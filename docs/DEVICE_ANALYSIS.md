# PGKM10 实机分析基线

分析日期：2026-07-25。

## 设备与 Root 环境

- 厂商/型号：OnePlus PGKM10
- 设备代号：OP5565
- 系统：Android 15 / API 35
- ColorOS：`PGKM10_15.0.0.700(CN01)` / V15.0.0
- 安全补丁：2025-03-01
- CPU ABI：arm64-v8a
- KernelSU：3.2.5，Root SELinux 上下文 `u:r:ksu:s0`
- SELinux：Enforcing
- Zygisk Next：1.3.1
- LSPosed：v2.0.3 (7716)

本项目仅把 `com.android.settings` 作为默认作用域，不 Hook `system_server`、SystemUI
或模块自身。

## Settings.apk 基线

- 路径：`/system_ext/priv-app/Settings/Settings.apk`
- 大小：153,596,392 字节
- SHA-256：
  `5bfd4739917372221099ff3fcfcf39109e11ed0753ddb656c11a4450d6eed107`
- DEX 数量：6

如果 OTA 后哈希发生变化，应重新确认下列类和资源，再启用设备定向 Hook。

## 真实页面结构

普通二级设置页：

```text
SettingsBaseActivity
└── SettingsActivity / OplusSettingsActivity
    └── SettingsBaseFragment
        └── COUIRecyclerView
```

关键实现：

- `com.android.settings.core.SettingsBaseActivity`
  使用 `settings_base_layout`，内容容器为 `content_parent` 和 `content_frame`。
- `com.oplus.settings.SettingsBaseFragment`
  使用 `coui_preference_recyclerview`，并在 `onViewCreated` 中为列表设置
  `coui_list_preference_bg`。
- 实际主入口是
  `com.oplus.settings.feature.homepage.OplusSettingsHomepageActivity`，
  继承 `AppCompatActivity`，使用 `settings_homepage_container_oplus`，不是 AOSP
  `SettingsHomepageActivity`。
- 首页包含默认隐藏的 `search_bg_mask`。该遮罩负责搜索状态和触摸退出，不进行透明化。

`coui_list_preference_bg.9.png` 和 `coui_statusbar_bg.9.png` 均为 8×8 全透明
9-patch，因此通用透明化逻辑继续严格跳过图片 Drawable，不需要替换这两个资源。

`coui_toolbar_bg` 是带纯色填充的 shape Drawable。Toolbar 属于交互和导航区域，
模块保留该背景，避免降低标题、返回按钮的可读性。

## 定向 Hook 决策

- 保留 `Activity.onPostResume`，延迟 180ms 注入。
- 补充 `androidx.fragment.app.Fragment.performResume`：
  同一 Activity 内切换 Fragment 时重新检查配置和敏感页。
- 敏感 Fragment 在恢复前立即移除已存在的背景层，再于恢复后扫描完整 Fragment 树。
- 透明化从 `recycler_view`、`content_frame`、`content_parent`、
  `settings_homepage_container` 和 `android.R.id.content` 分别开始，单次深度最多 4 层。
- 通用逻辑只清理完全不透明的 `ColorDrawable` 容器，并继续跳过图片、selector、
  ripple、渐变和交互控件。
- COUI 偏好卡片使用匿名 Drawable 绘制，且其 `setAlpha` 是空实现。模块因此只调整
  `COUICardListSelectedItemLayout.mCardBackgroundColor` 的 Alpha，保留原有路径、
  圆角、选择状态和触摸效果；关闭配置时恢复原颜色。

