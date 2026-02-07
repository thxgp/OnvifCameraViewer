package com.example.onvifcameraviewer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.onvifcameraviewer.ui.screens.CameraGridScreen
import com.example.onvifcameraviewer.ui.screens.FullScreenPlayerScreen
import com.example.onvifcameraviewer.ui.viewmodel.CameraViewModel

/**
 * Navigation routes for the app.
 */
object Routes {
    const val CAMERA_GRID = "camera_grid"
    const val FULLSCREEN_PLAYER = "fullscreen/{cameraId}"
    
    fun fullscreenPlayer(cameraId: String) = "fullscreen/$cameraId"
}

/**
 * Main navigation composable for the app.
 */
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val viewModel: CameraViewModel = hiltViewModel()
    
    NavHost(
        navController = navController,
        startDestination = Routes.CAMERA_GRID
    ) {
        // Camera grid screen
        composable(Routes.CAMERA_GRID) {
            CameraGridScreen(
                viewModel = viewModel,
                onCameraFullscreen = { cameraId ->
                    navController.navigate(Routes.fullscreenPlayer(cameraId))
                }
            )
        }
        
        // Fullscreen player screen
        composable(
            route = Routes.FULLSCREEN_PLAYER,
            arguments = listOf(
                navArgument("cameraId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val cameraId = backStackEntry.arguments?.getString("cameraId") ?: return@composable
            val uiState by viewModel.uiState.collectAsState()
            val camera = uiState.cameras.find { it.id == cameraId } ?: return@composable
            
            // Use produceState to properly handle async stream URI retrieval
            val mainStreamUri by produceState<String?>(initialValue = null, camera) {
                // For manual cameras, use existing streamUri directly
                // For ONVIF cameras, try to get the main stream URI
                value = if (camera.device.serviceUrl.isEmpty()) {
                    // Manual camera - already has stream URI
                    camera.streamUri
                } else {
                    // ONVIF camera - try to get main stream, fall back to existing
                    viewModel.getMainStreamUri(camera) ?: camera.streamUri
                }
            }
            
            FullScreenPlayerScreen(
                camera = camera,
                mainStreamUri = mainStreamUri,
                playerManager = viewModel.playerManager,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

