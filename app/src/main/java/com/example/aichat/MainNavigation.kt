package com.example.aichat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.aichat.data.model.WebEngine
import com.example.aichat.data.model.WebPlatform
import com.example.aichat.ui.screens.ChatScreen
import com.example.aichat.ui.screens.ConversationsScreen
import com.example.aichat.ui.screens.GeckoTestScreen
import com.example.aichat.ui.screens.MemoryScreen
import com.example.aichat.ui.screens.SettingsScreen
import com.example.aichat.ui.screens.WebPlatformsScreen
import com.example.aichat.ui.screens.WebScreen
import com.example.aichat.ui.viewmodel.WebPlatformsViewModel

@Composable
fun MainNavigation(app: AichatApp) {

    val navController = rememberNavController()

    val webPlatformsViewModel: WebPlatformsViewModel = viewModel(
        factory = WebPlatformsViewModel.Factory(app.webPlatformRepository)
    )

    NavHost(
        navController    = navController,
        startDestination = "conversations"
    ) {

        // ── المحادثات ─────────────────────────────────────────────────────
        composable("conversations") {
            ConversationsScreen(
                onOpenChat            = { id -> navController.navigate("chat/$id") },
                onNewChat             = { navController.navigate("chat/new") },
                onOpenSettings        = { navController.navigate("settings") },
                onOpenMemory          = { navController.navigate("memory") },
                webPlatformsViewModel = webPlatformsViewModel,
                onOpenPlatform        = { platform ->
                    navController.navigate("web/${platform.id}")
                },
                onManagePlatforms     = { navController.navigate("web_platforms") }
            )
        }

        // ── المحادثة ──────────────────────────────────────────────────────
        composable(
            route     = "chat/{conversationId}",
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType }
            )
        ) { back ->
            val conversationId = back.arguments?.getString("conversationId") ?: "new"
            ChatScreen(
                conversationId = conversationId,
                onNavigateUp   = { navController.popBackStack() }
            )
        }

        // ── الإعدادات ─────────────────────────────────────────────────────
        composable("settings") {
            SettingsScreen(onNavigateUp = { navController.popBackStack() })
        }

        // ── الذاكرة ───────────────────────────────────────────────────────
        composable("memory") {
            MemoryScreen(onNavigateUp = { navController.popBackStack() })
        }

        // ── إدارة المنصات ─────────────────────────────────────────────────
        composable("web_platforms") {
            WebPlatformsScreen(
                viewModel      = webPlatformsViewModel,
                onOpenPlatform = { platform ->
                    navController.navigate("web/${platform.id}")
                },
                onNavigateUp   = { navController.popBackStack() }
            )
        }

        // ── المتصفح (بالمعرّف) ────────────────────────────────────────────
        composable(
            route     = "web/{platformId}",
            arguments = listOf(
                navArgument("platformId") { type = NavType.StringType }
            )
        ) { back ->
            val platformId = back.arguments?.getString("platformId")
                ?: return@composable

            var platform by remember { mutableStateOf<WebPlatform?>(null) }

            LaunchedEffect(platformId) {
                platform = app.webPlatformRepository.getById(platformId)
            }

            platform?.let { p ->
                // اختيار المحرك حسب تفضيل المنصة
                when (WebEngine.valueOf(p.preferredEngine)) {
                    WebEngine.GECKO -> {
                        GeckoTestScreen(
                            url    = p.url,
                            title  = p.name,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    WebEngine.WEBVIEW, WebEngine.EXTERNAL -> {
                        WebScreen(
                            url           = p.url,
                            title         = p.name,
                            initialEngine = WebEngine.valueOf(p.preferredEngine),
                            onNavigateUp  = { navController.popBackStack() }
                        )
                    }
                }
            } ?: Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}
