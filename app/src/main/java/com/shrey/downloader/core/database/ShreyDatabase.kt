package com.shrey.downloader.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DownloadEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ShreyDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        fun create(context: Context): ShreyDatabase = Room.databaseBuilder(
            context.applicationContext,
            ShreyDatabase::class.java,
            "shrey_downloads.db"
        ).build()
    }
}
