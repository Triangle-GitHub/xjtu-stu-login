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
- **交付**：APK 已 ssh 真机(adb over ssh) → Termux adb 静默安装至真机（HUAWEI ALN-AL00 / Android 12），未启动。
- **上游回报**：发现 BitSrunLoginGo 两个 bug（XEncode pad 循环 off-by-one 潜在 panic；XDecode 不可逆），提交 https://github.com/Mmx233/BitSrunLoginGo/issues/64（⚠️ 2026-10-12 更正：该 issue 正文实含真实口令示例，属已公开信息，口令须轮换——见 2026-10-12 条目）。
- **环境**：构建机 构建链就绪（JDK 21 / Gradle 8.13 / /opt/android-sdk；Go 1.24.5 仅用于 golden 生成器）。（2026-10-11 更新：构建改走仓库内 wrapper `9.6.1`；Go 在 `/usr/local/go/bin`，**不在默认 PATH**）
- ⏳ 待办：M2 真机联调（用户实测）结果回填；WiFi 自动连接、双账号、release 签名为后续可选增强。

## 2026-10-11 —— 仓库迁移到 NAS + 构建入口自治（无功能变更）

- **迁移**：`~/xjtu-stu-login` → `（仓库根）`（rsync 校验差异 0，git 对象完好，源目录已删）。
- **构建入口**：入库 gradle wrapper（钉 Gradle 9.6.1，本机 wrapper 缓存已有 ⇒ 无需联网）。原全局 `旧版全局 Gradle` 已被同日清理删掉，README/DESIGN 里的绝对路径命令随之作废。
- **回归验证**：`./gradlew :engine:test :app:assembleDebug` 通过（engine 8 测全绿），产物 `app-debug.apk` 与交付件 `xjtu-stu-login-v0.1.0.apk` sha256 一致（Gradle 8.9 与 9.6.1 两条路径均复现）。
- **去硬编码**：`tools/vector-gen/main.go` 不再写死 `~/xjtu-stu-login/...`，改 `-out` 参数（默认 `engine/src/test/resources/golden.json`，相对当前目录）；重跑生成器输出与既有 golden.json 逐字节一致（12 向量）。
- ⏳ 待办不变：M2 真机联调（用户实测）结果回填。
- **文档事实校正**：M0 单测数 9 → **8**（`XEncodeGoldenTest` 3 + `SrunClientMockTest` 5）；DESIGN §7 构建环境重写（必须显式 JDK 21、默认 java 25 不可用、wrapper 免下载、aliyun 镜像 + 代理 7893 兜底、`gradle :engine:test` → `./gradlew`）；补 Go 的 PATH 与 `-out` 用法；README 目录补 `gradle/`。
- **未能复核**：真机装机状态（`ssh 真机(adb over ssh)` 不通，手机不在同网段）——M2 前提不变，但本次未再确认；APK 字节数与 sha256 已核对（6580814 B / `a63e1ad0…`）。

## 2026-10-12（二）—— v0.2.1：VPN（覆盖网 VPN 覆盖网）下绕过隧道直连网关

> 起因：真机实测 v0.2.0 —— 第一个错误是账号没填 `@stu` 后缀（配置问题，已解：设置页字段提示改为「西交校园网填 stu」、新增账号默认带 `stu`、网关返回 `login_error` 时日志直接给人话提示）；第二个错误是**手机开着 覆盖网 VPN 覆盖网时登录必失败**。

