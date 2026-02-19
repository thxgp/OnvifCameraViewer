package com.example.onvifcameraviewer.data.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages ExoPlayer instances for RTSP and HTTP streaming.
 * 
 * Optimized for ONVIF camera streams with:
 * - Low-latency Live Configuration
 * - Reliable RTSP over TCP
 * - Custom LoadControl for high-res main streams
 */
@Singleton
class RtspPlayerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val TAG = "RtspPlayerManager"
    }
    
    /**
     * Creates a media source based on the URI scheme.
     * Configured for ultra-low-latency live streaming.
     */
    @OptIn(UnstableApi::class)
    private fun createMediaSource(uri: String): MediaSource {
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMaxPlaybackSpeed(1.02f)
                    .setMinPlaybackSpeed(0.98f)
                    .setTargetOffsetMs(100)  // Ultra-low latency target
                    .setMinOffsetMs(50)
                    .setMaxOffsetMs(500)
                    .build()
            )
            .build()
        
        return if (uri.startsWith("rtsp://")) {
            RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)  // Essential for stability on many networks
                .setDebugLoggingEnabled(false)
                .setTimeoutMs(5000)  // Faster timeout
                .createMediaSource(mediaItem)
        } else {
            val dataSourceFactory = DefaultDataSource.Factory(context)
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
        }
    }
    
    /**
     * Creates a player configured for grid view (sub-stream quality).
     * Focuses on minimal latency and resource usage.
     */
    @OptIn(UnstableApi::class)
    fun createGridPlayer(
        streamUri: String,
        onError: ((PlaybackException) -> Unit)? = null
    ): ExoPlayer {
        Log.d(TAG, "Creating grid player for: $streamUri")
        
        return try {
            // Low-latency buffering for grid view
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    1000,  // Min buffer (must be >= bufferForPlaybackAfterRebufferMs)
                    3000,  // Max buffer
                    500,   // Buffer for playback
                    1000   // Buffer for rebuffer
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            
            val mediaSource = createMediaSource(streamUri)
            
            ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .build()
                .apply {
                    setMediaSource(mediaSource)
                    playWhenReady = true
                    volume = 0f  // Grid is always muted
                    
                    onError?.let { addErrorListener(it) }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating grid player", e)
            throw e
        }
    }
    
    /**
     * Creates a player configured for fullscreen view (main-stream quality).
     * Focuses on quality and stability for high-resolution streams.
     */
    @OptIn(UnstableApi::class)
    fun createFullscreenPlayer(
        streamUri: String,
        onError: ((PlaybackException) -> Unit)? = null
    ): ExoPlayer {
        Log.d(TAG, "Creating fullscreen player for: $streamUri")
        
        return try {
            // Buffering for fullscreen - balanced for stability
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    1500,  // Min buffer (must be >= bufferForPlaybackAfterRebufferMs)
                    5000,  // Max buffer
                    750,   // Buffer for playback
                    1500   // Buffer for rebuffer
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            
            val mediaSource = createMediaSource(streamUri)
            
            ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .build()
                .apply {
                    setMediaSource(mediaSource)
                    playWhenReady = true
                    
                    onError?.let { addErrorListener(it) }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating fullscreen player", e)
            throw e
        }
    }
    
    private fun ExoPlayer.addErrorListener(callback: (PlaybackException) -> Unit) {
        addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Player error: ${error.message} (Type: ${error.errorCodeName})", error)
                callback(error)
            }
        })
    }
    
    /**
     * Safely releases a player instance.
     */
    fun releasePlayer(player: ExoPlayer?) {
        try {
            player?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing player: ${e.message}")
        }
    }
}
