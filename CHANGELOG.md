# CHANGELOG — xjtu-stu-login

> 追加型（最新在最下）。每条 = 一次持久化/交付。


## 2026-10-12 —— v0.2.0：修复「无法保活」根因 + UI 全面重做

- **修复 cleartext 拦网（保活失败根因）**：网关是 `http://10.6.18.2` 明文接口，Android 9+/targetSdk 28+ 默认禁止明文传输，`HttpURLConnection` 直接抛
  `CLEARTEXT communication ... not permitted by network security policy`，每轮登录必失败 ⇒ UI 显示「无法保活」。
  新增 `res/xml/network_security_config.xml`（`cleartextTrafficPermitted=true`，保留系统 CA）+ manifest `usesCleartextTraffic` 双保险放行。
- **保活健壮性**（此前即使清了明文，服务也会被系统清理）：
  - 网络变化立即重检：`ConnectivityManager.registerDefaultNetworkCallback` 唤醒轮询（3s 去抖），断网/切网不等下一个间隔；
  - 心跳落盘：`StatusStore` 把心跳+上次状态写普通 prefs ⇒ 回前台能判断前台服务死活并自动自愈重启；
  - 电池优化白名单：主界面新增「后台保活白名单」入口（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，ROM 无该页则退列表）；
  - 单轮检测持 PARTIAL_WAKE_LOCK（Doze 下保证请求真发出去）；
  - SSID 读不到不再永久跳过：缺定位权限按「未知网络」继续尝试并在日志提示；Android 13+ 同时请求 `NEARBY_WIFI_DEVICES` 与 `ACCESS_FINE_LOCATION`（仅靠近设备权限时 SSID 仍会被系统打码）；
  - 配置/运行态读写加进程内缓存：不再每轮重建 MasterKey + EncryptedSharedPreferences（旧实现慢且偶发失败）；
  - 网关地址支持自带 `https://`/`http://` 前缀；网关提示「IP has been online」视为已在线。
- **UI 重做到 Shizuku 水准**：MD3 全量 color role 主题（亮/暗两套，交大蓝 #1F5E9E 种子）、NoActionBar + 大标题材质工具栏、大圆角填充卡片、
  自适应图标（渐变蓝底白色 WiFi）、全套 Material 图标、设置页卡片化（FAB 保存 / 未保存脏标记 / 未保存返回确认）、高级参数折叠卡片、
  状态点三色语义（在线绿 / 离线红 / 跳过黄）、常驻通知带「立即检测 / 停止」动作、日志可展开 / 复制。
- **其他**：viewBinding 化；通知渠道在 Application 预建；开机自启补 `MY_PACKAGE_REPLACED`；versionCode 2 / versionName 0.2.0。
- **交付**：`dist/xjtu-stu-login-v0.2.0.apk`（6664680 B / sha256 `434c1f8f3f63bbb94741bb309931eedf4b8d83349c44a8fa4455a529f732182d`）；`./gradlew :engine:test` 回归绿（8 测）。

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

## 2026-10-12（二）—— v0.2.1：VPN（VPN 覆盖网）下绕过隧道直连网关

> 起因：真机实测 v0.2.0 —— 第一个错误是账号没填 `@stu` 后缀（配置问题，已解：设置页字段提示改为「西交校园网填 stu」、新增账号默认带 `stu`、网关返回 `login_error` 时日志直接给人话提示）；第二个错误是**手机开着 VPN 覆盖网时登录必失败**。

- **根因**：VPN 抢占默认路由后（1）`ConnectivityManager.activeNetwork` 变成 VPN 隧道 ⇒ 旧逻辑判定「不是 WiFi」直接跳过、永不登录；（2）拿到的 IPv4 是隧道地址（172.31.x.x），网关根本不认；（3）即便发出去也是穿隧道，网关看到的源 IP 不对。
- **修法**：给协议引擎开一个 `ConnectionFactory` 注入口（纯 JDK `fun interface`，引擎仍零 Android 依赖），App 侧用 `Network.openConnection(url)` 把请求 socket **绑定到 TRANSPORT_WIFI 那张网卡** ⇒ 报文从 WiFi 直出、不穿隧道，源 IP 与网关看到的一致。绑定失败自动退回默认路由（不硬失败），并在状态里标 `绑定WiFi失败`。
- **WiFi 识别重写**（`WifiKit`）：显式在 `allNetworks` 里挑 `TRANSPORT_WIFI && !TRANSPORT_VPN` 的网络，SSID/IP/绑定都用它；`NetInfo` 新增 `vpnActive` / `wifiNetwork`。状态详情在绕过时显示 `· VPN已绕过`。
- **测试**：引擎 8 → **10 条**（新增 `connectionFactoryIsUsed`、`connectionFactoryFailurePropagates`），全绿。
- **顺带**：网关返回网页（`<script>` 重定向）而非 JSON 时，报错改为「该网关返回网页而非接口数据（可能不是当前网络的有效 AC）」。
