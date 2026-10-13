# XJTU-STU Auto Login · 校园网保活

西安交大校园网 **XJTU-STU**（深澜 Srun）自动登录 / 保活 Android App。
协议引擎自研（纯 Kotlin、零 Android 依赖、可离线单测），**不依赖任何第三方二进制**。

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

## 它解决什么

手机连上 `XJTU_STU` 后总要手动开浏览器点认证，掉线还得再来一次。这个 App 常驻后台替你做：
**连上就登录，掉线就重连**。

## 特性

- **默认值已按 XJTU-STU 预设**：目标 SSID `XJTU_STU`（忽略大小写、`-` 与 `_` 等价）、账号后缀默认 `@stu`、
  内置校园网 4 个 srun 认证端点 —— 开箱只需填学号 + 密码
- **保活链路完整**：前台服务 + 常驻通知（带「立即检测 / 停止」按钮）、`START_STICKY`、
  网络变化即刻重检、单轮检测持 WakeLock、进程被清理后回前台自动恢复、开机自启
- **开着 VPN 也能用**：检测到 VPN（覆盖网）时，把登录请求的 socket **绑定到 WiFi 网卡**直出、不穿隧道；
  机型不允许绑卡时自动降级（进程绑 WiFi → 默认路由），并把实际策略显示在状态里
- **认证 IP 取网关上报值**（见「协议要点」）—— 同类实现最常踩的坑
- **隐私优先**：凭据仅本机 Keystore 加密存储；无遥测、无上报、无第三方 SDK；日志与通知里学号自动脱敏（`前4位****后2位@stu`）
- **Material 3 界面**（亮/暗）、首启三步引导、简化模式（网关/SSID/轮询等高级项默认收起）

## 下载与安装

- 最新版本：[`dist/xjtu-stu-login-v2.0.0-release.apk`](dist/xjtu-stu-login-v2.0.0-release.apk)（约 2.3 MB，release 签名）
- 要求 Android 8.0+（minSdk 26）；安装时需允许「安装未知来源应用」
- 升级：同一签名可直接覆盖安装，账号配置保留

四步上手：

1. 打开 App，授予**通知**与**定位**权限（定位权限是 Android 读取 WiFi 名称的硬性要求，见「隐私」）
2. 「参数设置」填学号 + 密码（后缀已默认 `stu`）→ 保存
3. 主界面点「启动保活」；顺手点「后台保活白名单」授权（否则系统省电会掐掉前台服务）
4. 连上 `XJTU_STU`，状态卡变绿「已在线」即成功

> 主界面顶部的三步引导卡片会随进度打勾，全绿或手动关闭后隐藏。

## 常见问题

**Q：一直 `login_error`？**
① 账号后缀（西交校园网为 `stu`，App 已默认带成 `学号@stu`）；② 密码错误；③ 该区域由另一台 AC 服务 ——
App 内置了 XJTU 全部 4 个 srun 端点并自动兜底尝试。网关错误码会翻译成人话写进日志（`E2531` 欠费、`E2553` 已在别处在线等）。

**Q：某些区域（如食堂）失败，手动登录的页面也长得不一样？**
那是**认证 IP** 的问题，不是 AC 选错 —— 详见「协议要点」第 2 条。

**Q：显示「未连接 WiFi」或读不到 SSID？**
Android 10+ 把读取 SSID 归入定位权限。拒绝授权时 App 会按「未知网络」继续尝试登录（此时无法识别宿舍网）。

**Q：开着 VPN 会不会失效？**
不会。检测到 VPN 时把请求 socket 绑到 WiFi 网卡（`Network.openConnection`）直连网关；若机型禁止绑卡
（部分华为 ROM 返回 `EPERM`），自动降级为「进程绑 WiFi」→「默认路由」，状态里会写明当前策略（`VPN:已绕过(绑网卡)` 等）。

**Q：会不会被系统杀掉？**
前台服务 + 常驻通知 + 电池优化白名单 + 开机自启四件套。华为/小米等 ROM 还需在系统「应用启动管理」里允许自启动与后台运行。

**Q：宿舍网怎么办？**
宿舍网 SSID 可留空（留空 = 不跳过任何网络）；若填了，检测到该 SSID 就跳过登录，不占用校园网账号。

## 协议要点（给实现者）

