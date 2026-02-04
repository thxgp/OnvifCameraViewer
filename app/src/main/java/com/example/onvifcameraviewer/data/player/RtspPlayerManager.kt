package com.example.onvifcameraviewer.data.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages ExoPlayer instances for RTSP streaming.
 * 
 * Configured for low-latency playback with:
 * - RTSP over TCP (reliable on Wi-Fi)
 * - Reduced buffer thresholds (500ms)
 * - Hardware decoder optimization
 */
@Singleton
class RtspPlayerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        // Buffer configuration for low latency
        private const val MIN_BUFFER_MS = 500
        private const val MAX_BUFFER_MS = 2000
        private const val BUFFER_FOR_PLAYBACK_MS = 500
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1000
    }
    
    /**
     * Creates a configured ExoPlayer for RTSP streaming.
     * 
     * @param rtspUri The RTSP stream URI (with embedded credentials if needed)
     * @param onError Callback for playback errors
     * @return Configured ExoPlayer ready for playback
     */
    @OptIn(UnstableApi::class)
    fun createPlayer(
        rtspUri: String,
        onError: ((PlaybackException) -> Unit)? = null
    ): ExoPlayer {
        // Low-latency load control configuration
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()
        
        // RTSP media source configured for TCP transport
        val rtspMediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)  // TCP is more reliable on Wi-Fi
            .setDebugLoggingEnabled(false)
            .createMediaSource(MediaItem.fromUri(rtspUri))
        
        val player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setMediaSource(rtspMediaSource)
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
                
                // Add error listener
                onError?.let { errorCallback ->
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
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
        rtspUri: String,
        onError: ((PlaybackException) -> Unit)? = null
    ): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                300,   // Even smaller buffer for grid
                1500,
                300,
                500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        
        val rtspMediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(rtspUri))
        
        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setMediaSource(rtspMediaSource)
                playWhenReady = true
                volume = 0f  // Mute in grid view
                
                onError?.let { errorCallback ->
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
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
        rtspUri: String,
        onError: ((PlaybackException) -> Unit)? = null
    ): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                1000,
                5000,
                1000,
                2000
            )
            .build()
        
        val rtspMediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(rtspUri))
        
        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setMediaSource(rtspMediaSource)
                playWhenReady = true
                
                onError?.let { errorCallback ->
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
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
        player?.apply {
            stop()
            release()
        }
    }
}
