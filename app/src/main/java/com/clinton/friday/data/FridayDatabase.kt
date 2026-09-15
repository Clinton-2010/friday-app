package com.clinton.friday.data

import androidx.room.*

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val role: String,
    val content: String,
    val timestamp: String
)

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val event_type: String,
    val details: String?,
    val timestamp: String
)

@Entity(tableName = "knowledge")
data class KnowledgeEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val topic: String,
    val summary: String,
    val timestamp: String
)

@Entity(tableName = "preferences")
data class PreferenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val instruction: String,
    val timestamp: String
)

@Entity(tableName = "journal")
data class JournalEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val topic: String,
    val reflection: String,
    val mentioned: Int = 0,
    val timestamp: String
)

@Dao
interface FridayDao {
    @Insert
    suspend fun insertMessage(message: MessageEntity)

    @Query("SELECT * FROM messages ORDER BY id DESC LIMIT :limit")
    suspend fun getRecentMessages(limit: Int): List<MessageEntity>

    @Query("SELECT * FROM preferences")
    suspend fun getAllPreferences(): List<PreferenceEntity>

    @Insert
    suspend fun insertPreference(pref: PreferenceEntity)

    @Query("SELECT * FROM journal WHERE mentioned = 0 ORDER BY id DESC LIMIT 1")
    suspend fun getUnmentionedJournalEntry(): JournalEntity?

    @Query("UPDATE journal SET mentioned = 1 WHERE id = :id")
    suspend fun markJournalMentioned(id: Int)

    @Insert
    suspend fun insertJournal(entry: JournalEntity)

    @Query("SELECT * FROM events ORDER BY id DESC LIMIT 1")
    suspend fun getLastEvent(): EventEntity?

    @Insert
    suspend fun insertEvent(event: EventEntity)

    @Insert
    suspend fun insertKnowledge(entry: KnowledgeEntity)
}

@Database(
    entities = [MessageEntity::class, EventEntity::class, KnowledgeEntity::class, PreferenceEntity::class, JournalEntity::class],
    version = 1
)
abstract class FridayDatabase : RoomDatabase() {
    abstract fun fridayDao(): FridayDao
}
