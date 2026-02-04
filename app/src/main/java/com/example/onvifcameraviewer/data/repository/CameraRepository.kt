package com.example.onvifcameraviewer.data.repository

import com.example.onvifcameraviewer.data.discovery.OnvifDiscoveryService
import com.example.onvifcameraviewer.data.onvif.OnvifMediaService
import com.example.onvifcameraviewer.domain.model.Credentials
import com.example.onvifcameraviewer.domain.model.MediaProfile
import com.example.onvifcameraviewer.domain.model.OnvifDevice
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for camera operations.
 * Provides a clean API for the domain layer to interact with cameras.
 */
@Singleton
class CameraRepository @Inject constructor(
    private val discoveryService: OnvifDiscoveryService,
    private val mediaService: OnvifMediaService
) {
    
    /**
     * Discovers ONVIF devices on the local network.
     */
    fun discoverCameras(timeoutMs: Long = 8000): Flow<OnvifDevice> {
        return discoveryService.discoverDevices(timeoutMs)
    }
    
    /**
     * Retrieves available media profiles from a camera.
     */
    suspend fun getProfiles(
        device: OnvifDevice,
        credentials: Credentials
    ): Result<List<MediaProfile>> {
        return mediaService.getProfiles(device.serviceUrl, credentials)
    }
    
    /**
     * Retrieves the RTSP stream URI for a camera profile.
     */
    suspend fun getStreamUri(
        device: OnvifDevice,
        credentials: Credentials,
        profileToken: String
    ): Result<String> {
        return mediaService.getStreamUri(
            deviceUrl = device.serviceUrl,
            credentials = credentials,
            profileToken = profileToken,
            useUdp = false  // Use TCP for reliability
        )
    }
    
    /**
     * Gets the sub-stream URI (lower quality, for grid view).
     * Attempts to find a sub-stream profile, falls back to first profile.
     */
    suspend fun getSubStreamUri(
        device: OnvifDevice,
        credentials: Credentials
    ): Result<String> {
        val profilesResult = getProfiles(device, credentials)
        if (profilesResult.isFailure) {
            return Result.failure(profilesResult.exceptionOrNull()!!)
        }
        
        val profiles = profilesResult.getOrNull() ?: emptyList()
        if (profiles.isEmpty()) {
            return Result.failure(Exception("No profiles available"))
        }
        
        // Try to find a sub-stream (usually "Profile_2" or lower resolution)
        val subStreamProfile = profiles.find { profile ->
            val config = profile.videoEncoderConfig
            config != null && config.width <= 720 && config.height <= 576
        } ?: profiles.last()  // Use last profile if no sub-stream found
        
        return getStreamUri(device, credentials, subStreamProfile.token)
    }
    
    /**
     * Gets the main-stream URI (highest quality, for fullscreen view).
     */
    suspend fun getMainStreamUri(
        device: OnvifDevice,
        credentials: Credentials
    ): Result<String> {
        val profilesResult = getProfiles(device, credentials)
        if (profilesResult.isFailure) {
            return Result.failure(profilesResult.exceptionOrNull()!!)
        }
        
        val profiles = profilesResult.getOrNull() ?: emptyList()
        if (profiles.isEmpty()) {
            return Result.failure(Exception("No profiles available"))
        }
        
        // Use first profile (usually main stream / highest quality)
        return getStreamUri(device, credentials, profiles.first().token)
    }
}
