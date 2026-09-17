package com.example.aichat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.aichat.ui.theme.AiChatTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // الحصول على AichatApp مباشرة بدل AiSettings
        val app = application as AichatApp

        setContent {
            AiChatTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainNavigation(app = app)
                }
            }
        }
    }
}