- **根因**：VPN 抢占默认路由后（1）`ConnectivityManager.activeNetwork` 变成 VPN 隧道 ⇒ 旧逻辑判定「不是 WiFi」直接跳过、永不登录；（2）拿到的 IPv4 是隧道地址（172.31.x.x），网关根本不认；（3）即便发出去也是穿隧道，网关看到的源 IP 不对。
- **修法**：给协议引擎开一个 `ConnectionFactory` 注入口（纯 JDK `fun interface`，引擎仍零 Android 依赖），App 侧用 `Network.openConnection(url)` 把请求 socket **绑定到 TRANSPORT_WIFI 那张网卡** ⇒ 报文从 WiFi 直出、不穿隧道，源 IP 与网关看到的一致。绑定失败自动退回默认路由（不硬失败），并在状态里标 `绑定WiFi失败`。
- **WiFi 识别重写**（`WifiKit`）：显式在 `allNetworks` 里挑 `TRANSPORT_WIFI && !TRANSPORT_VPN` 的网络，SSID/IP/绑定都用它；`NetInfo` 新增 `vpnActive` / `wifiNetwork`。状态详情在绕过时显示 `· VPN已绕过`。
- **测试**：引擎 8 → **10 条**（新增 `connectionFactoryIsUsed`、`connectionFactoryFailurePropagates`），全绿。
- **顺带**：网关返回网页（`<script>` 重定向）而非 JSON 时，报错改为「该网关返回网页而非接口数据（可能不是当前网络的有效 AC）」。
- **交付**：`dist/xjtu-stu-login-v0.2.1.apk`（6667444 B / sha256 `c34e36f97282647785fa2aa35bd5a33da670de94b481df141cbf1a416649acb5`）；已 `ssh phone` → Termux adb `install -r` 装机（versionCode 3，**未启动**）。

## 2026-10-12（三）—— v0.2.2：VPN 下三级路由策略（修 v0.2.1 的 EPERM 硬失败）

> 真机实测 v0.2.1（理论正确但一句 `Binding socket to network 432 failed: EPERM (Operation not permitted)` 全军覆没）：Android netd **拒绝 App 把 socket 绑到网卡**（华为 EMUI/HarmonyOS 上如此）。且这个错是 connect 阶段抛的 —— v0.2.1 那个「同步失败才退回」的兜底根本来不及触发，于是每轮 4 次尝试全废。v0.2.1 的第二个教训：**没 VPN 时也去绑网卡**，凭空踩 OEM 限制。

- **只在 VPN 活着时才尝试绑**（没 VPN 时默认路由本来就是 WiFi，绑了没收益只有风险）。
- **三级降级（每级真被拒才降）**：① `Network.openConnection` 绑 socket → ② `ConnectivityManager.bindProcessToNetwork(WiFi)` 进程绑（netd 另一条路径，不受 socket 级 EPERM 限制）→ ③ 默认路由；成功那级记入 `vpnStrategy`，状态显示 `VPN:已绕过(绑网卡)/已绕过(进程绑WiFi)/走默认路由`。
- **判定用「网关是否真的应答」（`Attempt.answered`）**，而不是只看异常类型 —— 这样任何一级路由不通都会自动降级。
- **可执行的提示**：VPN 下若超时/连接失败，日志直接给出「在 覆盖网 VPN 里把校园网段 10.6.0.0/16、10.172.0.0/16 设为直连，或临时关掉 VPN」。
- 重构：循环体里那坨 try/catch 抽成 `attemptLogin(acc, gw, cfg, net, mode, sb)` + `Attempt`（online/ip/rejected/error/warn/bindRejected/answered），主循环只剩策略调度。
- versionCode 4 / versionName 0.2.2。**交付**：`dist/xjtu-stu-login-v0.2.2.apk`（sha256 `c39ba8a118cd9ed93713c42e1b109a229b648d0b05a85f1d8f9552f8cac893ae`），已装机（未启动）。
- ⏳ 待真机复核：开着 覆盖网 VPN 时状态里 `VPN:` 后面到底是哪一级；若停在「走默认路由」且仍失败 ⇒ 覆盖网 VPN 在抢路由，需放行校园网段。

## 2026-10-12（四）—— v0.3.0：面向「西交-STU 同学」的默认值 + 标题栏改名 + 学号脱敏

- **标题栏改 `XJTU-STU Auto Login`**（工具栏与常驻通知共用新资源 `app_title`；桌面图标名仍为「校园网保活」，如需一并改英文可再说）。
- **默认值按受众定死**：校园网 SSID 默认 `XJTU_STU`；宿舍网 SSID **留空即可**（留空=不跳过任何网络，提示文案已写明「可留空」）；账号运营商后缀 `Account.userType` 默认 **`stu`**（新增账号自动带 `@stu`，字段提示改「默认 stu，一般不用改」）。
- **SSID 宽松匹配**：忽略大小写、`-`/空格 视作 `_` ⇒ 同学手输 `xjtu-stu`、`XJTU_STU`、`xjtu stu` 都能命中（原来只做精确比较）。
- **学号脱敏**：日志与通知里学号显示为 `前4位****后2位@stu`（原来打全）。原因：这个 App 面向同学，最常见的泄露路径就是把「运行日志」贴到群里求助 —— 脱敏从源头掐掉。设置页仍显示完整学号（本机自己的数据）。
- versionCode 5 / versionName 0.3.0。**交付**：`dist/xjtu-stu-login-v0.3.0.apk`（sha256 `54b290ad58b7024e46aab6c3be7dfd7e4c43e2d6ab9cf5002c865be53fe69918`），已装机（未启动）。

