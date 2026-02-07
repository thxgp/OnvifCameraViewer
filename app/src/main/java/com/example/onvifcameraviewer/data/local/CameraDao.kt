package com.example.onvifcameraviewer.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for camera persistence operations.
 */
@Dao
interface CameraDao {
    
    @Query("SELECT * FROM cameras")
    fun getAllCameras(): Flow<List<CameraEntity>>
    
    @Query("SELECT * FROM cameras WHERE id = :id")
    suspend fun getCameraById(id: String): CameraEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCamera(camera: CameraEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCameras(cameras: List<CameraEntity>)
    
    @Update
    suspend fun updateCamera(camera: CameraEntity)
    
    @Delete
    suspend fun deleteCamera(camera: CameraEntity)
    
    @Query("DELETE FROM cameras WHERE id = :id")
    suspend fun deleteCameraById(id: String)
    
    @Query("DELETE FROM cameras")
    suspend fun deleteAllCameras()
}
