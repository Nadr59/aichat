package com.example.aichat

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.aichat.ui.screens.ChatScreen
import com.example.aichat.ui.screens.ConversationsScreen
import com.example.aichat.ui.screens.SettingsScreen

@Composable
fun MainNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "conversations"
    ) {
        composable("conversations") {
            ConversationsScreen(
                navController = navController
            )
        }
        composable("chat/{conversationId}") { backStackEntry ->
            val conversationId = backStackEntry.arguments
                ?.getString("conversationId")?.toLongOrNull() ?: 0L
            ChatScreen(
                conversationId = conversationId,
                navController = navController
            )
        }
        composable("settings") {
            SettingsScreen(
                navController = navController
            )
        }
    }
}
