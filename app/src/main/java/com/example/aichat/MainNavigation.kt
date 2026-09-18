package com.example.aichat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.model.WebEngine
import com.example.aichat.data.model.WebPlatform
import com.example.aichat.ui.screens.ChatScreen
import com.example.aichat.ui.screens.ConversationsScreen
import com.example.aichat.ui.screens.GeckoTestScreen
import com.example.aichat.ui.screens.MemoryScreen
import com.example.aichat.ui.screens.SettingsScreen
import com.example.aichat.ui.screens.WebPlatformsScreen
import com.example.aichat.ui.screens.WebScreen
import com.example.aichat.ui.viewmodel.ChatViewModel
import com.example.aichat.ui.viewmodel.WebPlatformsViewModel

@Composable
fun MainNavigation(app: AichatApp) {

    val navController         = rememberNavController()
    val settings              = remember { AiSettings(app) }
    val chatViewModel: ChatViewModel = viewModel()
    val webPlatformsViewModel: WebPlatformsViewModel = viewModel(
        factory = WebPlatformsViewModel.Factory(app.webPlatformRepository)
    )

    // ── Snackbar مشترك لكل الشاشات ───────────────────────────────────────
    val snackbarHostState = remember { SnackbarHostState() }

    // مراقبة successMessage من ChatViewModel
    val successMessage by chatViewModel.successMessage.collectAsState()
    val error          by chatViewModel.error.collectAsState()

    LaunchedEffect(successMessage) {
        successMessage?.let {
            snackbarHostState.showSnackbar(it)
            chatViewModel.clearSuccessMessage()
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar("❌ $it")
            chatViewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        }
    ) { padding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            NavHost(
                navController    = navController,
                startDestination = "conversations"
            ) {

                // ── المحادثات ─────────────────────────────────────────────
                composable("conversations") {
                    ConversationsScreen(
                        viewModel             = chatViewModel,
                        settings              = settings,
                        onBack                = { },
                        onOpenChat            = { id -> navController.navigate("chat/$id") },
                        onNewChat             = {
                            chatViewModel.newConversation()
                            navController.navigate("chat/new")
                        },
                        onOpenSettings        = { navController.navigate("settings") },
                        onOpenMemory          = { navController.navigate("memory") },
                        webPlatformsViewModel = webPlatformsViewModel,
                        onOpenPlatform        = { platform ->
                            navController.navigate("web/${platform.id}")
                        },
                        onManagePlatforms     = { navController.navigate("web_platforms") }
                    )
                }

                // ── المحادثة ──────────────────────────────────────────────
                composable(
                    route     = "chat/{conversationId}",
                    arguments = listOf(
                        navArgument("conversationId") { type = NavType.StringType }
                    )
                ) { back ->
                    val conversationId = back.arguments
                        ?.getString("conversationId") ?: "new"

                    LaunchedEffect(conversationId) {
                        if (conversationId == "new") {
                            chatViewModel.newConversation()
                        } else {
                            conversationId.toLongOrNull()
                                ?.let { chatViewModel.openConversation(it) }
                        }
                    }

                    // ChatScreen بدون Snackbar محلي — يستخدم المشترك
                    ChatScreen(
                        viewModel = chatViewModel,
                        settings  = settings,
                        onBack    = { navController.popBackStack() }
                    )
                }

                // ── الإعدادات ─────────────────────────────────────────────
                composable("settings") {
                    SettingsScreen(
                        settings     = settings,
                        onBack       = { navController.popBackStack() },
                        onOpenMemory = { navController.navigate("memory") }
                    )
                }

                // ── الذاكرة ───────────────────────────────────────────────
                composable("memory") {
                    MemoryScreen(
                        viewModel = chatViewModel,
                        onBack    = { navController.popBackStack() }
                    )
                }

                // ── إدارة المنصات ─────────────────────────────────────────
                composable("web_platforms") {
                    WebPlatformsScreen(
                        viewModel      = webPlatformsViewModel,
                        onOpenPlatform = { platform ->
                            navController.navigate("web/${platform.id}")
                        },
                        onNavigateUp   = { navController.popBackStack() }
                    )
                }

                // ── المتصفح ───────────────────────────────────────────────
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
                        when (WebEngine.valueOf(p.preferredEngine)) {
                            WebEngine.GECKO -> GeckoTestScreen(
                                url    = p.url,
                                title  = p.name,
                                onBack = { navController.popBackStack() }
                            )
                            WebEngine.WEBVIEW,
                            WebEngine.EXTERNAL -> WebScreen(
                                url           = p.url,
                                title         = p.name,
                                initialEngine = WebEngine.valueOf(p.preferredEngine),
                                onNavigateUp  = { navController.popBackStack() }
                            )
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
    }
}