1. **登录流程**：`GET /cgi-bin/get_challenge?username=&ip=` 取 `challenge` →
   `info = "{SRBX1}" + B64(XEncode(infoJson))`、`password = "{MD5}" + hmac-md5(challenge, password)`、
   `chksum = sha1(...)`（**末尾带换行**，与 Go 版 `%x\n` 对齐）→ `GET /cgi-bin/srun_portal?action=login&...`；
   在线状态查询 `/cgi-bin/rad_user_info`。
2. **认证 IP 必须用网关在响应里报的 `client_ip`**，而不是本机网卡读到的 IP。两者多数时候相同，
   但在部分区域（实测食堂）不一致 —— 用本机 IP 会直接被判 `login_error`。
   本项目与 BitSrunLoginGo 一致：优先取响应里的 `client_ip`，缺失才退回本机 IP，且**差异会写进日志**。
3. **XJTU 的 srun 端点是有限集合**（实测：`10.6.8.2` 总门户 + `10.6.18.2` / `10.6.21.2` / `10.6.27.2` 各区域 AC），
   不同区域由不同 AC 服务 ⇒ 本项目内置并自动兜底，同时记住上次成功的那个。
4. **明文 HTTP**：校园网关只提供 `http://`，Android 9+ 默认拦截明文 ⇒ 必须用 `network_security_config` 放行
   （本项目已含 —— 这也是「装了却完全无法登录」的经典原因）。

## 构建

```bash
export JAVA_HOME=/path/to/jdk21      # AGP 8.7 / Gradle 9 不支持 JDK 25
export ANDROID_HOME=/path/to/android-sdk

./gradlew :engine:test          # 协议引擎离线单测（XEncode golden 向量 + mock 网关）
./gradlew :app:assembleDebug    # debug 包
./gradlew :app:assembleRelease  # release 包（R8 + 资源压缩；需 keystore.properties）
```

release 签名从仓库根 `keystore.properties` 读取（**该文件与私钥都不入库**）：

```properties
storeFile=/absolute/path/to/your-release.jks
storePassword=***
keyAlias=***
keyPassword=***     # PKCS12 keystore 要求与 storePassword 相同
```

> 私钥务必备份：丢失后无法再对已发布的 App 做覆盖升级（签名不一致，用户必须卸载重装）。
> 未提供 `keystore.properties` 时 release 产物不签名（可构建，不可分发）。

## 隐私

- **无遥测、无上报、无第三方 SDK**：不收集任何使用数据；除校园网网关外不连接任何地址。
- **凭据仅存本机**：学号与密码写入 `EncryptedSharedPreferences`（Android Keystore / AES256），不上传、不导出，卸载即消失。
- **定位权限**：仅用于读取当前 WiFi 名称以判断是否在 `XJTU_STU`，不采集位置。
- **日志脱敏**：界面与通知里学号显示为 `前4位****后2位@stu`（方便你直接把日志贴出来求助）。

## 目录

```
engine/   # Kotlin 协议引擎（纯 JVM，零 Android 依赖，可离线单测）
app/      # Android App（MD3 UI + 前台服务 + 原生 WiFi + Keystore 存储）
docs/     # 设计文档（DESIGN.md / PROTOCOL.md）与上游 bug 回报存档
dist/     # 预编译 release APK
```

## 后续待办

- [ ] **运营商后缀改为下拉选择**（`stu` 预选、可留空）—— 目前仍为手输，输错会得到网关 `login_error`。
- [ ] **QS 磁贴 / 桌面小组件**：一键「立即检测 / 开关保活」，从宿舍切回校园网后不必开 App。

## 免责声明

仅供**你自己账号**的自动登录使用。请遵守学校网络使用规定；本项目不提供任何绕过认证、账号共享或攻击性能力。

## 许可与致谢

- 本项目以 **GPL-3.0** 发布（见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)）。
- 协议引擎（`engine/`）按**公开协议规范**（[docs/PROTOCOL.md](docs/PROTOCOL.md)）与 **MIT 许可的参考实现**
  [BIT-srun-login-script](https://github.com/coffeehat/BIT-srun-login-script)（© 2020 coffeehat，
  全文见 [licenses/](licenses/)）独立编写；同领域另有 BitSrunLoginGo 等实现，仅作协议理解之用。
- UI 依赖 AndroidX / Material Components（Apache-2.0）。
