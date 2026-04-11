package co.crackn.kompressor.sample

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import co.crackn.kompressor.sample.audio.AudioScreen
import co.crackn.kompressor.sample.di.AppComponent
import co.crackn.kompressor.sample.image.ImageScreen
import co.crackn.kompressor.sample.video.VideoScreen
import co.crackn.kompressor.sample.navigation.Route
import co.crackn.kompressor.sample.theme.KompressorTheme
import kompressor.sample.generated.resources.Res
import kompressor.sample.generated.resources.tab_audio
import kompressor.sample.generated.resources.tab_image
import kompressor.sample.generated.resources.tab_video
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private data class TabItem<T : Any>(
    val route: T,
    val labelRes: StringResource,
    val icon: ImageVector,
)

private val tabs = listOf(
    TabItem(route = Route.Image, labelRes = Res.string.tab_image, icon = Icons.Filled.Image),
    TabItem(route = Route.Video, labelRes = Res.string.tab_video, icon = Icons.Filled.Videocam),
    TabItem(route = Route.Audio, labelRes = Res.string.tab_audio, icon = Icons.Filled.MusicNote),
)

@Composable
fun App(appComponent: AppComponent) {
    KompressorTheme {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination

        Scaffold(
            bottomBar = {
                NavigationBar {
                    tabs.forEach { tab ->
                        val label = stringResource(tab.labelRes)
                        NavigationBarItem(
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(label) },
                            selected = currentDestination?.hasRoute(tab.route::class) == true,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Route.Image,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable<Route.Image> {
                    val vm = viewModel { appComponent.imageCompressViewModelFactory() }
                    ImageScreen(viewModel = vm)
                }
                composable<Route.Video> {
                    val vm = viewModel { appComponent.videoCompressViewModelFactory() }
                    VideoScreen(viewModel = vm)
                }
                composable<Route.Audio> {
                    val vm = viewModel { appComponent.audioCompressViewModelFactory() }
                    AudioScreen(viewModel = vm)
                }
            }
        }
    }
}