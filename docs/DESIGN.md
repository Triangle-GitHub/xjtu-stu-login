# xjtu-stu-login — 设计文档（v0.2）

> 状态：**M0 协议引擎 ✅ 已实现（单测全绿）· M1 App 壳 ✅ 已实现（APK 已构建并装入真机）· M2 真机联调 ⏳ 待用户实测**
> 
> ⚠️ 本文档 v0.2 为「设计」正文（决策/架构/协议细节仍有效）；v0.3（2026-09-27）标记实现状态，
> 实测结果与发现见文末「§9 实现纪要（2026-09-27）」。
> 目标：为**安卓手机**构建校园网深澜（Srun）自动登录 App，**剥离第三方 bitsrun（BitSrunLoginGo）**，
> 协议引擎 Kotlin 自研（仅一条固定路径），离线可验证，真机联调压到里程碑末尾。

---

## 0. 结论摘要

| 项 | 决策 |
|----|------|
| 平台 | **仅 Android 手机**（单 APK），**不依赖 Termux**（无 adb / 无 termux-api / 无 bash 脚本） |
| 技术栈 | **Kotlin**——引擎为纯 JVM 模块（不碰 Android API，可本地跑单测）；App 壳为 Android 原生（前台服务 + WiFi 原生 API） |
| 协议范围 | **仅一条固定路径**：`XEncode(srun_bx1)` + `get_challenge` → `srun_portal`(login/logout) + `rad_user_info`(check)；参数锁定自现有配置 `18`/`21` |
| 不做 | DDNS、guardian、reality、auto-enc/acid、多网卡、custom_header、backoff 全量——BitSrunLoginGo 里全砍 |
| 构建 | 本机 `/opt/android-sdk`（build-tools 34/36/37 + platforms 34/37）+ Gradle wrapper 本地构建 APK |
| 验证 | ① JVM 单测：XEncode golden 向量（对照 amd64 BitSrunLoginGo 生成）+ mock 网关 ② 真机联调（里程碑末尾） |

---

## 1. 背景与现状

### 1.1 被替代对象（取回材料，`ref-phone/`）

| 材料 | 说明 |
|------|------|
| `keep_srun_v2.6.sh` | 手机现有保活状态机（三态：disconnected/connected/room；调 `./bitsrun --config N`；termux-api + adb dumpsys 探测 WiFi） |
| `bitsrun` | **Mmx233/BitSrunLoginGo** 编译的 aarch64 ELF（strings 证实），AGPL-3.0 |
| `18` / `21` | 网关域 `10.6.18.2` / `10.6.21.2`，`enc: srun_bx1`，`acid: 1`，`type: 1`，`n: 200`，`info_prefix: SRBX1` —— **坐标锁定** |

J1900 侧参考：`campus-login.sh`（老式明文 auth_action 路线 + 双账号 + systemd timer）——**与本 App 并行存在，本轮不动**。

### 1.2 为什么是「仅一条路径」

BitSrunLoginGo 功能全景中，App 只移植 `pkg/srun` 子集：

| 模块 | 取舍 | 理由 |
|------|------|------|
| `XBase64.go` + `XEncode.go` | ✅ 自研复刻 | 加密核心，全部工作量所在，`enc: srun_bx1` 固定 |
| `api.go`（get_challenge / srun_portal / rad_user_info） | ✅ 移植 | 登录/登出/状态查询三 API，参数组装逐字对照 |
| `errors.go` | ✅ 移植 | `E0000`/`E2553`/`IP has been online` 等映射 |
| `detect.go`（网卡 IP 探测） | ❌ | Android 从 `WifiManager` 拿当前 WiFi IP |
| `internal/login/guardian.go` | ❌ | App 前台服务自实现常驻 |
| `internal/dns/*`（DDNS） | ❌ | 无此需求 |
| `auto-enc` / `auto-acid` / 多网卡 / `custom_header` / `backoff` / `reality` | ❌ | 参数已锁定，不需要探测与扩展 |

---

## 2. 架构