## 2026-10-12（五）—— v1.0.0：首个公开版本（P0 全做 + 同学向体验）

**P0（发布前必做）**
1. **release 签名 + R8**：固定 keystore（RSA 4096/30 年，`~/keys/xjtu-stu-login-release.jks`，口令在仓库根
   `keystore.properties`，两者均不入库）；release 开启 `minifyEnabled`+`shrinkResources`，配套 `app/proguard-rules.pro`
   （显式保留 Tink：`security-crypto` 走反射，R8 不保留会静默退化成明文 prefs）。debug 包 `debuggable=true`
   任何人 adb 可调试读凭据 ⇒ 不再分发 debug 包。
2. **LICENSE = AGPL-3.0**（XEncode 系 BitSrunLoginGo 的 Kotlin 移植，属衍生作品）+ **NOTICE** 列明来源与致谢
   （BitSrunLoginGo / BIT-srun-login-script / AndroidX·Material·Tink）。
3. **仓库清洗**：测试夹具与生成器里的真实学号/口令换成假值并**用假值重生成 `golden.json`**（引擎 10 测复跑绿）；
   `ref-phone/`、`ref-src/`（含明文口令 tarball、宿舍 WiFi 口令、AGPL 上游源码、bitsrun 二进制）**移出仓库**至
   `../xjtu-stu-login-refs/`，并用 `git filter-repo` 从全部历史中清除。
4. **README 隐私段**：无遥测、凭据仅本机 Keystore、为何要定位权限、日志已脱敏。

**同学向体验（本轮新增）**
5. **首启三步引导**：主界面顶部卡片列出「填学号密码 → 电池白名单 → 启动保活」，完成项自动打绿勾，三步全绿或手动关掉后隐藏。
6. **简化模式**：设置页默认只露「账号 + 开机自启」，网关/SSID/轮询参数收进「显示高级设置」开关。

- versionCode 6 / versionName **1.0.0**。
- **交付**：`dist/xjtu-stu-login-v1.0.0-release.apk`（2341447 B ≈ **2.3 MB**，
  sha256 `2f16118b7b5df0987e7dcebc6d855bb2fe509991aa5d17ecace6fcf5727bd30c`，签名 `CN=xjtu-stu-login`）；
  已装机真机（签名由 debug 换 release ⇒ 需卸载重装，**手机上的账号配置已清空，需重新填写**）。
- **已推送**：`<自建 Gitea>` master + tag `v1.0.0`；历史经 `git filter-repo` 重写（`ref-phone/`、`ref-src/`
  与真实学号/口令已从全部历史中清除，校验：ref 目录 0、旧口令 0、真实学号 0），旧历史备份为
  `<历史备份 bundle>`（**含旧凭据，仅本地保留，勿分发**）。

## 2026-10-12（六）—— v1.0.1：食堂等区域登录失败（补全 AC 端点 + 记忆上次成功网关）

> 现象：大部分区域正常，**食堂**每次都 `login_error`（IP 段 10.69.21.x，SSID 仍 XJTU_STU）。手动登录时门户页面观感不同、多级跳转。

- **根因（实测取证）**：Srun 是「一台 AC 一套 portal」，登录请求必须发给**服务当前 IP 的那台 AC**，发给别的 AC 通用答复就是 `login_error`（不是账号/后缀问题）。从校园网内实测扫描 `10.6.0-254.2`、`10.69.x.2`、`10.172.x.2`、`10.7.x.2`：
  **全网只有 4 个 srun 端点** —— `10.6.8.2`（总门户，响应形态为老版本 `error/res`）、`10.6.18.2`、`10.6.21.2`、`10.6.27.2`。
  而 App 默认网关只有 18.2/21.2 ⇒ 食堂那片的 AC 从未被尝试。
