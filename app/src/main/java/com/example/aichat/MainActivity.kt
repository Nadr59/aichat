package com.example.aichat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aichat.data.local.AiSettings
import com.example.aichat.ui.screens.ChatScreen
import com.example.aichat.ui.screens.ConversationsScreen
import com.example.aichat.ui.screens.SettingsScreen
import com.example.aichat.ui.theme.AiChatTheme
import com.example.aichat.ui.viewmodel.ChatViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // ✅ هذا يجعل الكيبورد يرفع المحتوى بدلاً من تغطيته
    WindowCompat.setDecorFitsSystemWindows(window, false)

    setContent {
        // ... باقي الكود
    }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AiChatTheme {
                Surface(modifier = Modifier.fillMaxSize()) {

                    val navController = rememberNavController()
                    val viewModel: ChatViewModel = viewModel()
                    val settings = AiSettings(this)

                    NavHost(
                        navController    = navController,
                        startDestination = "conversations"
                    ) {
                        composable("conversations") {
                            ConversationsScreen(
                                viewModel = viewModel,
                                onOpenChat = { conversationId ->
                                    viewModel.openConversation(conversationId)
                                    navController.navigate("chat")
                                },
                                onNewChat = {
                                    viewModel.newConversation()
                                    navController.navigate("chat")
                                },
                                onSettings = {
                                    navController.navigate("settings")
                                }
                            )
                        }
                        composable("chat") {
                          ChatScreen(
                           viewModel = viewModel,
                             settings  = settings,
                             onBack    = { navController.popBackStack() }
                           )
                        }
                        composable("settings") {
                            SettingsScreen(
                                settings = settings,
                                onBack   = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
