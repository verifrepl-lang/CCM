package com.crabcode.ide.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.crabcode.ide.R
import com.crabcode.ide.ui.screens.ChatScreen
import com.crabcode.ide.ui.screens.EditorScreen
import com.crabcode.ide.ui.screens.EnvScreen
import com.crabcode.ide.ui.screens.FilesScreen
import com.crabcode.ide.ui.screens.LoginScreen
import com.crabcode.ide.ui.screens.McpScreen
import com.crabcode.ide.ui.screens.ModelsScreen
import com.crabcode.ide.ui.screens.ProjectsScreen
import com.crabcode.ide.ui.screens.SessionListScreen
import com.crabcode.ide.ui.screens.SettingsScreen
import com.crabcode.ide.ui.screens.TerminalScreen
import com.crabcode.ide.ui.screens.UpdateScreen

/** Application root: login gate → adaptive IDE scaffold. */
@Composable
fun AppRoot(onRequestNotificationPermission: () -> Unit) {
    val vm: CrabViewModel = viewModel()
    val account by vm.account.collectAsState()
    val theme by vm.theme.collectAsState()

    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (account == null) LoginScreen(vm)
        else MainScaffold(vm, onRequestNotificationPermission)
    }
}

private data class NavItem(val route: String, val labelRes: Int, val iconRes: Int)

private val PRIMARY_ITEMS = listOf(
    NavItem("projects", R.string.nav_projects, R.drawable.ic_nav_projects),
    NavItem("chat", R.string.nav_chat, R.drawable.ic_nav_chat),
    NavItem("files", R.string.nav_files, R.drawable.ic_nav_files),
    NavItem("terminal", R.string.nav_terminal, R.drawable.ic_nav_terminal)
)

private val SECONDARY_ITEMS = listOf(
    NavItem("models", R.string.nav_models, R.drawable.ic_nav_models),
    NavItem("mcp", R.string.nav_mcp, R.drawable.ic_nav_mcp),
    NavItem("sessions", R.string.nav_sessions, R.drawable.ic_nav_sessions),
    NavItem("env", R.string.nav_env, R.drawable.ic_nav_env),
    NavItem("settings", R.string.nav_settings, R.drawable.ic_nav_settings)
)

@Composable
private fun MainScaffold(vm: CrabViewModel, onRequestNotificationPermission: () -> Unit) {
    val navController = rememberNavController()
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val tablet = configuration.screenWidthDp >= 840
    val expanded = landscape || tablet   // multi-panel mode

    Row(modifier = Modifier.fillMaxSize()) {
        if (expanded) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                (PRIMARY_ITEMS + SECONDARY_ITEMS).forEach { item ->
                    RailItem(navController, item)
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Box(modifier = Modifier.weight(1f)) {
                NavGraph(vm, navController, expanded, onRequestNotificationPermission)
            }
            if (!expanded) {
                BottomBarWithMore(navController)
            }
        }
    }
}

@Composable
private fun RailItem(navController: NavHostController, item: NavItem) {
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    NavigationRailItem(
        selected = current == item.route,
        onClick = {
            navController.navigate(item.route) {
                popUpTo(navController.graph.startDestinationId)
                launchSingleTop = true
            }
        },
        icon = { Icon(painterResource(item.iconRes), contentDescription = null) },
        label = { Text(stringResourceSafe(item.labelRes)) }
    )
}

@Composable
private fun BottomBarWithMore(navController: NavHostController) {
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val moreOpen = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    if (moreOpen.value) {
        Column {
            SECONDARY_ITEMS.forEach { item ->
                androidx.compose.material3.Surface(
                    onClick = {
                        moreOpen.value = false
                        navController.navigate(item.route) { launchSingleTop = true }
                    },
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp)) {
                        Icon(painterResource(item.iconRes), null)
                        Text(
                            stringResourceSafe(item.labelRes),
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
            }
        }
    }

    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        PRIMARY_ITEMS.forEach { item ->
            NavigationBarItem(
                selected = current == item.route,
                onClick = {
                    moreOpen.value = false
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.startDestinationId)
                        launchSingleTop = true
                    }
                },
                icon = { Icon(painterResource(item.iconRes), contentDescription = null) },
                label = { Text(stringResourceSafe(item.labelRes)) }
            )
        }
        NavigationBarItem(
            selected = current in SECONDARY_ITEMS.map { it.route },
            onClick = { moreOpen.value = !moreOpen.value },
            icon = { Icon(painterResource(R.drawable.ic_nav_more), contentDescription = null) },
            label = { Text(stringResourceSafe(R.string.nav_more)) }
        )
    }
}

@Composable
private fun NavGraph(
    vm: CrabViewModel,
    navController: NavHostController,
    expanded: Boolean,
    onRequestNotificationPermission: () -> Unit
) {
    NavHost(navController = navController, startDestination = "projects") {
        composable("projects") { ProjectsScreen(vm, onOpenProject = { navController.navigate("chat") }) }
        composable("chat") { ChatScreen(vm, expanded, onRequestNotificationPermission) }
        composable("files") { FilesScreen(vm, expanded, onOpenFile = { navController.navigate("editor") }) }
        composable("terminal") { TerminalScreen(vm, expanded) }
        composable("models") { ModelsScreen(vm) }
        composable("mcp") { McpScreen(vm) }
        composable("sessions") { SessionListScreen(vm, onOpenSession = { navController.navigate("chat") }) }
        composable("env") { EnvScreen(vm) }
        composable("settings") { SettingsScreen(vm, onUpdate = { navController.navigate("update") }) }
        composable("update") { UpdateScreen(vm) }
        composable("editor") { EditorScreen(vm) }
    }
}

@Composable
private fun stringResourceSafe(res: Int): String =
    androidx.compose.ui.res.stringResource(res)
