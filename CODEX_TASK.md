# 实现目标

创建可编译安装的 Kotlin / API 35 单模块 Android 工程“设置萌化背景”，包名
`com.local.settingsanimebackground`。通过 LSPosed 仅作用于 `com.android.settings`，
在普通设置页面底层注入不接管触摸的自定义图片背景。

工程实现中文配置界面、SAF 图片导入、EXIF 纠正、2160px 限制、WebP 内部保存、
严格按 UID 放行的只读跨进程 Provider、配置版本与通知、透明容器与卡片的保守处理、
Android 15 RenderEffect 模糊、系统栏保存恢复、状态栏图标自动亮度，以及密码、锁屏、
生物识别、授权、紧急相关页面排除。不得修改系统分区或系统 APK，不得 Hook
system_server、SystemUI 或执行自动重启、刷写操作。
