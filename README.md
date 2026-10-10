# xjtu-stu-login

西安交大校园网（深澜 Srun）自动登录 **Android App**，协议自研（Kotlin），**不依赖第三方 bitsrun / BitSrunLoginGo**。

## 当前状态（2026-09-27；2026-10-11 迁移更新）

- ✅ **M0 引擎**：`engine/` Kotlin 协议引擎（XEncode + Challenge 登录 + 状态查询），
  JVM 单测全绿：12 条 XEncode golden 向量与 BitSrunLoginGo 蓝本逐字节对齐 + mock 网关全流程。
- ✅ **M1 App 壳**：`app/` Material 3 原生 App，构建产物 `xjtu-stu-login-v0.1.0.apk`。
- ⏳ **M2 真机联调**：待装到手机实测（登录一次确认 `login_ok`，再开自动保活）。
- ✅ **仓库迁移 + 构建入口自治**（2026-10-11）：仓库落在 `.`；gradle wrapper 入库（钉 `9.6.1`）⇒ `./gradlew` 自洽；重建 APK 与交付件 sha256 一致（`a63e1ad0…`）。

## 功能（全参数可配）

- **网关列表**：任意数量，逐条勾选启用；每条可配 domain / acid（默认 `10.6.18.2` + `10.6.21.2` 主备示例）
- **账号列表**：任意数量，逐条勾选启用；学号 + 密码（可加运营商后缀）
- **参数**：轮询间隔、请求超时、目标校园网 SSID 列表（逗号分隔，留空=任意）、
  宿舍网 SSID 列表（检测到则跳过，不占校园网账号）、os/name 提交参数、开机自启
- **保活形态**：前台服务 + 常驻通知（通知文案即登录状态）；宿舍网优先语义源自 keep_srun v2.6
- **凭据**：EncryptedSharedPreferences（Keystore），无明文落盘

## 构建（build-host）

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # 默认 java 已是 25；Gradle 9.x/AGP 8.7.3 不支持 ⇒ 必须显式指定
export ANDROID_HOME=/opt/android-sdk
./gradlew :engine:test         # 协议引擎单测（离线 golden + mock 网关）
./gradlew :app:assembleDebug   # 打 APK
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> Gradle 由仓库内 wrapper 自带（钉 `9.6.1`，本机 wrapper 缓存已解包 ⇒ 离线可用）。已实测
> `:engine:test` + `:app:assembleDebug` 通过，APK 与交付件 `xjtu-stu-login-v0.1.0.apk` 逐字节一致；
> 若将来遇到 AGP/Kotlin 与 9.x 的兼容问题，可把 `gradle-wrapper.properties` 退回 `gradle-8.9-bin.zip`（本机亦已缓存）。
> 2026-10-11 起仓库位于 `.`（原 `~/xjtu-stu-login`，已删）；
> 原全局 Gradle `全局 Gradle` 已被清理，不要再引用。

## 目录

```
engine/    # Kotlin 协议引擎（纯 JVM，零 Android 依赖，可离线单测）
app/       # Android App 壳（MD3 UI + 前台服务 + 原生 WiFi + 安全存储）
tools/     # 构建工具：vector-gen（Go 生成 golden 向量；需 PATH=/usr/local/go/bin:$PATH）
ref-phone/ # 手机 Termux 取回参考（bitsrun aarch64 二进制 + 配置 + keep_srun v2.6）
ref-src/   # BitSrunLoginGo pkg/srun 源码蓝本
docs/      # DESIGN.md（设计文档）
gradle/    # Gradle wrapper（钉 9.6.1）；gradlew 是唯一构建入口，不依赖全局 gradle
```

## ⚠️ 凭据

`ref-phone/18`、`ref-phone/21` 含明文账号密码 —— `.gitignore` 已排除，禁止提交。

## 真机联调（M2，用户执行）

APK 已装（未打开）：包 `org.xjtulogin.app`，华为 ALN-AL00 / Android 12。（2026-10-11 复核：`ssh phone` = 192.168.5.x:8022 不通——手机不在同网段，装机状态本次未复核）

1. 打开 App → 授予通知 / 附近设备权限（首次启动弹窗）。
2. 设置 → 填学号密码（可多条，勾选启用）、确认网关勾选（默认 10.6.18.2 / 10.6.21.2 主备）→ 保存。
3. 确保手机已连校园网 WiFi（默认目标 SSID `XJTU_STU`；设置里可改/留空=任意）。
4. 回主页点「立即检测」：预期状态变 **已在线 · 登录成功 ✓**。
5. 点「启动保活」进入前台轮询（默认 60s）；宿舍 SSID 填了的话，连宿舍网时自动跳过。

**如果失败**：记下状态页显示的网关错误（如 `拒绝: E2531: ...`）或超时现象；`rad_user_info` 与错误透传已在 mock 中覆盖，联调结果按错误码可快速定位。