```
单 APK：xjtu-stu-login.apk
├── engine（纯 Kotlin/JVM 模块，零 Android 依赖 —→ 本地可跑单测）
│   ├── XBase64.kt        # 深澜变体 base64（对照 Go 版逐字移植）
│   ├── XEncode.kt        # XEncode(str, key)（MD5 派生密钥 + 异或 + 奇偶置换 + 变体 b64）
│   ├── SrunClient.kt     # get_challenge / srun_portal(login|logout) / rad_user_info
│   ├── LoginForm.kt      # 参数模型（domain/username/password/meta：n,type,acid,enc,os,name,info_prefix,double_stack）
│   └── SrunErrors.kt     # 错误码 → 结构化结果
├── app（Android 壳）
│   ├── LoginService.kt   # 前台服务（常驻通知），周期任务：check → 需登录则 login → 续期
│   ├── WifiManager.kt    # 原生 WiFi：连接 XJTU_STU / 探测宿舍网 SSID（替代 adb+termux-api！）
│   ├── SettingsActivity  # 配置页：账号/密码/网关域/SSID/续期间隔/开机自启
│   ├── StatusActivity    # 状态页：在线/续期计数/最近日志
│   └── Store.kt          # EncryptedSharedPreferences（凭据 + 配置）
└── tests（JVM）
    ├── XEncodeGoldenTest.kt   # golden 向量全量比对（离线）
    └── SrunClientMockTest.kt  # 本地 mock 网关（HTTPServer）：challenge/portal/rad_user_info
```

### 2.1 引擎与壳的边界

- `engine` 不 import 任何 `android.*` → 用 `kotlinc` 编译成普通 JAR 即可在 构建机 跑 `XEncodeGoldenTest`
- `app` 依赖 `engine`，只做：调用引擎 + 调度 + WiFi + UI + 存储
- 好处：**协议正确性在写 UI 之前就已离线锁定**

### 2.2 登录流程（与位次对照）

1. IP 获取：`WifiManager` 当前 WiFi IP（或备份：`http://10.6.x.2` 网关自身检测，同现有配置的空 IP 语义）
2. `GET {domain}/cgi-bin/get_challenge?callback=jQuery…&username=<u>&ip=<ip>` → 解析 `token`
3. `info = "{SRBX1}" + XEncode(b64{type=1&n=200&acid=1&os=…&name=…&ip=…}, token)`；`password = "{SRBX1}" + XEncode(pwd, token)`
4. `GET {domain}/cgi-bin/srun_portal?action=login&ac_id=1&…&password=…&info=…` → `login_ok` / 错误码
5. 在线判定：`GET {domain}/cgi-bin/rad_user_info` → 已认证则跳过登录

---

## 3. 常驻与保活（替代 shell 状态机）

三态语义自 `keep_srun_v2.6.sh` 平移，但**脱离 Termux/adb 后全部用原生机制**：

| 项 | v2.6（Termux） | App（新） |
|----|----------------|-----------|
| WiFi 探测 | termux-api（`cmd wifi`/`dumpsys`，高耗电 + 需刷新节流） | `WifiManager` 原生（扫描/连接/状态回调，无耗电坑） |
| 宿舍网优先 | room 态：检出宿舍 SSID 即切走，不占校园网账号 | 同语义：周期任务先查宿舍 SSID → 有则挂起 |
| 续期 | 5s 无条件 | **默认 30–60s 可配**；失败即重连 |
| 在线判定 | 看 WiFi 猜 | `rad_user_info` 网关侧判定 |
| 常驻 | 前台脚本（EMUI 可杀，需手动重开） | **前台服务 + 常驻通知**；引导用户加「自启动/忽略电池优化」白名单（华为 EMUI 必要步骤，App 内首次引导） |
| 启动 | 手动开 Termux | 开机自启（BOOT_COMPLETED 的 `START_STICKY`）+ 应用内手动开关 |

调度：前台服务内 `Handler`/协程定时循环即可（不做 WorkManager——登录需要立即性，保活本身由前台服务承担）。

### 3.1 前台服务的诚实性

- 常驻通知文案即登录状态（`已连接 · 续期 12 次 · 在线 3h` 等），把"常驻"变成用户可见价值；
- 通知渠道 + 前台服务类型 `specialUse`（Android 14+ 需声明），媒体/数据同步类目按 targetSdk 适配。

---

## 4. 配置与凭据

| 项 | 方案 |
|----|------|
| 账号/密码 | **EncryptedSharedPreferences**（Keystore 主密钥）——凭据只进系统安全存储，不再有 `18`/`21` 明文 YAML |
| 网关坐标 | 内置默认 `10.6.18.2` / `10.6.21.2`（双网关主备下拉）+ 允许自填 |
| 策略参数 | SSID、宿舍 SSID 列表、续期间隔、是否开机自启、（可选）双账号 |
| 迁移 | 现有 `ref-phone/18`、`21` 的值已锁定为默认值来源；**明文 YAML 不进 App、不进 git（已 .gitignore）** |

