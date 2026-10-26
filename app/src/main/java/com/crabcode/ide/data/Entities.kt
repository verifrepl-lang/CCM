package com.crabcode.ide.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastSessionId: String? = null,
    val instructionsJson: String = "[]"   // attached steering/instruction files
)

/** A conversation with the agent ("session" in desktop CrabCode terms). */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val title: String,
    val mode: String = "AGENT",           // AGENT | PLAN | ASK
    val providerId: String? = null,
    val modelId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val stateJson: String = "{}"          // resumable task state (recovery after process death)
)

/**
 * Every chat element is a row: text messages, streaming deltas, tool calls and
 * results, plan updates, statuses, file changes, approval requests — rendered
 * as separate UI items exactly like the desktop IDE does.
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val kind: Int,
    val role: String? = null,             // user | assistant | system (for TEXT)
    val content: String = "",
    val toolName: String? = null,
    val toolArgsJson: String? = null,
    val toolStatus: String? = null,       // running | ok | error | cancelled
    val createdAt: Long,
    val updatedAt: Long,
    val streamOpen: Boolean = false
) {
    companion object {
        const val KIND_TEXT = 0
        const val KIND_TOOL_CALL = 1
        const val KIND_TOOL_RESULT = 2
        const val KIND_PLAN = 3
        const val KIND_STATUS = 4
        const val KIND_ERROR = 5
        const val KIND_FILE_CHANGE = 6
        const val KIND_APPROVAL_REQUEST = 7
        const val KIND_APPROVAL_RESPONSE = 8
    }
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(p: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE projectId = :projectId ORDER BY updatedAt DESC")
    fun observeForProject(projectId: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: String): SessionEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(s: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeForSession(sessionId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(m: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageEntity?

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteRow(id: String)

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: String): Int
}

@Database(
    entities = [ProjectEntity::class, SessionEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class CrabDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
}
