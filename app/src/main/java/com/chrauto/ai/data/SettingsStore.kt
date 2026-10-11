package com.chrauto.ai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.chrauto.ai.provider.AiConfig
import com.chrauto.ai.provider.ProviderKind

/** Persists settings on-device. The API key is kept in encrypted preferences and never logged. */
class SettingsStore(context: Context) {
    private val app = context.applicationContext
    private val plain: SharedPreferences = app.getSharedPreferences("chrauto_settings", Context.MODE_PRIVATE)
    private val secure: SharedPreferences? = runCatching {
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            app, "chrauto_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrNull()

    init { migrate() }

    var provider: ProviderKind
        get() = ProviderKind.fromId(plain.getString("provider", ProviderKind.GEMINI.id))
        set(value) = plain.edit().putString("provider", value.id).apply()

    fun apiKey(p: ProviderKind): String = secure?.getString("key_${p.id}", "").orEmpty()
    fun setApiKey(p: ProviderKind, value: String) { secure?.edit()?.putString("key_${p.id}", value.trim())?.apply() }

    fun model(p: ProviderKind): String = plain.getString("model_${p.id}", p.defaultModel).orEmpty().ifBlank { p.defaultModel }
    fun setModel(p: ProviderKind, value: String) = plain.edit().putString("model_${p.id}", value.trim()).apply()

    fun baseUrl(p: ProviderKind): String = plain.getString("base_${p.id}", p.baseUrl).orEmpty().ifBlank { p.baseUrl }
    fun setBaseUrl(p: ProviderKind, value: String) = plain.edit().putString("base_${p.id}", value.trim()).apply()

    fun config(p: ProviderKind = provider) = AiConfig(p, apiKey(p), model(p), baseUrl(p))

    fun save(config: AiConfig) {
        provider = config.provider
        setApiKey(config.provider, config.apiKey)
        setModel(config.provider, config.model)
        if (config.provider == ProviderKind.CUSTOM) setBaseUrl(config.provider, config.baseUrl)
    }

    /** 0.2.0 stored a single Gemini key/model. */
    private fun migrate() {
        val oldKey = secure?.getString("gemini_api_key", null)
        if (!oldKey.isNullOrBlank()) {
            secure?.edit()?.putString("key_gemini", oldKey)?.remove("gemini_api_key")?.apply()
        }
        plain.getString("model", null)?.let { old ->
            plain.edit().putString("model_gemini", old).remove("model").apply()
        }
    }

    var lastTask: String
        get() = plain.getString("last_task", "").orEmpty()
        set(value) = plain.edit().putString("last_task", value).apply()

    var floatingAssistant: Boolean
        get() = plain.getBoolean("floating_assistant", true)
        set(value) = plain.edit().putBoolean("floating_assistant", value).apply()

    var voiceReplies: Boolean
        get() = plain.getBoolean("voice_replies", true)
        set(value) = plain.edit().putBoolean("voice_replies", value).apply()

    // ---- Chron voice assistant ----
    var chronEnabled: Boolean
        get() = plain.getBoolean("chron_enabled", false)
        set(value) = plain.edit().putBoolean("chron_enabled", value).apply()

    var wakeName: String
        get() = plain.getString("wake_name", "Chron").orEmpty().ifBlank { "Chron" }
        set(value) = plain.edit().putString("wake_name", value.trim()).apply()

    var pauseWhenScreenOff: Boolean
        get() = plain.getBoolean("chron_screen_off_pause", true)
        set(value) = plain.edit().putBoolean("chron_screen_off_pause", value).apply()

    // ---- Task history & favourites ----
    val recents: List<String> get() = readList("recent_tasks")
    val favorites: List<String> get() = readList("favorite_tasks")

    fun addRecent(task: String) {
        val t = task.trim(); if (t.isBlank()) return
        writeList("recent_tasks", (listOf(t) + recents.filterNot { it.equals(t, true) }).take(12))
    }
    fun toggleFavorite(task: String): Boolean {
        val t = task.trim(); if (t.isBlank()) return false
        val fav = favorites
        val now = fav.none { it.equals(t, true) }
        writeList("favorite_tasks", if (now) (listOf(t) + fav).take(20) else fav.filterNot { it.equals(t, true) })
        return now
    }
    fun removeRecent(task: String) = writeList("recent_tasks", recents.filterNot { it == task })

    private fun readList(key: String): List<String> = runCatching {
        val a = org.json.JSONArray(plain.getString(key, "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())
    private fun writeList(key: String, list: List<String>) = plain.edit().putString(key, org.json.JSONArray(list).toString()).apply()

    val canStoreKey get() = secure != null
}