- **修法**：内置这 4 个端点作**兜底网关**（用户配置的优先且保持其顺序，未配过的自动补在后面，按 host 去重）——
  现有安装无需改配置即可受益；同时新增 `AppConfig.lastGoodGateway`：**登录成功后记住该区域能用的网关，下轮优先试它**（少打几次网关、更快更省电）。
- versionCode 7 / versionName 1.0.1。**交付**：`dist/xjtu-stu-login-v1.0.1-release.apk`（2342583 B，sha256 `861841b5ea2c226b1f97002d…`）。
- ⏳ 待真机复核：食堂现场 ① 手动加 `10.6.8.2`（或 27.2）到网关列表看是否 `登录成功`；② 装 v1.0.1 后自动兜底是否生效、日志里是否出现新的 host。

## 2026-10-12（七）—— v1.0.2：食堂失败的真因（认证 IP 取错）+ 更正 v1.0.1 的判断

> 用户追问：「bitsrun 用配置 21（= `10.6.21.2`）在食堂就直接成功，那怎么会是 AC 选错？」—— 追问成立，v1.0.1 的结论只对了一半。

- **真因**：上游 `pkg/srun/srun.go:65` 的 `LoginStatus()` 返回 **`res["client_ip"]`**，`internal/login/login.go` 直接把它当认证 IP（`clientIp = *ip` → `loginIp = clientIp`）；只有响应缺该字段时才退到门户页面嗅探（`DetectIp()` 正则 `ip\s*:\s*"(.+)"`）。
  **即：bitsrun 的认证 IP 来自「网关自己报的地址」，不是手机网卡的 IP。**
  我们的实现一直把本机 WiFi IP（`LinkProperties`）当认证 IP 发出去 —— 多数区域两者相同（所以一直能用），**食堂那片两者不一致 ⇒ 网关照着自己认的 IP 找不到该地址 ⇒ `login_error`**。
  （同一族问题的另一面：v0.2.0 日志里那条 `not an object: <script>top.self.location.href=…`，正是上游 `Detector._FollowRedirect` 专门处理的「JS 跳转页」，我们既不跟随也不解析。）
- **修法（v1.0.2）**：`attemptLogin()` 改为 `authIp = 网关上报的 client_ip ?: 本机 IP`，并用它做 `get_challenge`/`srun_portal` 的 `ip`；两者不一致时在日志里显式打出 `(认证IP=x ≠ 本机 y)`，以后一眼可辨。
- **v1.0.1 的更正**：补全 4 个 srun 端点仍然保留（无害兜底、且对真·跨区域有帮助），但**它不是食堂问题的钥匙**；当时的「AC 选错」判断是把「认证 IP 不匹配」误读成了「网关不匹配」。唯一还能证明 AC 名单价值的场景是：某区域的 AC 完全不在那 4 个里（本机扫描所见范围内不存在）。
- versionCode 8 / versionName 1.0.2。**交付**：`dist/xjtu-stu-login-v1.0.2-release.apk`（sha256 `ef1d359d98ed75cf33b56bf86b329063952f61b960e7ce6abb98513760ec3eb6`），已装机（release→release 覆盖，配置保留）。

## 2026-10-12（八）—— v1.0.3：面向公开发布整理

- **仓库对外整理**：README 重写为对外版本（简介 / 特性 / 下载安装 / FAQ / 协议要点 / 构建 / 隐私 / 免责 / 许可）；
  CHANGELOG 与 DESIGN 中的内网痕迹（主机名、内网 IP、个人绝对路径、自建服务地址、覆盖网品牌）全部泛化；
  `dist/` 只保留最新 release APK（旧版含跨区域缺陷，不再随仓库分发）。
- **文案泛化**：VPN 相关提示不再点名具体产品（面向任意 VPN 客户端）；代码注释同步。
- **认证 IP 修正的延续**：沿用 v1.0.2 的 `client_ip` 取值逻辑（食堂 login_error 的真因）。
- versionCode 9 / versionName 1.0.3。

## 2026-10-13 —— v1.1.0：引擎独立重写 + 整仓改为 GPL-3.0

