package com.crabcode.ide.data

import android.content.Context
import androidx.room.Room
import com.crabcode.ide.core.auth.UserSpace
import java.io.File

/** Per-user Room database — physical isolation of chat/project history. */
class CrabStore(context: Context, space: UserSpace) {

    val db: CrabDatabase = Room.databaseBuilder(
        context,
        CrabDatabase::class.java,
        File(space.db, "chats.db").absolutePath
    )
        .fallbackToDestructiveMigration()
        .build()

    fun close() = runCatching { db.close() }
}