---

## 5. 验证策略（无生产实机的条件）

| 层 | 方法 | 时机 |
|----|------|------|
| XEncode 正确性 | **离线 golden 向量**：下载 `BitSrunLoginGo` linux/amd64 release → 本机运行生成 `(key,str)→输出` 向量表 → Kotlin/XEncode 逐条断言全绿 | 开发当期（M0） |
| 协议流程 | 本地 mock 网关：JVM `HTTPServer` 模拟 get_challenge/srun_portal/rad_user_info 三端点，跑通 login/logout/check 全流程 | 开发当期（M0） |
| 网关真实响应形态 | （可选）用户做**只读快照**：手机连校园网时 GET `get_challenge` + `rad_user_info`（不登录，零副作用）→ 返回结构固化为 mock 样本 | 设计确认后、M0 前（可选） |
| App 壳 | 本机构建 APK + `assembleDebug` 通过；模拟器/仪器测试可选 | M1 |
| 真机联调 | 用户手机上装 APK，手动触发一次登录，看 `login_ok`；之后才授权自动续期 | **M2（最后）** |

> 原则：**所有能在 构建机 离线验证的都离线验证**；真机只做最后一件事——确认"网关真的是这么应答的"。

---

## 6. 里程碑

| 里程碑 | 内容 | 完成标准 |
|--------|------|----------|
| **M0 引擎** ✅（2026-09-27 完成） | `XEncode.kt` + `XBase64.kt` + `SrunClient.kt` + JVM 单测 | golden 12 条向量全过 + mock 网关 login/logout/check 通过（`./gradlew :engine:test` 绿，8 项测试） |
| **M1 App 壳** ✅（2026-09-27 完成） | UI（设置+状态）+ 前台服务 + WiFi 原生 + 存储 + 开机自启 | 本机 `assembleDebug` 出 APK（6.6 MB）；已 via adb 装入真机（HUAWEI ALN-AL00 / Android 12），未启动 |
| **M2 真机** ⏳ 待用户 | 用户打开 App → 填账号 → 手动登录验证 → 打开自动保活 | 用户实测通过（login_ok / 断网自愈）；测试指引见 README「真机联调」 |

M0 与 M1 可并行推进（引擎纯 JVM，不依赖壳）。

---

## 7. 构建环境（构建机 可用性实测）

- Java：构建必须显式 `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`。系统默认 `java` 已是 25.0.4.1 —— Gradle 9.x/AGP 8.7.3 **不支持**，直接拿它跑必失败；17/21/25 均在 `/usr/lib/jvm/` 下。
- Gradle：仓库内 wrapper 已入库（钉 `9.6.1`）。`GRADLE_USER_HOME` = `~/.gradle` → `本地 Gradle 缓存`（符号链接），其 `wrapper/dists` 已解包 9.6.1 与 8.9 ⇒ **首次运行也无需下载**；8.9 留作 AGP 8.7.3 的兼容退路（换 `distributionUrl` 即可）。全局 gradle 已不可用：原 `旧版全局 Gradle` 于 2026-10-11 被系统清理。
- AGP/Kotlin 插件：Gradle 构建时解析（`~/.gradle/init.d/mirrors.gradle` 会自动前置 aliyun 镜像；本机代理 `http://127.0.0.1:7893` 仍在监听，可兜底）。
- 目标 SDK：`compileSdk 34` + `minSdk 26`（Android 8.0，覆盖前台服务通知要求；WiFi API 按实际机型用 `WifiNetworkSpecifier` 适配分支）

---

## 8. 开放问题（待用户拍板）

1. **UI 深度**：最小可用（设置页+状态页两个页面）即可，还是要做精致的深色主题/图表？（建议先最小可用）
2. **双网关主备**：默认 `18`→`21` 主备尝试顺序是否沿用？（建议沿用）
3. **只读快照实测**：是否在做 M0 前做一次 `get_challenge`+`rad_user_info` 只读快照（可选，非必须）？
4. **多账号**（J1900 双账号切换）本轮不做，确认？
5. **分发**：APK 如何交付？（本地 `adb install` / 走现有 pi-web-ui 客户端 / 其他）

---

## 9. 实现纪要（2026-09-27）

### 9.1 引擎（M0）

