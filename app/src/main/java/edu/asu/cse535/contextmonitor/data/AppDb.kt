package edu.asu.cse535.contextmonitor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SessionRecord::class], version = 1)
abstract class AppDb : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile private var INSTANCE: AppDb? = null
        fun get(ctx: Context): AppDb =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "cse535.db").build().also { INSTANCE = it }
            }
    }
}
