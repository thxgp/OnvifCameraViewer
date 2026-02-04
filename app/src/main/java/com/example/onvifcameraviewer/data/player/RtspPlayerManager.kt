package com.example.onvifcameraviewer.data.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages ExoPlayer instances for RTSP and HTTP streaming.
 * 
 * Configured for low-latency playback with:
 * - RTSP over TCP (reliable on Wi-Fi)
 * - HTTP progressive streaming support (for IP Webcam, etc.)
 * - Reduced buffer thresholds (500ms)
 * - Hardware decoder optimization
 */
@Singleton
class RtspPlayerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val TAG = "RtspPlayerManager"
        // Buffer configuration for low latency
        private const val MIN_BUFFER_MS = 500
        private const val MAX_BUFFER_MS = 2000
        private const val BUFFER_FOR_PLAYBACK_MS = 500
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1000
    }
    
    /**
     * Creates a media source based on the URI scheme.
     * Supports both RTSP and HTTP streams.
     */
    @OptIn(UnstableApi::class)
    private fun createMediaSource(uri: String): MediaSource {
        val mediaItem = MediaItem.fromUri(uri)
        
        return if (uri.startsWith("rtsp://")) {
            // RTSP stream
            RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)  // TCP is more reliable on Wi-Fi
                .setDebugLoggingEnabled(false)
                .createMediaSource(mediaItem)
        } else {
            // HTTP stream (MJPEG, HLS, etc.)
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(10000)
                .setAllowCrossProtocolRedirects(true)
            
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
        }
    }
    
    /**
     * Creates a configured ExoPlayer for streaming.
     * 
     * @param streamUri The stream URI (RTSP or HTTP)
     * @param onError Callback for playback errors
     * @return Configured ExoPlayer ready for playback
     */
    @OptIn(UnstableApi::class)
    fun createPlayer(
        streamUri: String,
        onError: ((PlaybackException) -> Unit)? = null
    ): ExoPlayer {
        Log.d(TAG, "Creating player for: $streamUri")
        
        // Low-latency load control configuration
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()
        
        val mediaSource = createMediaSource(streamUri)
        
        val player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setMediaSource(mediaSource)
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
                
                // Add error listener
                onError?.let { errorCallback ->
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            Log.e(TAG, "Player error: ${error.message}", error)
                            errorCallback(error)
                        }
                    })
                }
            }
        
        return player
    }
    
    /**
     * Creates a player configured for grid view (sub-stream quality).
     * Uses more aggressive buffer settings for multiple simultaneous streams.
     */
    @OptIn(UnstableApi::class)
    fun createGridPlayer(
        streamUri: String,
        onError: ((PlaybackException) -> Unit)? = null
    ): ExoPlayer {
        Log.d(TAG, "Creating grid player for: $streamUri")
        
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                300,   // Even smaller buffer for grid
                1500,
                300,
                500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        
        val mediaSource = createMediaSource(streamUri)
        
        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setMediaSource(mediaSource)
                playWhenReady = true
                volume = 0f  // Mute in grid view
                
                onError?.let { errorCallback ->
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            Log.e(TAG, "Grid player error: ${error.message}", error)
                            errorCallback(error)
                        }
                    })
                }
            }
    }
    
    /**
     * Creates a player configured for fullscreen view (main-stream quality).
     * Uses standard buffering for best quality.
     */
    @OptIn(UnstableApi::class)
    fun createFullscreenPlayer(
        streamUri: String,
        onError: ((PlaybackException) -> Unit)? = null
    ): ExoPlayer {
        Log.d(TAG, "Creating fullscreen player for: $streamUri")
        
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                1000,
                5000,
                1000,
                2000
            )
            .build()
        
        val mediaSource = createMediaSource(streamUri)
        
        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setMediaSource(mediaSource)
                playWhenReady = true
                
                onError?.let { errorCallback ->
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            Log.e(TAG, "Fullscreen player error: ${error.message}", error)
                            errorCallback(error)
                        }
                    })
                }
            }
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