- Kotlin 核心：`engine/src/main/kotlin/org/xjtulogin/core/`（XEncode/SrunB64/SrunCrypto/SrunClient/MiniJson/LoginForm）——纯 JVM，零 Android 依赖。
- **8 项 JVM 单测全绿**（`XEncodeGoldenTest` 3 + `SrunClientMockTest` 5；2026-10-11 更正：旧文「9 项」为笔误）：
  1. goldenVectorsMatch / emptyStringEdge：XEncode 12 条向量（含中文/空串/特殊字符/长文本/短 key）与 Go 蓝本生成的 `golden.json` 逐字节对齐，并单测空串入口→空串出口；
  2. checksumGoldenCase：`{MD5}+hmac-md5`、`{SRBX1}+XEncode(info)`、`sha1(%x\n)` 全链路与 golden 一致；
  3. mock 网关（com.sun.net.httpserver）5 场景：登录成功 / 提交参数形态（password={MD5}、info={SRBX1}、chksum 尾换行且重算一致）/ rad_user_info 在线判定 / 登出 / 错误透传（Rejected）。
- golden 生成器：`tools/vector-gen/`（Go，vendored 自 BitSrunLoginGo 的 XEncode/XBase64 + 修正 pad），产物 `engine/src/test/resources/golden.json`（虚构测试串，无真实凭据）。重跑：`cd tools/vector-gen && PATH=/usr/local/go/bin:$PATH go run . -out ../../engine/src/test/resources/golden.json`（Go 1.24.5 装在 `/usr/local/go/bin`，**不在默认 PATH**；`-out` 省略时写当前目录下的 `engine/src/test/resources/golden.json`）。

### 9.2 上游发现（已回报 issue #64）

- **Bug A**：XEncode 密钥补零循环 off-by-one（`for i := 0; i < (4-len(pwdk)); i++` 条件每次迭代重算 → pwdk 停在 3 组，短 token<16 字节时 `pwdk[3]` panic）。XJTU token ≥16 字节不触发 → 潜伏。Kotlin 侧按意图补满 4 并注明。
- **Bug B**：XDecode 不可逆（Go 原样跑 `XDecode(XEncode(x))` 返回空）。协议只用 XEncode，Kotlin 侧保持与上游一致（对齐、不修）。
- 已提交 https://github.com/Mmx233/BitSrunLoginGo/issues/64（英文，无隐私信息）。

### 9.3 App 壳（M1）

- `app/`：Material 3（Theme.Material3.DayNight）+ 传统 View；设置页网关/账号动态增删+逐条勾选；参数全可配（轮询间隔/超时/目标 SSID/宿舍 SSID/os/name/开机自启）。
- 前台服务 `LoginService`（specialUse FGS + 常驻通知文案即状态）；宿舍网优先语义继承 keep_srun v2.6；`rad_user_info` 判在线（§4.3）。
- 凭据 EncryptedSharedPreferences（Keystore；异常回退普通 prefs —— 首版取舍）。
- **已知边界（与设计差异）**：WiFi 自动连接未做（仅“当前 SSID 命中目标列表才保活”），避免系统弹框；如需自动切网，下一版用 WifiNetworkSpecifier + 引导。
- 构建环境（构建机 实测）：JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64、ANDROID_HOME=/opt/android-sdk、Gradle 9.6.1（仓库内 wrapper，已验证 `:engine:test` + `:app:assembleDebug`；AGP 8.7.3 + Kotlin 2.0.21 在 9.x 下可用，仅报 Gradle 10 弃用警告；必要时可退回 wrapper 8.9）、compileSdk 34 / minSdk 26。
- 仓库位置（2026-10-11 迁移）：`（仓库根）`（原 `~/xjtu-stu-login`，源目录已删）；原全局 `旧版全局 Gradle` 同时被系统清理 ⇒ 构建一律走仓库内 `./gradlew`。
- 产物：`xjtu-stu-login-v0.1.0.apk`（6580814 B ≈ 6.6 MB，debug 签名）——已 ssh 真机(adb over ssh) → Termux adb 静默安装至真机（HUAWEI ALN-AL00 / Android 12），**未启动**。（2026-10-11 复核：`ssh 真机(adb over ssh)` = 192.168.5.x:8022 不通——手机不在同网段 ⇒ 本次未能复核装机状态）

### 9.4 待办（M2 之后）

- 真机联调结果回填本节；失败按错误码定位（mock 已覆盖 login_ok / E2531 类透传）。
- 可选增强：WiFi 自动连接、双账号（J1900 语义）、release 签名与分发渠道。
