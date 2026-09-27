# CHANGELOG — xjtu-stu-login

> 追加型（最新在最下）。每条 = 一次持久化/交付。

## 2026-09-27 —— 项目启动：设计 + M0 引擎 + M1 App 壳 + 上游回报

- **立项**：从手机 Termux 取回 keep_srun v2.6 全量素材（`ref-phone/`）与 BitSrunLoginGo 源码蓝本（`ref-src/`）；确认手机 `bitsrun` = BitSrunLoginGo 编译产物（aarch64 ELF）。
- **设计定稿 v0.2**：仅 Android 原生 App（脱离 Termux），Kotlin 引擎（纯 JVM）+ Android 壳；协议仅一条路径（XEncode Challenge 登录）；离线验证策略。`docs/DESIGN.md`
- **M0 协议引擎**（`engine/`）：XEncode/XBase64/SrunCrypto/SrunClient/MiniJson；**9 项 JVM 单测全绿**（12 条 golden 向量逐字节对齐 + chksum 全链路 + mock 网关 5 场景）。
- **M1 App 壳**（`app/`）：MD3 设置页（网关/账号动态增删勾选 + 全参数）+ 状态页 + 前台服务 + 原生 WiFi + EncryptedSharedPreferences + 开机自启；`assembleDebug` 出 `xjtu-stu-login-v0.1.0.apk`（6.6 MB）。
- **交付**：APK 已 ssh phone → Termux adb 静默安装至真机（HUAWEI ALN-AL00 / Android 12），未启动。
- **上游回报**：发现 BitSrunLoginGo 两个 bug（XEncode pad 循环 off-by-one 潜在 panic；XDecode 不可逆），提交 https://github.com/Mmx233/BitSrunLoginGo/issues/64（无隐私信息）。
- **环境**：build-host 构建链就绪（JDK 21 / Gradle 8.13 / /opt/android-sdk；Go 1.24.5 仅用于 golden 生成器）。
- ⏳ 待办：M2 真机联调（用户实测）结果回填；WiFi 自动连接、双账号、release 签名为后续可选增强。