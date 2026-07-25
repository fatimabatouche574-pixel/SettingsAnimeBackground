# 设置萌化背景

这是一个面向一加 Ace 系列、ColorOS 15、Android 15（API 35）的 LSPosed 模块。它通过 Hook `com.android.settings` 的 Activity 生命周期，在系统设置普通页面的最底层加入用户选择的背景图片，不修改 Settings.apk，也不写入任何系统分区。

当前设备定向基线为 OnePlus PGKM10、`PGKM10_15.0.0.700(CN01)`、KernelSU
3.2.5 和 LSPosed v2.0.3。完整逆向证据与 OTA 后复核项见
[`docs/DEVICE_ANALYSIS.md`](docs/DEVICE_ANALYSIS.md)。

## 功能

- 自定义 JPEG、PNG 或 WEBP 背景，导入时修正 EXIF 方向并限制最长边为 2160 像素
- 背景透明度、黑色遮罩、0～30dp 模糊和三种缩放模式
- 保守清理页面顶层纯色不透明背景
- 可选卡片透明度，保留可安全复制的圆角、描边与涟漪 Drawable
- 可覆盖状态栏、导航栏，并支持自动判断状态栏图标明暗
- 配置和图片通过严格 UID 校验的只读 ContentProvider 提供给系统设置
- 配置变更实时通知，Activity 恢复时也会自动重新读取
- 密码、锁屏、生物识别、授权和紧急相关页面强制排除
- 诊断日志、安全停止设置作用域、恢复默认设置

## 构建

需要 JDK 17 和 Android SDK Platform 35。工程包含 Gradle Wrapper，不要求安装 Android Studio。

```bash
export ANDROID_HOME=/你的/Android/Sdk
./gradlew :app:assembleDebug
```

生成的 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions 云端构建

仓库包含 `.github/workflows/android.yml`。推送到 `main`/`master`、创建 Pull Request，
或在 Actions 页面手动运行“构建 Android APK”，都会在 GitHub 的 Ubuntu Runner 上：

1. 配置 JDK 17 与 Android SDK Platform 35；
2. 校验 Gradle Wrapper 并运行 Debug 单元测试；
3. 构建 `app-debug.apk`；
4. 上传 `SettingsAnimeBackground-debug` 构建产物和测试报告。

在工作流运行详情页面底部的“构建产物”区域即可下载 APK。

## 安装和启用

1. 安装生成的 APK。
2. 在 LSPosed 中启用“设置萌化背景”模块。
3. 作用域只勾选“设置”（`com.android.settings`），不要勾选模块自身、SystemUI 或 Android 系统。
4. 打开模块应用，选择背景并调整参数。
5. 首次启用模块后，点击“安全重启设置作用域”，或手动强制停止并重新打开系统设置。

配置变化通常不需要重启设置，已经恢复的页面会通过 Provider 通知刷新。

## 安全边界

模块不替换系统 APK、不使用 RRO、不 Hook system_server 或 SystemUI、不修改应用签名，也不包含刷写、重启手机或分区写入代码。“安全重启设置作用域”只在用户确认后以 Root 执行：

```text
am force-stop --user current com.android.settings
```

Provider 导出是跨 UID 读取所必需的。每个入口都会检查 Binder 调用 UID，只接受模块自身 UID 或设备上 `com.android.settings` 的系统 UID；图片路径固定为内部目录中的 `background.webp`，外部不能写入、删除或选择任意路径。

## 诊断

启用“诊断日志”后，可查看统一 Tag：

```bash
adb logcat | grep AnimeSettingsBg
```

LSPosed 管理器的模块日志中也会出现同一 Tag。设备厂商更改页面容器实现时，未知复杂 Drawable 会被跳过，而不是强制清除。

## 项目结构

- `ui/`：中文配置界面和 SAF 图片选择
- `image/`：安全解码、EXIF 纠正、缩放、WebP 保存和亮度预计算
- `provider/`：跨进程只读配置与图片 Provider
- `hook/`：LSPosed 入口、生命周期管理、背景注入、透明化和系统栏恢复
- `config/`：配置模型、版本递增和通知协议

## 已知限制

本项目优先针对固定的 ColorOS 15 / API 35 环境。ColorOS 小版本如果替换了设置页面结构，保守透明化可能会跳过某些容器；这不会影响原页面点击、滚动和输入。模块停用后应强制停止一次系统设置进程，让未加载模块的新进程恢复完全原生状态。

OTA 后如果 Settings.apk 哈希与设备分析基线不同，应先关闭模块作用域并重新核对页面
类结构；模块不会尝试自动修改系统 APK、刷写分区或重启手机。
