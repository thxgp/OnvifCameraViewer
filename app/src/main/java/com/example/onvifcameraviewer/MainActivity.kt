package com.example.onvifcameraviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.onvifcameraviewer.ui.navigation.AppNavigation
import com.example.onvifcameraviewer.ui.theme.OnvifCameraViewerTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Main entry point for the ONVIF Camera Viewer application.
 * Uses Hilt for dependency injection and Jetpack Compose for UI.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OnvifCameraViewerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }
}
