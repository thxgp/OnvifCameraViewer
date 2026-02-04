package com.example.onvifcameraviewer.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.onvifcameraviewer.data.player.RtspPlayerManager
import com.example.onvifcameraviewer.data.repository.CameraRepository
import com.example.onvifcameraviewer.domain.model.CameraUiState
import com.example.onvifcameraviewer.domain.model.ConnectionState
import com.example.onvifcameraviewer.domain.model.Credentials
import com.example.onvifcameraviewer.domain.model.OnvifDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Main screen UI state.
 */
data class MainScreenState(
    val cameras: List<CameraUiState> = emptyList(),
    val isScanning: Boolean = false,
    val showAuthDialog: Boolean = false,
    val selectedCameraId: String? = null,
    val errorMessage: String? = null
)

/**
 * ViewModel for the camera grid screen.
 * Manages camera discovery, authentication, and streaming state.
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val cameraRepository: CameraRepository,
    val playerManager: RtspPlayerManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(MainScreenState())
    val uiState: StateFlow<MainScreenState> = _uiState.asStateFlow()
    
    /**
     * Starts scanning for ONVIF cameras on the network.
     */
    fun startDiscovery() {
        if (_uiState.value.isScanning) return
        
        _uiState.update { it.copy(isScanning = true, cameras = emptyList()) }
        
        viewModelScope.launch {
            try {
                cameraRepository.discoverCameras().collect { device ->
                    addDiscoveredCamera(device)
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(errorMessage = "Discovery failed: ${e.message}") 
                }
            } finally {
                _uiState.update { it.copy(isScanning = false) }
            }
        }
    }
    
    /**
     * Adds a newly discovered camera to the UI state.
     */
    private fun addDiscoveredCamera(device: OnvifDevice) {
        val cameraState = CameraUiState(
            id = device.id,
            device = device,
            connectionState = ConnectionState.DISCONNECTED
        )
        
        _uiState.update { state ->
            if (state.cameras.none { it.id == device.id }) {
                state.copy(cameras = state.cameras + cameraState)
            } else {
                state
            }
        }
    }
    
    /**
     * Opens the authentication dialog for a camera.
     */
    fun requestAuthentication(cameraId: String) {
        _uiState.update { 
            it.copy(showAuthDialog = true, selectedCameraId = cameraId) 
        }
    }
    
    /**
     * Dismisses the authentication dialog.
     */
    fun dismissAuthDialog() {
        _uiState.update { 
            it.copy(showAuthDialog = false, selectedCameraId = null) 
        }
    }
    
    /**
     * Authenticates and connects to a camera.
     */
    fun connectCamera(cameraId: String, username: String, password: String) {
        val camera = _uiState.value.cameras.find { it.id == cameraId } ?: return
        val credentials = Credentials(username, password)
        
        updateCameraState(cameraId) { 
            it.copy(
                credentials = credentials,
                connectionState = ConnectionState.AUTHENTICATING
            ) 
        }
        dismissAuthDialog()
        
        viewModelScope.launch {
            try {
                // Get profiles first
                updateCameraState(cameraId) { 
                    it.copy(connectionState = ConnectionState.LOADING_PROFILES) 
                }
                
                val profilesResult = cameraRepository.getProfiles(camera.device, credentials)
                if (profilesResult.isFailure) {
                    throw profilesResult.exceptionOrNull()!!
                }
                val profiles = profilesResult.getOrNull() ?: emptyList()
                
                // Get sub-stream URI for grid view
                val streamResult = cameraRepository.getSubStreamUri(camera.device, credentials)
                if (streamResult.isFailure) {
                    throw streamResult.exceptionOrNull()!!
                }
                val streamUri = streamResult.getOrNull()!!
                
                updateCameraState(cameraId) { 
                    it.copy(
                        profiles = profiles,
                        selectedProfileToken = profiles.lastOrNull()?.token,
                        streamUri = streamUri,
                        connectionState = ConnectionState.STREAMING
                    ) 
                }
            } catch (e: Exception) {
                updateCameraState(cameraId) { 
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        errorMessage = e.message ?: "Connection failed"
                    ) 
                }
            }
        }
    }
    
    /**
     * Updates the state of a specific camera.
     */
    private fun updateCameraState(cameraId: String, update: (CameraUiState) -> CameraUiState) {
        _uiState.update { state ->
            state.copy(
                cameras = state.cameras.map { camera ->
                    if (camera.id == cameraId) update(camera) else camera
                }
            )
        }
    }
    
    /**
     * Clears error message.
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
    
    /**
     * Gets the main stream URI for fullscreen view.
     */
    suspend fun getMainStreamUri(camera: CameraUiState): String? {
        val credentials = camera.credentials ?: return null
        val result = cameraRepository.getMainStreamUri(camera.device, credentials)
        return result.getOrNull()
    }
}
