package com.chrauto.ai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.chrauto.ai.provider.DEFAULT_MODEL

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

    var apiKey: String
        get() = secure?.getString("gemini_api_key", "").orEmpty()
        set(value) { secure?.edit()?.putString("gemini_api_key", value.trim())?.apply() }

    var model: String
        get() = plain.getString("model", DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        set(value) = plain.edit().putString("model", value.trim()).apply()

    var lastTask: String
        get() = plain.getString("last_task", "").orEmpty()
        set(value) = plain.edit().putString("last_task", value).apply()

    var floatingAssistant: Boolean
        get() = plain.getBoolean("floating_assistant", true)
        set(value) = plain.edit().putBoolean("floating_assistant", value).apply()

    var voiceReplies: Boolean
        get() = plain.getBoolean("voice_replies", true)
        set(value) = plain.edit().putBoolean("voice_replies", value).apply()

    val canStoreKey get() = secure != null
}
