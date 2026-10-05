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
import com.example.aichat.ui.viewmodel.ChatViewModel
import com.example.aichat.ui.viewmodel.WebPlatformsViewModel

@Composable
fun MainNavigation(
    app: AichatApp
) {

    val navController =
        rememberNavController()

    val settings =
        remember {
            AiSettings(app)
        }

    val chatViewModel: ChatViewModel =
        viewModel()

    val webPlatformsViewModel: WebPlatformsViewModel =
        viewModel(
            factory =
                WebPlatformsViewModel.Factory(
                    app.webPlatformRepository
                )
        )

    /*
     * قائمة المنصات المستخدمة في تبويبات GeckoTestScreen.
     *
     * نفس مصدر البيانات المستخدم في WebPlatformsScreen.
     */
    val platforms by
        webPlatformsViewModel.platforms
            .collectAsState(
                initial = emptyList()
            )

    /*
     * التبويبات تعرض المنصات المفعلة فقط.
     */
    val enabledPlatforms =
        remember(platforms) {
            platforms.filter {
                it.isEnabled
            }
        }

    val snackbarHostState =
        remember {
            SnackbarHostState()
        }

    val successMessage by
        chatViewModel.successMessage
            .collectAsState()

    val error by
        chatViewModel.error
            .collectAsState()

    LaunchedEffect(successMessage) {

        successMessage?.let {

            snackbarHostState.showSnackbar(
                it
            )

            chatViewModel.clearSuccessMessage()
        }
    }

    LaunchedEffect(error) {

        error?.let {

            snackbarHostState.showSnackbar(
                "❌ $it"
            )

            chatViewModel.clearError()
        }
    }

    Scaffold(

        snackbarHost = {

            SnackbarHost(
                hostState = snackbarHostState
            ) { data ->

                Snackbar(
                    snackbarData = data
                )
            }
        }

    ) { padding ->

        Box(

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
        ) {

            NavHost(

                navController = navController,

                startDestination =
                    "conversations"

            ) {

                // ══════════════════════════════════════════════════════
                // الرئيسية
                // ══════════════════════════════════════════════════════

                composable(
                    "conversations"
                ) {

                    ConversationsScreen(

                        viewModel =
                            chatViewModel,

                        settings =
                            settings,

                        onBack = {},

                        onOpenChat = { id ->

                            navController.navigate(
                                "chat/$id"
                            )
                        },

                        onNewChat = {

                            chatViewModel
                                .newConversation()

                            navController.navigate(
                                "chat/new"
                            )
                        },

                        onOpenSettings = {

                            navController.navigate(
                                "settings"
                            )
                        },

                        onOpenMemory = {

                            navController.navigate(
                                "memory"
                            )
                        },

                        webPlatformsViewModel =
                            webPlatformsViewModel,

                        onOpenPlatform = { platform ->

                            navController.navigate(
                                "web/${platform.id}"
                            ) {

                                launchSingleTop = true
                            }
                        },

                        onManagePlatforms = {

                            navController.navigate(
                                "web_platforms"
                            )
                        }
                    )
                }

                // ══════════════════════════════════════════════════════
                // المحادثة
                // ══════════════════════════════════════════════════════

                composable(

                    route =
                        "chat/{conversationId}",

                    arguments =
                        listOf(

                            navArgument(
                                "conversationId"
                            ) {
                                type =
                                    NavType.StringType
                            }
                        )
                ) { backStackEntry ->

                    val conversationId =
                        backStackEntry
                            .arguments
                            ?.getString(
                                "conversationId"
                            )
                            ?: "new"

                    LaunchedEffect(
                        conversationId
                    ) {

                        if (
                            conversationId == "new"
                        ) {

                            chatViewModel
                                .newConversation()

                        } else {

                            conversationId
                                .toLongOrNull()
                                ?.let {
                                    chatViewModel
                                        .openConversation(it)
                                }
                        }
                    }

                    ChatScreen(

                        viewModel =
                            chatViewModel,

                        settings =
                            settings,

                        onBack = {
                            navController.popBackStack()
                        }
                    )
                }

                // ══════════════════════════════════════════════════════
                // الإعدادات
                // ══════════════════════════════════════════════════════

                composable(
                    "settings"
                ) {

                    SettingsScreen(
    settings = settings,
    onBack = {
        navController.popBackStack()
    },
    onOpenMemory = {
        navController.navigate("memory")
    },
    onOpenWebPlatforms = {
        navController.navigate("web_platforms")
    }
)
                }

                // ══════════════════════════════════════════════════════
                // الذاكرة
                // ══════════════════════════════════════════════════════

                composable(
                    "memory"
                ) {

                    MemoryScreen(

                        viewModel =
                            chatViewModel,

                        onBack = {
                            navController.popBackStack()
                        }
                    )
                }

                // ══════════════════════════════════════════════════════
                // إدارة المنصات
                // ══════════════════════════════════════════════════════

                composable(
                    "web_platforms"
                ) {

                    WebPlatformsScreen(

                        viewModel =
                            webPlatformsViewModel,

                        onOpenPlatform = { platform ->

                            navController.navigate(
                                "web/${platform.id}"
                            )
                        },

                        onNavigateUp = {

                            navController.popBackStack()
                        }
                    )
                }

                // ══════════════════════════════════════════════════════
                // منصة الويب
                // ══════════════════════════════════════════════════════

                composable(

                    route =
                        "web/{platformId}",

                    arguments =
                        listOf(

                            navArgument(
                                "platformId"
                            ) {
                                type =
                                    NavType.StringType
                            }
                        )
                ) { backStackEntry ->

                    val platformId =
                        backStackEntry
                            .arguments
                            ?.getString(
                                "platformId"
                            )
                            ?: return@composable

                    var platform by
                        remember(platformId) {
                            mutableStateOf<WebPlatform?>(
                                null
                            )
                        }

                    LaunchedEffect(
                        platformId
                    ) {

                        platform =
                            app.webPlatformRepository
                                .getById(
                                    platformId
                                )
                    }

                    platform?.let { p ->

                        val engine =
                            remember(
                                p.preferredEngine
                            ) {

                                runCatching {

                                    WebEngine.valueOf(
                                        p.preferredEngine
                                    )

                                }.getOrDefault(
                                    WebEngine.GECKO
                                )
                            }

                        when (engine) {

                            // ──────────────────────────────────────────
                            // Gecko
                            // ──────────────────────────────────────────

                            WebEngine.GECKO -> {

                                GeckoTestScreen(

                                    url =
                                        p.url,

                                    title =
                                        p.name,

                                    onBack = {

                                        navController
                                            .popBackStack()
                                    },

                                    platform =
                                        p,

                                    chatViewModel =
                                        chatViewModel,

                                    /*
                                     * التبويبات تعرض المنصات المفعلة.
                                     */
                                    availablePlatforms =
                                        enabledPlatforms,

                                    /*
                                     * عند الضغط على تبويب منصة أخرى:
                                     *
                                     * - لا نغلق Session القديمة.
                                     * - GeckoTestScreen القديمة ستنفذ
                                     *   releaseSession().
                                     * - GeckoSessionManager يحتفظ بها.
                                     * - الشاشة الجديدة تستخدم Session
                                     *   الخاصة بالمنصة الجديدة.
                                     */
                                    onSwitchPlatform = { targetPlatform ->

                                        if (
                                            targetPlatform.id != p.id
                                        ) {

                                            navController.navigate(
                                                "web/${targetPlatform.id}"
                                            ) {

                                                /*
                                                 * لا نريد تراكم شاشة
                                                 * لكل ضغطة على تبويب.
                                                 *
                                                 * نرجع إلى أول شاشة web
                                                 * موجودة إن وجدت.
                                                 */
                                                launchSingleTop = true
                                            }
                                        }
                                    },

                                    /*
                                     * زر ⌂:
                                     *
                                     * العودة مباشرة إلى الرئيسية
                                     * conversations.
                                     *
                                     * لا نستخدم popBackStack() فقط،
                                     * لأن ذلك قد يعيد المستخدم إلى
                                     * web_platforms بدلاً من الرئيسية.
                                     */
                                    onHome = {

                                        navController
                                            .popBackStack(
                                                "conversations",
                                                false
                                            )
                                    }
                                )
                            }

                            // ──────────────────────────────────────────
                            // WebView / External
                            // ──────────────────────────────────────────

                            WebEngine.WEBVIEW,
                            WebEngine.EXTERNAL -> {

                                GeckoTestScreen(

                                    url =
                                        p.url,

                                    title =
                                        p.name,

                                    onBack = {

                                        navController
                                            .popBackStack()
                                    },

                                    /*
                                     * نحافظ على السلوك القديم لهذه
                                     * المحركات ولا نفعل وظائف الذاكرة
                                     * الخاصة بـ Gecko.
                                     */
                                    platform =
                                        null,

                                    chatViewModel =
                                        null,

                                    /*
                                     * ما زالت التبويبات متاحة.
                                     */
                                    availablePlatforms =
                                        enabledPlatforms,

                                    onSwitchPlatform = { targetPlatform ->

                                        if (
                                            targetPlatform.id != p.id
                                        ) {

                                            navController.navigate(
                                                "web/${targetPlatform.id}"
                                            ) {

                                                launchSingleTop = true
                                            }
                                        }
                                    },

                                    onHome = {

                                        navController
                                            .popBackStack(
                                                "conversations",
                                                false
                                            )
                                    }
                                )
                            }
                        }

                    } ?: Box(

                        modifier =
                            Modifier.fillMaxSize(),

                        contentAlignment =
                            Alignment.Center

                    ) {

                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}
