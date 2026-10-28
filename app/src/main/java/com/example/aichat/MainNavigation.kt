// app/src/main/java/com/example/aichat/MainNavigation.kt
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
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.repository.AgentRepository
import com.example.aichat.ui.screens.AgentsScreen
import com.example.aichat.ui.screens.ChatScreen
import com.example.aichat.ui.screens.ConversationsScreen
import com.example.aichat.ui.screens.GeckoTestScreen
import com.example.aichat.ui.screens.MemoryScreen
import com.example.aichat.ui.screens.SettingsScreen
import com.example.aichat.ui.screens.WebPlatformsScreen
import com.example.aichat.ui.viewmodel.AgentsViewModel
import com.example.aichat.ui.viewmodel.ChatViewModel
import com.example.aichat.ui.viewmodel.WebPlatformsViewModel

@Composable
fun MainNavigation(app: AichatApp) {

    val navController = rememberNavController()
    val settings = remember { AiSettings(app) }

    val chatViewModel: ChatViewModel = viewModel()
    val agentsViewModel: AgentsViewModel = viewModel()

    val webPlatformsViewModel: WebPlatformsViewModel =
        viewModel(factory = WebPlatformsViewModel.Factory(app.webPlatformRepository))

    // ✅ AgentRepository (لا نعتمد على AgentsViewModel داخليًا عشان GeckoTestScreen يحتاج Repository مباشرة)
    val db = remember(app) { ChatDatabase.getDatabase(app) }
    val agentRepository = remember(db) { AgentRepository(db.agentDao()) }

    val platforms by webPlatformsViewModel.platforms.collectAsState(initial = emptyList())
    val enabledPlatforms = remember(platforms) { platforms.filter { it.isEnabled } }

    val snackbarHostState = remember { SnackbarHostState() }

    val successMessage by chatViewModel.successMessage.collectAsState()
    val error by chatViewModel.error.collectAsState()

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

        Box(modifier = Modifier.fillMaxSize().padding(padding)) {

            NavHost(navController = navController, startDestination = "conversations") {

                composable("conversations") {
                    ConversationsScreen(
                        viewModel = chatViewModel,
                        settings = settings,
                        onBack = {},
                        onOpenChat = { id -> navController.navigate("chat/$id") },
                        onNewChat = {
                            chatViewModel.newConversation()
                            navController.navigate("chat/new")
                        },
                        onOpenSettings = { navController.navigate("settings") },
                        onOpenMemory = { navController.navigate("memory") },
                        webPlatformsViewModel = webPlatformsViewModel,
                        onOpenPlatform = { platform ->
                            navController.navigate("web/${platform.id}") { launchSingleTop = true }
                        },
                        onManagePlatforms = { navController.navigate("web_platforms") }
                    )
                }

                composable(
                    route = "chat/{conversationId}",
                    arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
                ) { backStackEntry ->

                    val conversationId = backStackEntry.arguments?.getString("conversationId") ?: "new"

                    LaunchedEffect(conversationId) {
                        if (conversationId == "new") chatViewModel.newConversation()
                        else conversationId.toLongOrNull()?.let { chatViewModel.openConversation(it) }
                    }

                    ChatScreen(
                        viewModel = chatViewModel,
                        agentsViewModel = agentsViewModel,
                        webPlatformsViewModel = webPlatformsViewModel,
                        settings = settings,
                        onBack = { navController.popBackStack() },
                        onOpenAgents = { navController.navigate("agents") }
                    )
                }

                composable("settings") {
                    SettingsScreen(
                        settings = settings,
                        agentsViewModel = agentsViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenMemory = { navController.navigate("memory") },
                        onOpenWebPlatforms = { navController.navigate("web_platforms") },
                        onOpenAgents = { navController.navigate("agents") }
                    )
                }

                composable("agents") {
                    AgentsScreen(
                        viewModel = agentsViewModel,
                        onNavigateUp = { navController.popBackStack() }
                    )
                }

                composable("memory") {
                    MemoryScreen(
                        viewModel = chatViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable("web_platforms") {
                    WebPlatformsScreen(
                        viewModel = webPlatformsViewModel,
                        onOpenPlatform = { platform -> navController.navigate("web/${platform.id}") },
                        onNavigateUp = { navController.popBackStack() }
                    )
                }

                composable(
                    route = "web/{platformId}",
                    arguments = listOf(navArgument("platformId") { type = NavType.StringType })
                ) { backStackEntry ->

                    // ملاحظة: GeckoTestScreen الآن self-contained ويقرأ المنصات من repo،
                    // لذلك لا نمرر (url/title/availablePlatforms...) كما كان سابقاً.
                    val platformId = backStackEntry.arguments?.getString("platformId")

                    if (platformId == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        return@composable
                    }

                    GeckoTestScreen(
    webPlatformRepository = app.webPlatformRepository,
    aiSettings = settings,
    agentRepository = agentRepository,
    chatViewModel = chatViewModel,
    initialPlatformId = platformId,
    onBack = { navController.popBackStack() },
    onHome = { navController.popBackStack("conversations", false) }
)
                }
            }
        }
    }
}
