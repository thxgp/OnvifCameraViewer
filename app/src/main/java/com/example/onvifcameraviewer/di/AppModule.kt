package com.example.onvifcameraviewer.di

import android.content.Context
import com.example.onvifcameraviewer.data.discovery.OnvifDiscoveryService
import com.example.onvifcameraviewer.data.onvif.OnvifMediaService
import com.example.onvifcameraviewer.data.player.RtspPlayerManager
import com.example.onvifcameraviewer.data.repository.CameraRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt dependency injection module.
 * Provides singleton instances of services and repositories.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideOnvifDiscoveryService(
        @ApplicationContext context: Context
    ): OnvifDiscoveryService {
        return OnvifDiscoveryService(context)
    }
    
    @Provides
    @Singleton
    fun provideOnvifMediaService(): OnvifMediaService {
        return OnvifMediaService()
    }
    
    @Provides
    @Singleton
    fun provideRtspPlayerManager(
        @ApplicationContext context: Context
    ): RtspPlayerManager {
        return RtspPlayerManager(context)
    }
    
    @Provides
    @Singleton
    fun provideCameraRepository(
        discoveryService: OnvifDiscoveryService,
        mediaService: OnvifMediaService
    ): CameraRepository {
        return CameraRepository(discoveryService, mediaService)
    }
}
