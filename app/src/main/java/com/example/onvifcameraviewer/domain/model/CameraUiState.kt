package com.example.onvifcameraviewer.domain.model

/**
 * Represents the state of a camera for UI display.
 */
data class CameraUiState(
    val id: String,
    val device: OnvifDevice,
    val credentials: Credentials? = null,
    val streamUri: String? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val profiles: List<MediaProfile> = emptyList(),
    val selectedProfileToken: String? = null,
    val errorMessage: String? = null
)

/**
 * Connection states for a camera.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    LOADING_PROFILES,
    STREAMING,
    ERROR
}
