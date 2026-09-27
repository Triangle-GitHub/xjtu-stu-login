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
    val userType: String = "",
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
    val metaOs: String = "Windows 2000",
    val metaName: String = "losedows",
)

object AppStore {
    private const val PREF = "xjtu_cfg"
    private const val KEY_JSON = "cfg_json"

    private fun prefs(context: Context): SharedPreferences = try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, PREF, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    fun load(context: Context): AppConfig {
        val raw = prefs(context).getString(KEY_JSON, null) ?: return AppConfig()
        return try {
            val o = JSONObject(raw)
            val gws = (o.optJSONArray("gateways") ?: JSONArray()).let { arr ->
                (0 until arr.length()).map { i ->
                    val g = arr.getJSONObject(i)
                    Gateway(g.optString("domain"), g.optBoolean("enabled", true), g.optString("acid", "1"), g.optString("n", "200"), g.optString("type", "1"))
                }
            }
            val accs = (o.optJSONArray("accounts") ?: JSONArray()).let { arr ->
                (0 until arr.length()).map { i ->
                    val a = arr.getJSONObject(i)
                    Account(a.optString("username"), a.optString("password"), a.optString("userType"), a.optBoolean("enabled", true))
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
                metaOs = o.optString("metaOs", "Windows 2000"),
                metaName = o.optString("metaName", "losedows"),
            )
        } catch (_: Exception) {
            AppConfig()
        }
    }

    fun save(context: Context, cfg: AppConfig) {
        val o = JSONObject()
        val gws = JSONArray()
        cfg.gateways.forEach {
            gws.put(JSONObject().put("domain", it.domain).put("enabled", it.enabled).put("acid", it.acid).put("n", it.n).put("type", it.type))
        }
        val accs = JSONArray()
        cfg.accounts.forEach {
            accs.put(JSONObject().put("username", it.username).put("password", it.password).put("userType", it.userType).put("enabled", it.enabled))
        }
        o.put("gateways", gws).put("accounts", accs)
            .put("pollIntervalSec", cfg.pollIntervalSec).put("timeoutMs", cfg.timeoutMs)
            .put("targetSsids", cfg.targetSsids.joinToString(","))
            .put("roomSsids", cfg.roomSsids.joinToString(","))
            .put("autoStart", cfg.autoStart).put("serviceEnabled", cfg.serviceEnabled)
            .put("metaOs", cfg.metaOs).put("metaName", cfg.metaName)
        prefs(context).edit().putString(KEY_JSON, o.toString()).apply()
    }
}

/** 前台服务 / MainActivity 共享的实时状态（进程内） */
object LoginStatusHolder {
    @Volatile var summary: String = "未启动"
    @Volatile var detail: String = ""
    @Volatile var log: String = ""
    @Volatile var lastAt: Long = 0

    fun update(sum: String, det: String, lg: String) {
        summary = sum
        detail = det
        log = lg.takeLast(1200)
        lastAt = System.currentTimeMillis()
    }
}