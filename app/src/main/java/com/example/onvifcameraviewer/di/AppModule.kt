package com.example.onvifcameraviewer.di

import android.content.Context
import com.example.onvifcameraviewer.data.player.RtspPlayerManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt dependency injection module.
 * Provides singleton instances for classes that cannot use constructor injection.
 * (e.g., classes requiring Context or third-party libraries).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideRtspPlayerManager(
        @ApplicationContext context: Context
    ): RtspPlayerManager {
        return RtspPlayerManager(context)
    }
}
