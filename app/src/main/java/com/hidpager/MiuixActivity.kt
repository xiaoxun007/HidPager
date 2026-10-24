package com.hidpager

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Miuix（HyperOS 风格）示例界面。
 *
 * 仅用于演示 Compose + Miuix 的接入效果：
 * ThemeController（跟随系统深浅色）+ MiuixTheme + Scaffold(topBar) + Button。
 * 页面不调用任何蓝牙 HID 服务，hid/ 包下的核心逻辑零改动。
 */
class MiuixActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 可用模式：System / Light / Dark / MonetSystem / MonetLight / MonetDark
            val controller = remember { ThemeController(ColorSchemeMode.System) }
            MiuixTheme(controller = controller) {
                Scaffold(
                    topBar = { TopAppBar(title = "翻页遥控器") },
                    content = { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .padding(horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(
                                16.dp,
                                Alignment.CenterVertically
                            )
                        ) {
                            Button(onClick = {}) { Text("上一页") }
                            Button(onClick = {}) { Text("下一页") }
                            Text("Miuix 风格界面预览")
                            Text("核心 HID 服务未做任何改动")
                        }
                    }
                )
            }
        }
    }
}
