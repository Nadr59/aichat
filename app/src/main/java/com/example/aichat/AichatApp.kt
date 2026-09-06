package com.example.aichat

import android.app.Application
import com.example.aichat.data.local.ChatDatabase

class AichatApp : Application() {
    val database: ChatDatabase by lazy {
        ChatDatabase.getDatabase(this)
    }
}
