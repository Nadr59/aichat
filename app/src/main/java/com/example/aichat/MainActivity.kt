package com.example.aichat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.aichat.data.local.AiSettings
import com.example.aichat.ui.theme.AiChatTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        val settings = AiSettings(this)

        setContent {
            AiChatTheme {
                Surface(modifier = Modifier.fillMaxSize()) {

                    // ✅ كل Navigation منقول لـ MainNavigation.kt
                    // MainActivity مسؤولة فقط عن:
                    // 1. تهيئة Theme
                    // 2. تمرير Settings
                    MainNavigation(settings = settings)
                }
            }
        }
    }
}
