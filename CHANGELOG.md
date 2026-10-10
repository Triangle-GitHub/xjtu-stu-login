# CHANGELOG — xjtu-stu-login

> 追加型（最新在最下）。每条 = 一次持久化/交付。

## 2026-09-27 —— 项目启动：设计 + M0 引擎 + M1 App 壳 + 上游回报

- **立项**：从手机 Termux 取回 keep_srun v2.6 全量素材（`ref-phone/`）与 BitSrunLoginGo 源码蓝本（`ref-src/`）；确认手机 `bitsrun` = BitSrunLoginGo 编译产物（aarch64 ELF）。
- **设计定稿 v0.2**：仅 Android 原生 App（脱离 Termux），Kotlin 引擎（纯 JVM）+ Android 壳；协议仅一条路径（XEncode Challenge 登录）；离线验证策略。`docs/DESIGN.md`
- **M0 协议引擎**（`engine/`）：XEncode/XBase64/SrunCrypto/SrunClient/MiniJson；**8 项 JVM 单测全绿**（12 条 golden 向量逐字节对齐 + chksum 全链路 + mock 网关 5 场景；原文写作「9 项」，2026-10-11 核实校正）。
- **M1 App 壳**（`app/`）：MD3 设置页（网关/账号动态增删勾选 + 全参数）+ 状态页 + 前台服务 + 原生 WiFi + EncryptedSharedPreferences + 开机自启；`assembleDebug` 出 `xjtu-stu-login-v0.1.0.apk`（6.6 MB）。
- **交付**：APK 已 ssh phone → Termux adb 静默安装至真机（HUAWEI ALN-AL00 / Android 12），未启动。
- **上游回报**：发现 BitSrunLoginGo 两个 bug（XEncode pad 循环 off-by-one 潜在 panic；XDecode 不可逆），提交 https://github.com/Mmx233/BitSrunLoginGo/issues/64（无隐私信息）。
- **环境**：build-host 构建链就绪（JDK 21 / Gradle 8.13 / /opt/android-sdk；Go 1.24.5 仅用于 golden 生成器）。（2026-10-11 更新：构建改走仓库内 wrapper `9.6.1`；Go 在 `/usr/local/go/bin`，**不在默认 PATH**）
- ⏳ 待办：M2 真机联调（用户实测）结果回填；WiFi 自动连接、双账号、release 签名为后续可选增强。

## 2026-10-11 —— 仓库迁移到 NAS + 构建入口自治（无功能变更）

- **迁移**：`~/xjtu-stu-login` → `.`（rsync 校验差异 0，git 对象完好，源目录已删）。
- **构建入口**：入库 gradle wrapper（钉 Gradle 9.6.1，本机 wrapper 缓存已有 ⇒ 无需联网）。原全局 `全局 Gradle` 已被同日清理删掉，README/DESIGN 里的绝对路径命令随之作废。
- **回归验证**：`./gradlew :engine:test :app:assembleDebug` 通过（engine 8 测全绿），产物 `app-debug.apk` 与交付件 `xjtu-stu-login-v0.1.0.apk` sha256 一致（Gradle 8.9 与 9.6.1 两条路径均复现）。
- **去硬编码**：`tools/vector-gen/main.go` 不再写死 `~/xjtu-stu-login/...`，改 `-out` 参数（默认 `engine/src/test/resources/golden.json`，相对当前目录）；重跑生成器输出与既有 golden.json 逐字节一致（12 向量）。
- ⏳ 待办不变：M2 真机联调（用户实测）结果回填。
- **文档事实校正**：M0 单测数 9 → **8**（`XEncodeGoldenTest` 3 + `SrunClientMockTest` 5）；DESIGN §7 构建环境重写（必须显式 JDK 21、默认 java 25 不可用、wrapper 免下载、aliyun 镜像 + 代理 7893 兜底、`gradle :engine:test` → `./gradlew`）；补 Go 的 PATH 与 `-out` 用法；README 目录补 `gradle/`。
- **未能复核**：真机装机状态（`ssh phone` 不通，手机不在同网段）——M2 前提不变，但本次未再确认；APK 字节数与 sha256 已核对（6580814 B / `a63e1ad0…`）。