- **背景**：v1.0.x 的 `engine/` 是对 BitSrunLoginGo（AGPL-3.0）的移植，NOTICE 亦如实声明为衍生作品
  ⇒ 整仓受 AGPL 约束（网络条款对"服务端跑"的场景有传染性）。
- **本轮**：`engine/` 三个文件按**公开协议规范**（新增 `docs/PROTOCOL.md`：端点、参数表、XEncode 规范、
  变体 base64 字母表、chksum 拼接、认证 IP 规则、错误码）与 **MIT 许可的参考实现**（BIT-srun-login-script，
  © 2020 coffeehat，全文归档在 `licenses/`）**独立重写**：
  - **对外 API 完全冻结** ⇒ `app/` 与测试零改动；行为由现有 **10 条测试**（12 条逐字节 golden 向量 +
    mock 网关全链路 7 例）验收；
  - 顺手删掉仅为"逐行对齐上游"而存在的 `xDecode`（协议路径不使用，且上游实现本身不可逆）；
  - 删除 AGPL 血缘的工具 `tools/vector-gen`（`golden.json` 转为冻结夹具）；
  - `MiniJson`、`goQueryEscape` 为本项目原创，原样保留。
- **许可**：整仓改为 **GPL-3.0**（LICENSE 换为 GPLv3 全文），NOTICE 重写为「MIT 参考实现 + 公开规范」，
  MIT 全文归档 `licenses/MIT-BIT-srun-login-script.txt`。
- **诚实声明**：重写者此前阅读过 AGPL 上游源码，因此本轮属于**换血统 + 重写表达**，
  并非教科书式 clean-room（后者要求实现者从未接触上游代码）。若需要最强证据链，
  建议由未接触过上游实现者照 `docs/PROTOCOL.md` 再实现一版（现有 10 条测试即验收标准）。
- versionCode 10 / versionName 1.1.0。**交付**：`dist/xjtu-stu-login-v1.1.0-release.apk`
  （sha256 `47adee7226cae08f2f149fd17732aa697df83c9de8e3d5ec6e991f1772af788e`，release 签名 `CN=xjtu-stu-login`）；已推送 tag `v1.1.0`。

## 2026-10-13（二）—— v2.0.0：界面与代码整体润色 + 首发 GitHub

> 面向公开发布的 2.0：界面按「一眼看懂状态」重做，代码做了一轮润色（去死代码、消警告、降耦合）。

**界面**
- 状态卡改为**渐变 Hero**（主色容器 → 面板色，24dp 圆角），左侧 **64dp 状态徽章**：在线绿勾 / 失败红叹 / 跳过黄叹 / 未启动灰；
  状态标题升为 `HeadlineMedium`，标题栏加副标题（西交深澜 · 前台服务自动登录）；
- 新增**网络胶囊**（`XJTU_STU · 10.172.155.57`）：状态改为**结构化保存** SSID/IP（`ServiceStatus` 新增字段并持久化），界面不再解析 detail 文本；
- 状态切换时徽章做一次轻微弹入动画；「启动保活」改用闪电图标。

**代码润色**
- 所有 Kotlin 源文件补 `SPDX-License-Identifier: GPL-3.0-only`；
- 设置页保存改为遍历**行绑定对象**（去掉按 `R.id` 从视图树 `findViewById` 的耦合，删除相关重复代码）；
- 删除死代码（`NetInfo.ssidText`、`StatusStore.beat`）；消除 `allNetworks` / `connectionInfo` 的 deprecation 警告并注明为何仍需使用。

- versionCode 20 / versionName **2.0.0**。**交付**：`dist/xjtu-stu-login-v2.0.0-release.apk`（sha256 `ee840a011d17935e2f7265f7070f206302284a3c5080bf3cdc7014e66362c03d`，release 签名）。
- **仓库历史清洗**：`git filter-repo --replace-text` 将旧提交中的主机名、内网 IP、个人绝对路径等内网痕迹一并泛化
  （当前树此前已清洗，本次覆盖历史）；清洗后校验：`build-host`/`phone`/`192.168.*`/`/home/ubuntu`/旧口令/真实学号 命中数均为 0。
- **首发 GitHub（公开仓）**：仓库地址与 Release 见 README 顶部徽章。
