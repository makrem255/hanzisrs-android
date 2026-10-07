package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.screens.AddWordScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.ProgressScreen
import com.example.ui.screens.RandomReviewScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SwipeDeckReviewScreen
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.AccentPrimaryInk
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkSurfaceContainer
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.TextMuted
import com.example.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var requestedNavigationTarget by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestedNavigationTarget = intent.getStringExtra("navigate_to")

        setContent {
            MyApplicationTheme {
                MainAppContainer(
                    viewModel = viewModel,
                    initialTarget = requestedNavigationTarget,
                    onInitialTargetHandled = { requestedNavigationTarget = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedNavigationTarget = intent.getStringExtra("navigate_to")
    }
}

/**
 * The four destinations in the bottom bar, plus the screens reached from them.
 *
 * The route strings are unchanged from the previous information architecture, so
 * every `navigate` call, deep link and test tag that referred to `library` or
 * `settings` still resolves. What changed is the *labels and order the learner sees*:
 * the vocabulary library is now "Learn", progress is promoted from a section inside
 * Home to its own destination, and settings presents as "Profile".
 */
sealed class Screen(val route: String, val title: String, val icon: @Composable () -> Unit) {
    object Home : Screen("home", "Home", { Icon(Icons.Default.Home, contentDescription = "Home") })
    object Learn : Screen("library", "Learn", { Icon(Icons.Default.School, contentDescription = "Learn") })
    object Progress : Screen("progress", "Progress", { Icon(Icons.Default.Insights, contentDescription = "Progress") })
    object Profile : Screen("settings", "Profile", { Icon(Icons.Default.Person, contentDescription = "Profile") })
    object AddWord : Screen("add_word", "Add Word", { Icon(Icons.Default.AddCircle, contentDescription = "Add Word") })
    object Deck : Screen("deck", "Review", {})

    /**
     * Open practice, deliberately not in the bottom bar.
     *
     * It is an alternative to a graded sitting rather than a fourth place to be, and adding it to
     * the navigation rail would have made four destinations carry a fifth that does a version of
     * what "Review" already does. Reached from Home, like "Review" is, and left off the rail for
     * the same reason the rail has no entry for it.
     */
    object RandomReview : Screen("random_review", "Practice", {})
    object Auth : Screen("auth", "Auth", {})
}

/**
 * The easing every screen change in the app shares.
 *
 * Named once because four transitions have to agree on it: if forward eases out and back eases
 * in, the app feels like it changes its mind depending on which way you went. `0.2f, 0f, 0f, 1f`
 * is the platform's own "decelerate" curve, so these transitions feel like the rest of Android
 * rather than like something invented here.
 */
private val NavEase = CubicBezierEasing(0.2f, 0f, 0f, 1f)

@Composable
fun MainAppContainer(
    viewModel: MainViewModel,
    initialTarget: String? = null,
    onInitialTargetHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val sessionRestored by viewModel.sessionRestored.collectAsStateWithLifecycle()
    val dueCount by viewModel.dueCount.collectAsStateWithLifecycle()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Signed out means "we checked and there is no session", not "we have not looked yet".
    // Keying this on `sessionRestored` is what stops a launch from flashing the sign-in form at
    // a learner who is still signed in, and what stops the `popUpTo(0)` below from destroying
    // the back stack before restoration has had a chance to answer.
    LaunchedEffect(sessionRestored, currentUser) {
        if (sessionRestored && currentUser == null) {
            navController.navigate(Screen.Auth.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    LaunchedEffect(initialTarget, currentUser) {
        if (initialTarget == "deck" && currentUser != null) {
            if (currentRoute != Screen.Deck.route) {
                navController.navigate(Screen.Deck.route)
            }
            onInitialTargetHandled()
        }
    }

    val bottomNavScreens = listOf(
        Screen.Home,
        Screen.Learn,
        Screen.Progress,
        Screen.Profile
    )

    val showBottomNav = currentRoute in bottomNavScreens.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomNav) {
                Column {
                    // A single hairline between content and the bar. The bar is the same
                    // colour family as the background, so without this the navigation floated
                    // with no edge at all on a scrolling screen.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(OutlineSubtle)
                    )
                    NavigationBar(
                        containerColor = DarkSurfaceContainer,
                        contentColor = AccentPrimary,
                        tonalElevation = 0.dp
                    ) {
                        bottomNavScreens.forEach { screen ->
                            val isSelected = currentRoute == screen.route
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    if (screen == Screen.Home && dueCount > 0) {
                                        BadgedBox(
                                            badge = {
                                                // The count of work outstanding, in the accent
                                                // colour, not the failure colour. A learner who
                                                // had simply not studied yet saw a red number
                                                // about work they had not failed at.
                                                Badge(
                                                    containerColor = AccentPrimary,
                                                    contentColor = AccentPrimaryInk
                                                ) {
                                                    Text("$dueCount")
                                                }
                                            }
                                        ) {
                                            screen.icon()
                                        }
                                    } else {
                                        screen.icon()
                                    }
                                },
                                label = {
                                    Text(
                                        text = screen.title,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = AccentPrimaryInk,
                                    selectedTextColor = AccentPrimary,
                                    indicatorColor = AccentPrimary,
                                    unselectedIconColor = TextMuted,
                                    unselectedTextColor = TextMuted
                                ),
                                modifier = Modifier.testTag("nav_item_${screen.route}")
                            )
                        }
                    }
                }
            }
        },
        containerColor = DarkBg
    ) { innerPadding ->
        // Nothing is drawn until the session check has answered. Rendering the graph in the
        // meantime would show the home screen - a summary, a streak and an empty library - to a
        // learner who is not signed in, and for a learner who is, it would show the loading
        // states of queries that are about to succeed. A blank surface for the few milliseconds
        // a local SQLite lookup takes is the honest one.
        if (!sessionRestored) {
            Box(Modifier.fillMaxSize().background(DarkBg))
            return@Scaffold
        }

        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding),
            // Stated rather than inherited, so the app's transitions are one decision made here
            // instead of whatever the navigation library's default happens to be.
            //
            // Deliberately shallow: a quarter of the width and a 60/240ms fade. Enough that a
            // screen arrival has direction - you can tell whether you went forward or back - and
            // not enough that a learner tapping through four destinations waits for any of them.
            // The outgoing and incoming screens overlap rather than queue, so there is never a
            // frame where the app looks empty.
            enterTransition = {
                fadeIn(tween(260)) +
                    slideInHorizontally(tween(340, easing = NavEase)) { it / 4 }
            },
            exitTransition = {
                fadeOut(tween(200)) +
                    slideOutHorizontally(tween(260, easing = NavEase)) { -it / 6 }
            },
            popEnterTransition = {
                fadeIn(tween(260)) +
                    slideInHorizontally(tween(340, easing = NavEase)) { -it / 4 }
            },
            popExitTransition = {
                fadeOut(tween(200)) +
                    slideOutHorizontally(tween(260, easing = NavEase)) { it / 6 }
            }
        ) {
            composable(Screen.Auth.route) {
                AuthScreen(
                    viewModel = viewModel,
                    onAuthSuccess = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Auth.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(Screen.Home.route) {
                HomeScreen(
                    viewModel = viewModel,
                    onStartReview = {
                        // Which cards a sitting covers is the view model's decision.
                        viewModel.startReviewSession()
                        navController.navigate(Screen.Deck.route)
                    },
                    onNavigateToAddWord = { navController.navigate(Screen.AddWord.route) },
                    onNavigateToLibrary = { navController.navigate(Screen.Learn.route) },
                    onNavigateToProgress = { navController.navigate(Screen.Progress.route) },
                    onNavigateToProfile = { navController.navigate(Screen.Profile.route) },
                    onNavigateToRandomReview = { navController.navigate(Screen.RandomReview.route) }
                )
            }

            composable(Screen.Progress.route) {
                ProgressScreen(
                    viewModel = viewModel,
                    onNavigateToLibrary = { navController.navigate(Screen.Learn.route) }
                )
            }

            composable(Screen.Deck.route) {
                SwipeDeckReviewScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.RandomReview.route) {
                RandomReviewScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.AddWord.route) {
                AddWordScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Learn.route) {
                LibraryScreen(
                    viewModel = viewModel,
                    onNavigateToAddWord = { navController.navigate(Screen.AddWord.route) }
                )
            }

            composable(Screen.Profile.route) {
                SettingsScreen(
                    viewModel = viewModel,
                    // No navigation here on purpose. `SettingsScreen` calls `viewModel.logout()`
                    // and then this callback, and `logout()` clears `currentUser` - which is the
                    // same signal the gate above watches, so navigating from both places issued
                    // two `popUpTo(0)` navigations for one sign-out. One decision, one place.
                    onLogout = {}
                )
            }
        }
    }
}
