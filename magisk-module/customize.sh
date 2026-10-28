#!/sbin/sh
#
# customize.sh —— 模块自定义脚本（通用，Magisk / KernelSU 双兼容）
#
# 本脚本仅用于安装期打印日志，不执行任何实际安装逻辑。
# 严禁出现 Magisk 专属命令（如 magisk --install-module、
# . /data/adb/magisk/util_functions.sh 等）。
#
# ui_print 在 Magisk / KernelSU 环境中均由注入的 shell 函数提供；
# 若独立执行（如 sh -n 或手动调试），则回退到 echo。
#

if command -v ui_print >/dev/null 2>&1 || type ui_print >/dev/null 2>&1; then
    :
else
    ui_print() { echo "$@"; }
fi

ui_print "***********************************************"
ui_print " HidPager Privileged module installing ..."
ui_print " - 预置 APK 到 /system/priv-app 以获得"
ui_print "   BLUETOOTH_PRIVILEGED 权限"
ui_print " - 激活经典蓝牙 HID 遥控（上一页/下一页/"
ui_print "   播放暂停，面向阅星瞳 X3）"
ui_print "***********************************************"
