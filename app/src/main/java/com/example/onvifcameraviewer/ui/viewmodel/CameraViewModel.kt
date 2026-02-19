package com.example.onvifcameraviewer.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.onvifcameraviewer.data.player.RtspPlayerManager
import com.example.onvifcameraviewer.data.repository.CameraRepository
import com.example.onvifcameraviewer.domain.exception.OnvifException
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
    val showManualAddDialog: Boolean = false,
    val showDeleteDialog: Boolean = false,
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
    
    companion object {
        private const val TAG = "CameraViewModel"
    }
    
    init {
        loadSavedCameras()
    }
    
    /**
     * Loads saved cameras from the database.
     */
    private fun loadSavedCameras() {
        viewModelScope.launch {
            try {
                cameraRepository.getSavedCameras().collect { savedCameras ->
                    val cameraStates = savedCameras.map { saved ->
                        CameraUiState(
                            id = saved.id,
                            device = saved.device,
                            credentials = saved.credentials,
                            streamUri = saved.streamUri,
                            connectionState = if (saved.streamUri != null) 
                                ConnectionState.STREAMING 
                            else 
                                ConnectionState.DISCONNECTED
                        )
                    }
                    _uiState.update { it.copy(cameras = cameraStates) }
                    Log.d(TAG, "Loaded ${cameraStates.size} saved cameras")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading saved cameras", e)
            }
        }
    }
    
    /**
     * Starts scanning for ONVIF cameras on the network.
     * Preserves manually added cameras during discovery.
     */
    fun startDiscovery() {
        if (_uiState.value.isScanning) return
        
        // Keep manually added cameras (those with empty serviceUrl)
        val manualCameras = _uiState.value.cameras.filter { it.device.serviceUrl.isEmpty() }
        _uiState.update { it.copy(isScanning = true, cameras = manualCameras) }
        
        viewModelScope.launch {
            try {
                cameraRepository.discoverCameras().collect { device ->
                    addDiscoveredCamera(device)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal cancellation, don't show error
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(errorMessage = "Discovery failed: ${e.message ?: "Unknown error"}") 
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
                
                // Save camera to database for persistence
                try {
                    cameraRepository.saveCamera(
                        id = cameraId,
                        name = camera.device.name,
                        streamUri = streamUri,
                        device = camera.device,
                        credentials = credentials,
                        isManual = false
                    )
                    Log.d(TAG, "Saved camera to database: ${camera.device.name}")
                } catch (saveError: Exception) {
                    Log.e(TAG, "Failed to save camera to database", saveError)
                }
            } catch (e: Exception) {
                val userMessage = when (e) {
                    is OnvifException.AuthenticationException -> "Wrong username or password"
                    is OnvifException.NetworkException -> e.message ?: "Network error"
                    is OnvifException.NoProfilesException -> "Camera has no streaming profiles"
                    is OnvifException.TimeoutException -> "Connection timed out - check camera IP"
                    is OnvifException.StreamUriException -> "Failed to get stream URL"
                    else -> e.message ?: "Connection failed"
                }
                updateCameraState(cameraId) { 
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        errorMessage = userMessage
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
     * Opens the manual camera add dialog.
     */
    fun showManualAddDialog() {
        _uiState.update { it.copy(showManualAddDialog = true) }
    }
    
    /**
     * Dismisses the manual camera add dialog.
     */
    fun dismissManualAddDialog() {
        _uiState.update { it.copy(showManualAddDialog = false) }
    }
    
    /**
     * Adds a camera manually with a direct stream URL.
     * Used for non-ONVIF cameras like IP Webcam.
     */
    fun addManualCamera(name: String, streamUrl: String, username: String, password: String) {
        val id = "manual_${System.currentTimeMillis()}"
        val ipAddress = OnvifDevice.extractIpFromUrl(streamUrl).ifEmpty { "Unknown" }
        
        val device = OnvifDevice(
            id = id,
            name = name,
            manufacturer = "Manual",
            model = "",
            serviceUrl = "",
            ipAddress = ipAddress
        )
        
        val credentials = if (username.isNotBlank()) {
            Credentials(username, password)
        } else {
            null
        }
        
        val cameraState = CameraUiState(
            id = id,
            device = device,
            credentials = credentials,
            streamUri = streamUrl,
            connectionState = ConnectionState.STREAMING
        )
        
        _uiState.update { state ->
            state.copy(
                cameras = state.cameras + cameraState,
                showManualAddDialog = false
            )
        }
        
        // Save to database
        viewModelScope.launch {
            try {
                cameraRepository.saveCamera(
                    id = id,
                    name = name,
                    streamUri = streamUrl,
                    device = device,
                    credentials = credentials,
                    isManual = true
                )
                Log.d(TAG, "Saved manual camera: $name")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving camera", e)
            }
        }
    }
    
    /**
     * Clears error message.
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
    
    /**
     * Shows the delete confirmation dialog for a camera.
     */
    fun showDeleteDialog(cameraId: String) {
        _uiState.update { 
            it.copy(showDeleteDialog = true, selectedCameraId = cameraId) 
        }
    }
    
    /**
     * Dismisses the delete confirmation dialog.
     */
    fun dismissDeleteDialog() {
        _uiState.update { 
            it.copy(showDeleteDialog = false, selectedCameraId = null) 
        }
    }
    
    /**
     * Deletes a camera from both UI state and database.
     */
    fun deleteCamera(cameraId: String) {
        viewModelScope.launch {
            try {
                // Remove from database
                cameraRepository.deleteCamera(cameraId)
                
                // Remove from UI state
                _uiState.update { state ->
                    state.copy(
                        cameras = state.cameras.filter { it.id != cameraId },
                        showDeleteDialog = false,
                        selectedCameraId = null
                    )
                }
                Log.d(TAG, "Deleted camera: $cameraId")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting camera", e)
                _uiState.update { 
                    it.copy(errorMessage = "Failed to delete camera: ${e.message}") 
                }
            }
        }
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
