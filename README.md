# ntfy Android (LSPosed 模块增强版)

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Xposed API](https://img.shields.io/badge/Xposed-API%20102-green.svg)](https://github.com/libxposed/api)
[![Release](https://img.shields.io/github/v/release/starshadows/ntfy-android-lsposed?color=success)](https://github.com/starshadows/ntfy-android-lsposed/releases)

本项目是基于官方 [ntfy-android](https://github.com/binwiederhier/ntfy-android) 深度定制的 **LSPosed / Xposed 系统级增强版**。通过将网络长连接常驻于 Android 核心系统进程（`com.android.systemui`），彻底解决国产定制系统（如 Flyme / HyperOS / ColorOS 等）杀后台、漏通知、需要常驻前台通知等痛点问题。

---

## ✨ 核心特性

- ⚡ **SystemUI 系统级常驻监听**：类似 `syncclipboard-xposed` 架构，将 WebSocket 监听引擎常驻注入到 `com.android.systemui` 中。即便被杀后台、手机重启后从未打开过应用，也能在息屏状态下秒级稳定接收消息。
- 🚫 **无常驻通知栏烦恼**：默认彻底移除官方原版刺眼的“正在监听通知”（Listening for incoming notifications）前台常驻服务通知，状态栏不再被强行霸占。
- 🎯 **精准对齐系统渠道规则与震动配置**：
  - **最高优先级 (Priority 5 / Max)**：横幅浮动 + 锁屏显示 + 状态栏 + **多段阶梯振动提醒**
  - **高优先级 (Priority 4 / High)**：横幅浮动 + 锁屏显示 + 状态栏 + **严格不震动**（遵循系统渠道设置）
  - **默认优先级 (Priority 3 / Default)**：通知栏与锁屏显示 + **不震动**
  - **低/最低优先级 (Priority 2 & 1)**：**静默通知**，通知中心静默收纳
  - 支持动态读取应用内各渠道真实的 `shouldVibrate()` 配置并实时同步至底层。
- 🧹 **打开应用自动清理通知栏残留**：进入应用主界面时，自动通过跨进程 IPC 撤除所有由 ntfy 产生的通知；进入具体主题时，自动精准清理该主题的状态栏通知。
- 💬 **会话内实时无感刷新 & 即刻已读**：
  - 进入主题详情页时立即将所有消息标记为已读（消除未读绿点气泡，无需“退出再重进”）；
  - 停留于会话内时，新推送消息通过 Room LiveData 实时顶格刷出，并自动抑制状态栏重复弹窗，体验完全对齐原生即时通讯工具。
- 🛡️ **单通道派发，彻底根治重复弹窗**：应用前台与 SystemUI 底层深度互锁，打开应用时自动退避应用内监听服务，全权由系统单通道派发，绝不出现双重弹窗。
- 🔄 **智能指数退避与防 429 访问风暴**：网络切换平滑防抖，重连具备阶梯退避策略（5s, 10s, 20s, 30s, 60s, 120s）；一旦遭遇 HTTP 429 速率限制，自动触发 60 秒安全冷却期，保护服务器免受冲击。

---

## 📲 安装与启用指南

1. **下载安装**：前往 [Releases 页面](https://github.com/starshadows/ntfy-android-lsposed/releases) 下载最新签名的 `ntfy-lsposed-release.apk` 并安装到手机。
2. **启用模块**：
   - 打开 **LSPosed** / **LSPatch** 管理器；
   - 找到并启用 **ntfy** 模块；
   - 推荐勾选作用域：
     - `系统界面` (`com.android.systemui`) —— **必须勾选**（常驻保活与系统级弹窗核心）
     - `ntfy` (`io.heckel.ntfy`) —— **推荐勾选**（前台自动互锁与渠道状态同步）
3. **生效配置**：重启 `SystemUI`（或直接重启手机一次）。
4. **添加订阅**：打开 ntfy 应用添加您的私有服务器或官方主题。之后即可彻底划掉应用后台，享受开机自启、全天候无前台常驻的极速推送体验！

---

## 🛠️ 自行构建 (Build from Source)

本项目依赖 Android SDK 及 JDK 17：

```bash
# 克隆仓库
git clone https://github.com/starshadows/ntfy-android-lsposed.git
cd ntfy-android-lsposed

# 构建 F-Droid Release 变体（无需 Google Play / Firebase 凭据）
./gradlew assembleFdroidRelease

# 构建完成后使用 apksigner 签名即可安装使用
```

---

## 📜 鸣谢与协议 (Credits & License)

- 原生 Android 客户端由 [Philipp C. Heckel](https://heckel.io) 开发，开源于 [binwiederhier/ntfy-android](https://github.com/binwiederhier/ntfy-android)。
- Xposed 架构设计参考并汲取自 [shaklow/syncclipboard-xposed](https://github.com/shaklow/syncclipboard-xposed)。
- 本项目遵循 [Apache License 2.0](LICENSE) 协议发布。
