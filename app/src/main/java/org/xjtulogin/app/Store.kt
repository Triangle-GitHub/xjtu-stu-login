// SPDX-License-Identifier: GPL-3.0-only
package org.xjtulogin.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

data class Gateway(
    val domain: String = "",
    val enabled: Boolean = true,
    val acid: String = "1",
    val n: String = "200",
    val type: String = "1",
)

data class Account(
    val username: String = "",
    val password: String = "",
    val userType: String = "stu",
    val enabled: Boolean = true,
)

/** 宿舍网（原脚本 `ROOM_WIFI_SSIDS` / `ROOM_WIFI_PASSWORDS`）：命中即切换到「宿舍模式」，不占用校园网账号 */
data class RoomWifi(
    val ssid: String = "",
    val password: String = "",
)

data class AppConfig(
    val gateways: List<Gateway> = listOf(Gateway("10.6.18.2"), Gateway("10.6.21.2")),
    val accounts: List<Account> = emptyList(),

    // ---- 原脚本时序（keep_srun_v2.6.sh）----
    /** 进入「校园网状态」后的续期间隔 = 原脚本 `sleep 5` */
    val renewIntervalSec: Int = 5,
    /** 单次登录/续期的整体预算 = 原脚本 `run_with_timeout 10 ./bitsrun` */
    val loginTimeoutSec: Int = 10,
    /** 「未连接状态」里 t 的初值 = 原脚本 `t=5` */
    val baseLoopSec: Int = 5,
    /** 原脚本 `ENABLE_WIFI_SCAN`（默认 0） */
    val wifiScan: Boolean = false,
    /** 原脚本 `SCAN_INTERVAL` */
    val scanIntervalSec: Int = 30,
    /** 原脚本 `ENABLE_BATTERY_WARNING` */
    val batteryWarning: Boolean = true,

    // ---- 本 App 参数（原脚本没有的）----
    val pollIntervalSec: Int = 60,
    val timeoutMs: Int = 5000,
    val targetSsids: List<String> = listOf("XJTU_STU"),
    /** 宿舍网列表（SSID + 口令）；口令留空 = 开放网络 */
    val rooms: List<RoomWifi> = emptyList(),
    val autoStart: Boolean = true,
    val serviceEnabled: Boolean = false,
    /** 首启三步引导被手动关掉 */
    val onboardingDismissed: Boolean = false,
    /** 设置页显示高级项（默认简化模式） */
    val showAdvanced: Boolean = false,
    /** 上次登录成功的网关（下次优先试它，少打几次网关） */
    val lastGoodGateway: String = "",
    val metaOs: String = "Windows 2000",
    val metaName: String = "losedows",

    // ---- WiFi 自修复 / Shizuku ----
    /** 用 Shizuku 取得 ADB shell 身份来跑 `cmd wifi …`（原脚本靠 adb 做的那几条） */
    val useShizuku: Boolean = false,
    /** 开启保活时若 WiFi 关着，自动打开一次 */
    val autoEnableWifi: Boolean = true,

    /** 老版本字段（只读，用于迁移到 [rooms]） */
    val legacyRoomSsids: List<String> = emptyList(),
)

/**
 * 配置存取：EncryptedSharedPreferences（Keystore）落盘 + 进程内缓存。
 *
 * 缓存是必须的：保活线程每轮都要读配置，而 Keystore/加密 Prefs 的构造极慢
 * （旧实现每次 load 都重建 MasterKey + EncryptedSharedPreferences），
 * 既拖慢轮询，也容易在机型上偶发失败。
 */
object AppStore {
    private const val PREF = "xjtu_cfg"
    private const val KEY_JSON = "cfg_json"

    @Volatile private var prefsRef: SharedPreferences? = null
    @Volatile private var cache: AppConfig? = null

