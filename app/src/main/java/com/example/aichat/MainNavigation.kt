package com.example.aichat

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.aichat.data.local.AiSettings
import com.example.aichat.ui.screens.ChatScreen
import com.example.aichat.ui.screens.ConversationsScreen
import com.example.aichat.ui.screens.MemoryScreen
import com.example.aichat.ui.screens.SettingsScreen
import com.example.aichat.ui.screens.WebScreen
import com.example.aichat.ui.viewmodel.ChatViewModel

@Composable
fun MainNavigation(settings: AiSettings) {

    val navController = rememberNavController()
    val viewModel: ChatViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = "conversations"
    ) {

        // ============================================================
        // المحادثات
        // ============================================================

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
                },

                onOpenWeb = { platform ->
                    navController.navigate("web/$platform")
                }
            )
        }

        // ============================================================
        // المحادثة
        // ============================================================

        composable("chat") {
            ChatScreen(
                viewModel = viewModel,
                settings = settings,
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        // ============================================================
        // الإعدادات
        // ============================================================

        composable("settings") {
            SettingsScreen(
                settings = settings,
                onBack = {
                    navController.popBackStack()
                },
                onOpenMemory = {
                    navController.navigate("memory")
                }
            )
        }

        // ============================================================
        // الذاكرة المشتركة
        // ============================================================

        composable("memory") {
            MemoryScreen(
                viewModel = viewModel,
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        // ============================================================
        // الويب - مع argument للمنصة
        // ============================================================

        composable(
            route = "web/{platform}",
            arguments = listOf(
                navArgument("platform") {
                    type = NavType.StringType
                    defaultValue = "huggingchat"
                }
            )
        ) { backStackEntry ->

            val platform = backStackEntry
                .arguments
                ?.getString("platform")
                ?: "huggingchat"

            WebScreen(
                platform = platform,
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
