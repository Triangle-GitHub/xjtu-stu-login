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

data class AppConfig(
    val gateways: List<Gateway> = listOf(Gateway("10.6.18.2"), Gateway("10.6.21.2")),
    val accounts: List<Account> = emptyList(),
    val pollIntervalSec: Int = 60,
    val timeoutMs: Int = 5000,
    val targetSsids: List<String> = listOf("XJTU_STU"),
    val roomSsids: List<String> = emptyList(),
    val autoStart: Boolean = true,
    val serviceEnabled: Boolean = false,
    /** 首启三步引导被手动关掉 */
    val onboardingDismissed: Boolean = false,
    /** 设置页显示高级项（默认简化模式） */
    val showAdvanced: Boolean = false,
    val metaOs: String = "Windows 2000",
    val metaName: String = "losedows",
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
            AppConfig(
                gateways = gws, accounts = accs,
                pollIntervalSec = o.optInt("pollIntervalSec", 60),
                timeoutMs = o.optInt("timeoutMs", 5000),
                targetSsids = o.optString("targetSsids", "XJTU_STU").split(",").map { it.trim() }.filter { it.isNotEmpty() },
                roomSsids = o.optString("roomSsids", "").split(",").map { it.trim() }.filter { it.isNotEmpty() },
                autoStart = o.optBoolean("autoStart", true),
                serviceEnabled = o.optBoolean("serviceEnabled", false),
                onboardingDismissed = o.optBoolean("onboardingDismissed", false),
                showAdvanced = o.optBoolean("showAdvanced", false),
                metaOs = o.optString("metaOs", "Windows 2000"),
                metaName = o.optString("metaName", "losedows"),
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
        o.put("gateways", gws).put("accounts", accs)
            .put("pollIntervalSec", cfg.pollIntervalSec).put("timeoutMs", cfg.timeoutMs)
            .put("targetSsids", cfg.targetSsids.joinToString(","))
            .put("roomSsids", cfg.roomSsids.joinToString(","))
            .put("autoStart", cfg.autoStart).put("serviceEnabled", cfg.serviceEnabled)
            .put("onboardingDismissed", cfg.onboardingDismissed).put("showAdvanced", cfg.showAdvanced)
            .put("metaOs", cfg.metaOs).put("metaName", cfg.metaName)
        try {
            prefs(context).edit().putString(KEY_JSON, o.toString()).apply()
        } catch (_: Throwable) {
        }
    }
}