    private fun prefs(context: Context): SharedPreferences {
        prefsRef?.let { return it }
        synchronized(this) {
            prefsRef?.let { return it }
            val app = context.applicationContext
            val p = try {
                val key = MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    app, PREF, key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (_: Throwable) {
                // Keystore 异常（设备迁移/被清理）时退回普通 prefs，保证 App 仍可用
                app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            }
            prefsRef = p
            return p
        }
    }

    fun load(context: Context): AppConfig {
        cache?.let { return it }
        val cfg = read(context)
        cache = cfg
        return cfg
    }

    private fun read(context: Context): AppConfig {
        val raw = try {
            prefs(context).getString(KEY_JSON, null)
        } catch (_: Throwable) {
            null
        } ?: return AppConfig()
        return try {
            val o = JSONObject(raw)
            val gws = (o.optJSONArray("gateways") ?: JSONArray()).let { arr ->
                (0 until arr.length()).map { i ->
                    val g = arr.getJSONObject(i)
                    Gateway(
                        g.optString("domain"), g.optBoolean("enabled", true),
                        g.optString("acid", "1"), g.optString("n", "200"), g.optString("type", "1")
                    )
                }
            }
            val accs = (o.optJSONArray("accounts") ?: JSONArray()).let { arr ->
                (0 until arr.length()).map { i ->
                    val a = arr.getJSONObject(i)
                    Account(
                        a.optString("username"), a.optString("password"),
                        a.optString("userType"), a.optBoolean("enabled", true)
                    )
                }
            }
            val legacyRooms = o.optString("roomSsids", "")
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val rooms = (o.optJSONArray("rooms") ?: JSONArray()).let { arr ->
                (0 until arr.length()).map { i ->
                    val r = arr.getJSONObject(i)
                    RoomWifi(r.optString("ssid"), r.optString("password"))
                }
            }.ifEmpty {
                // 老版本只有 SSID 列表（语义=跳过登录，无口令）⇒ 迁成「无口令宿舍网」
                legacyRooms.map { RoomWifi(it, "") }
            }
            AppConfig(
                gateways = gws, accounts = accs,
                renewIntervalSec = o.optInt("renewIntervalSec", 5),
                loginTimeoutSec = o.optInt("loginTimeoutSec", 10),
                baseLoopSec = o.optInt("baseLoopSec", 5),
                wifiScan = o.optBoolean("wifiScan", false),
                scanIntervalSec = o.optInt("scanIntervalSec", 30),
                batteryWarning = o.optBoolean("batteryWarning", true),
                pollIntervalSec = o.optInt("pollIntervalSec", 60),
                timeoutMs = o.optInt("timeoutMs", 5000),
                targetSsids = o.optString("targetSsids", "XJTU_STU").split(",").map { it.trim() }.filter { it.isNotEmpty() },
                rooms = rooms,
                autoStart = o.optBoolean("autoStart", true),
                serviceEnabled = o.optBoolean("serviceEnabled", false),
                onboardingDismissed = o.optBoolean("onboardingDismissed", false),
                showAdvanced = o.optBoolean("showAdvanced", false),
                lastGoodGateway = o.optString("lastGoodGateway", ""),
                metaOs = o.optString("metaOs", "Windows 2000"),
                metaName = o.optString("metaName", "losedows"),
                useShizuku = o.optBoolean("useShizuku", false),
                autoEnableWifi = o.optBoolean("autoEnableWifi", true),
                legacyRoomSsids = legacyRooms,
            )
        } catch (_: Throwable) {
            AppConfig()
        }
    }

    fun save(context: Context, cfg: AppConfig) {
        cache = cfg
        val o = JSONObject()
        val gws = JSONArray()
        cfg.gateways.forEach {
            gws.put(
                JSONObject().put("domain", it.domain).put("enabled", it.enabled)
                    .put("acid", it.acid).put("n", it.n).put("type", it.type)
            )
        }
        val accs = JSONArray()
        cfg.accounts.forEach {
            accs.put(
                JSONObject().put("username", it.username).put("password", it.password)
                    .put("userType", it.userType).put("enabled", it.enabled)
            )
        }
        val rooms = JSONArray()
        cfg.rooms.forEach {
            rooms.put(JSONObject().put("ssid", it.ssid).put("password", it.password))
        }
        o.put("gateways", gws).put("accounts", accs).put("rooms", rooms)
            .put("renewIntervalSec", cfg.renewIntervalSec)
            .put("loginTimeoutSec", cfg.loginTimeoutSec)
            .put("baseLoopSec", cfg.baseLoopSec)
            .put("wifiScan", cfg.wifiScan)
            .put("scanIntervalSec", cfg.scanIntervalSec)
            .put("batteryWarning", cfg.batteryWarning)
            .put("pollIntervalSec", cfg.pollIntervalSec).put("timeoutMs", cfg.timeoutMs)
            .put("targetSsids", cfg.targetSsids.joinToString(","))
            .put("autoStart", cfg.autoStart).put("serviceEnabled", cfg.serviceEnabled)
            .put("onboardingDismissed", cfg.onboardingDismissed).put("showAdvanced", cfg.showAdvanced)
            .put("lastGoodGateway", cfg.lastGoodGateway)
            .put("metaOs", cfg.metaOs).put("metaName", cfg.metaName)
            .put("useShizuku", cfg.useShizuku).put("autoEnableWifi", cfg.autoEnableWifi)
        try {
            prefs(context).edit().putString(KEY_JSON, o.toString()).apply()
        } catch (_: Throwable) {
        }
    }
}
