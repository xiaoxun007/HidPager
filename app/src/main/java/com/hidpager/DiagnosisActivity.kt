package com.hidpager

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hidpager.hid.HidDeviceService
import java.util.concurrent.TimeUnit

/**
 * 环境自检/诊断页：逐项检测蓝牙开关、权限、HID profile、HID 注册状态、
 * root、系统 prop、dumpsys 占用者、设备概览，并给出可执行的修复建议。
 *
 * - 普通 View 体系（代码构建 LinearLayout），不用 RecyclerView/Compose；
 * - 检测/root 命令全部在后台线程串行执行，结果经 runOnUiThread 追加到日志区；
 * - root 命令绝不自动执行，仅用户点击修复按钮触发，且均有 5 秒超时保护。
 */
class DiagnosisActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var logView: TextView
    private lateinit var suggestionBox: LinearLayout

    @Volatile private var service: HidDeviceService? = null
    @Volatile private var running = false

    private data class RootResult(val exitCode: Int, val output: String, val isTimeout: Boolean)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? HidDeviceService.LocalBinder)?.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.diagnosis_title)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        btnStart = Button(this).apply {
            text = getString(R.string.diagnosis_btn_start)
            setOnClickListener { startCheck() }
        }
        root.addView(btnStart)

        logView = TextView(this).apply {
            text = getString(R.string.diagnosis_log_hint)
            setTextIsSelectable(true)
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        val scroll = ScrollView(this).apply {
            addView(logView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f))

        suggestionBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(suggestionBox)

        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        // 复用 MiuixActivity 绑定模式；绑定失败不崩溃，第 4 项会提示
        try {
            bindService(
                Intent(this, HidDeviceService::class.java),
                serviceConnection, Context.BIND_AUTO_CREATE
            )
        } catch (e: Exception) { /* ignore */ }
    }

    override fun onStop() {
        super.onStop()
        try { unbindService(serviceConnection) } catch (e: Exception) { /* ignore */ }
        service = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 后台线程执行：ProcessBuilder("su","-c",cmd)，5 秒超时后强制销毁；合并 stdout+stderr；异常不崩溃 */
    private fun runRootCommand(cmd: String): RootResult {
        var proc: Process? = null
        return try {
            proc = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            // 先 waitFor 超时判定，再读流：避免命令卡死时 readText 永久阻塞在 EOF 上
            val finished = proc.waitFor(5, TimeUnit.SECONDS)
            val out = if (finished) {
                proc.inputStream.bufferedReader().readText()
            } else {
                proc.destroyForcibly()
                proc.inputStream.bufferedReader().readText() // 进程已终止，读剩余缓冲至 EOF
            }
            if (finished) RootResult(proc.exitValue(), out.trim(), false)
            else RootResult(-1, out.trim(), true)
        } catch (e: Exception) {
            try { proc?.destroyForcibly() } catch (_: Exception) { /* ignore */ }
            RootResult(-1, "root 命令异常: ${e.message}", false)
        }
    }

    /** 任意线程可调：追加一行到日志区 */
    private fun report(line: String) = runOnUiThread { logView.append("\n" + line) }

    private fun startCheck() {
        if (running) return
        running = true
        btnStart.isEnabled = false
        suggestionBox.removeAllViews()
        logView.text = ""

        Thread {
            val suggestions = mutableListOf<String>()
            var rootOk = false
            var occupantPkg: String? = null
            try {
                val adapter =
                    (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

                // 1. 蓝牙开关
                if (adapter?.isEnabled == true) {
                    report("✅ 1. 蓝牙开关：已开启")
                } else {
                    report("❌ 1. 蓝牙开关：未开启")
                    suggestions += "请先在系统设置开启蓝牙"
                }

                // 2. 运行时权限（API<31 按 ACCESS_FINE_LOCATION 口径）
                val need = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= 31) {
                    need += Manifest.permission.BLUETOOTH_SCAN
                    need += Manifest.permission.BLUETOOTH_CONNECT
                } else {
                    need += Manifest.permission.ACCESS_FINE_LOCATION
                }
                if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
                val missing =
                    need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) {
                    report("✅ 2. 运行时权限：齐全")
                } else {
                    report("❌ 2. 运行时权限缺失：${missing.joinToString(", ")}")
                    suggestions += "请在系统设置→应用权限中授予蓝牙/通知相关权限"
                }

                // 3. HID profile 支持（null = ROM 裁剪/不支持）
                val hid = try {
                    if (Build.VERSION.SDK_INT >= 31) {
                        getSystemService(BluetoothHidDevice::class.java)
                    } else {
                        getSystemService("bluetooth_hid_device") as? BluetoothHidDevice
                    }
                } catch (e: Exception) {
                    null
                }
                if (hid != null) {
                    report("✅ 3. HID profile：BluetoothHidDevice 可用")
                } else {
                    report("❌ 3. HID profile：BluetoothHidDevice 为 null，疑似 ROM 裁剪")
                    suggestions += "该 ROM 可能裁剪了 HID Device profile（小米 HyperOS 需在权限/外设设置放行）"
                }

                // 4. 注册状态：真实调用 registerHid() 验证是否被系统拦截
                val svc = service
                if (svc == null) {
                    report("⚠️ 4. 注册状态：服务未绑定（请先回主界面启动翻页服务）")
                } else if (svc.isRegistered()) {
                    report("✅ 4. 注册状态：HID App 已注册")
                } else {
                    report("⚠️ 4. 当前未注册，尝试调用 registerHid()…")
                    val ok = try {
                        svc.registerHid()
                    } catch (e: Exception) {
                        false
                    }
                    Thread.sleep(800L) // 等异步回调落定
                    val reg = svc.isRegistered()
                    if (reg) {
                        report("✅ 4. registerHid()=$ok，注册成功")
                    } else {
                        report("❌ 4. registerHid()=$ok 且最终未注册：疑似系统 UID 鉴权拦截或名额被占")
                        suggestions += "HID 注册被拦截：检查是否有其他应用占用 HID Device 注册名额"
                    }
                }

                // 5. root 检测
                val r = runRootCommand("id")
                if (!r.isTimeout && r.exitCode == 0 && r.output.contains("uid=0")) {
                    rootOk = true
                    report("✅ 5. root：su 可用（${r.output.take(40)}）")
                } else {
                    report("❌ 5. root：无 root（exit=${r.exitCode}）；6/7 项跳过")
                    suggestions += "无 root：prop/dumpsys 与一键修复不可用"
                }

                // 6. 系统 prop
                if (!rootOk) {
                    report("⚠️ 6. prop 检测：需 root，跳过")
                } else {
                    val p = runRootCommand("getprop bluetooth.profile.hid.device.enabled")
                    val v = p.output.trim()
                    if (v.equals("true", ignoreCase = true)) {
                        report("✅ 6. prop bluetooth.profile.hid.device.enabled=true")
                    } else {
                        report("❌ 6. prop 未开启（当前值：'$v'）")
                        suggestions += "可执行 setprop bluetooth.profile.hid.device.enabled true 后重启蓝牙栈（需 root）"
                    }
                }

                // 7. dumpsys bluetooth：本地过滤解析，最多保留 40 行
                if (!rootOk) {
                    report("⚠️ 7. dumpsys bluetooth：需 root，跳过")
                } else {
                    val d = runRootCommand("dumpsys bluetooth")
                    if (d.isTimeout) report("⚠️ 7. dumpsys bluetooth 读取超时，输出已截断")
                    val keys = d.output.lines().filter { l ->
                        l.contains("HID_DEVICE", ignoreCase = true) ||
                            l.contains("registered", ignoreCase = true) ||
                            l.contains("androidhidkeyboard", ignoreCase = true) ||
                            l.trim().contains("hid", ignoreCase = true)
                    }.take(40)
                    report("—— dumpsys bluetooth 关键行（共 ${keys.size} 行）——")
                    keys.forEach { report("   " + it.trim()) }
                    val pkgRe = Regex("[a-z][a-z0-9_]*(\\.[a-z0-9_]+){2,}")
                    val pkgs = keys.flatMap { pkgRe.findAll(it).map { m -> m.value } }.toSet()
                        .filter { !it.startsWith("android.") && !it.startsWith("com.android.") }
                    occupantPkg = pkgs.firstOrNull()
                    if (occupantPkg != null) {
                        report("⚠️ 疑似 HID 占用者包名：$occupantPkg")
                        suggestions += "检测到 $occupantPkg 占用 HID Device 注册，可 force-stop 后重启蓝牙栈"
                    } else {
                        report("—— 未发现明显 HID Device 占用者包名")
                    }
                }

                // 8. 设备概览
                try {
                    val bonded = adapter?.bondedDevices?.size ?: 0
                    val cur = service?.getConnectedDevice()?.name ?: "（无）"
                    report("✅ 8. 设备概览：已配对 $bonded 台；当前连接：$cur")
                } catch (e: SecurityException) {
                    report("⚠️ 8. 设备概览：缺蓝牙权限，无法读取配对列表")
                }
            } catch (e: Exception) {
                report("⚠️ 自检中断：${e.message}")
            } finally {
                val finalRoot = rootOk
                val finalPkg = occupantPkg
                runOnUiThread {
                    buildSuggestions(suggestions, finalRoot, finalPkg)
                    btnStart.isEnabled = true
                    running = false
                }
            }
        }.start()
    }

    /** 动态渲染修复建议与一键修复按钮（root 可用才显示修复按钮） */
    private fun buildSuggestions(items: List<String>, rootOk: Boolean, occupantPkg: String?) {
        suggestionBox.removeAllViews()
        if (items.isEmpty()) {
            suggestionBox.addView(TextView(this).apply { text = "未发现明显异常。" })
        } else {
            suggestionBox.addView(TextView(this).apply {
                text = "—— 修复建议 ——"
                typeface = Typeface.MONOSPACE
            })
            items.forEach { s -> suggestionBox.addView(TextView(this).apply { text = "• $s" }) }
        }
        if (rootOk) {
            suggestionBox.addView(Button(this).apply {
                text = getString(R.string.diagnosis_restart_bluetooth)
                setOnClickListener { confirmThen("svc bluetooth disable / enable") { restartBluetooth() } }
            })
        }
        if (occupantPkg != null) {
            suggestionBox.addView(Button(this).apply {
                text = "${getString(R.string.diagnosis_force_stop)}（$occupantPkg）"
                setOnClickListener { confirmThen("am force-stop $occupantPkg") { forceStop(occupantPkg) } }
            })
        }
    }

    /** AlertDialog 二次确认（含副作用提示），确认后后台线程执行 */
    private fun confirmThen(cmdDesc: String, task: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(R.string.diagnosis_confirm_title)
            .setMessage(getString(R.string.diagnosis_confirm_msg, cmdDesc))
            .setPositiveButton(R.string.diagnosis_confirm_ok) { _, _ ->
                Thread {
                    report("▶ 执行：$cmdDesc")
                    task()
                }.start()
            }
            .setNegativeButton(R.string.diagnosis_confirm_cancel, null)
            .show()
    }

    private fun restartBluetooth() {
        val d1 = runRootCommand("svc bluetooth disable")
        report("  svc bluetooth disable → exit=${d1.exitCode} ${d1.output.trim()}")
        try {
            Thread.sleep(1500L)
        } catch (_: Exception) { /* ignore */ }
        val d2 = runRootCommand("svc bluetooth enable")
        report("  svc bluetooth enable → exit=${d2.exitCode} ${d2.output.trim()}")
    }

    private fun forceStop(pkg: String) {
        val d = runRootCommand("am force-stop $pkg")
        report("  am force-stop $pkg → exit=${d.exitCode} ${d.output.trim()}")
    }
}
