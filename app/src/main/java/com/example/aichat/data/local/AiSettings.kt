package com.example.aichat.data.local

import android.content.Context
import android.content.SharedPreferences

class AiSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value).apply()

    var apiUrl: String
        get() = prefs.getString("api_url", "https://api.openai.com/v1/chat/completions") ?: ""
        set(value) = prefs.edit().putString("api_url", value).apply()

    var model: String
        get() = prefs.getString("model", "gpt-3.5-turbo") ?: ""
        set(value) = prefs.edit().putString("model", value).apply()

    var systemPrompt: String
        get() = prefs.getString("system_prompt", "You are a helpful assistant.") ?: ""
        set(value) = prefs.edit().putString("system_prompt", value).apply()

    var temperature: Float
        get() = prefs.getFloat("temperature", 0.7f)
        set(value) = prefs.edit().putFloat("temperature", value).apply()
}
