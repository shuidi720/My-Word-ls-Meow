# 第三方开源组件许可声明

本项目基于 MIT 许可开源（见 [LICENSE](LICENSE)），衍生自 **QQNHYhelper**（Copyright (c) 2026 qaqdym, AnotherCream，MIT License）。

本项目在开发过程中参考了 LaiNova_ 制作的「QQ喵喵助手」的悬浮窗交互思路，在此致谢；当前悬浮窗代码为独立实现。

## 依赖的开源组件

| 组件 | 用途 | 许可证 |
| --- | --- | --- |
| [AndroidX (core-ktx / appcompat / constraintlayout / recyclerview)](https://developer.android.com/jetpack/androidx) | 兼容与基础 UI | Apache License 2.0 |
| [Material Components for Android](https://github.com/material-components/material-components-android) | Material 风格控件 | Apache License 2.0 |
| [AndroidX Room](https://developer.android.com/jetpack/androidx/releases/room) | 本地规则数据库 | Apache License 2.0 |
| [Shizuku API / Provider](https://github.com/RikkaApps/Shizuku-API)（dev.rikka.shizuku:api / provider:12.2.0） | 通过 ADB/root 执行系统级保活白名单命令（可选功能） | Apache License 2.0 |
| [Kotlin](https://kotlinlang.org/) 标准库 | 开发语言 | Apache License 2.0 |

### Apache License 2.0
上述 Apache-2.0 组件的许可全文见：<https://www.apache.org/licenses/LICENSE-2.0>

### MIT License
MIT 许可全文见本仓库 [LICENSE](LICENSE)。

## 权限与隐私说明
- 本应用**不申请联网权限（无 INTERNET）**，所有规则与数据均保存在本地，不会上传任何用户信息。
- 无障碍服务（Accessibility Service）仅用于在用户授权下，对当前输入框文本执行本地关键词/语句替换与句尾附加，不读取、不上传任何界面内容。
- 悬浮窗（SYSTEM_ALERT_WINDOW）仅用于提供无障碍写入被拦截时的备用变语入口。
- Shizuku 相关保活命令为可选功能，需用户主动连接 Shizuku 并点击应用后才会执行。
