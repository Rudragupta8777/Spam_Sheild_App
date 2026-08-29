package com.spamshield.app.data

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single entry point for message history storage. Backed by [MessageDbHelper]; exposes a
 * [LiveData] snapshot of the full table so the UI list redraws itself after every insert/update
 * without polling.
 */
class MessageRepository private constructor(context: Context) {
    private val dbHelper = MessageDbHelper(context)
    private val _messages = MutableLiveData<List<MessageRecord>>()
    val messages: LiveData<List<MessageRecord>> = _messages

    companion object {
        @Volatile private var instance: MessageRepository? = null

        fun getInstance(context: Context): MessageRepository =
            instance ?: synchronized(this) {
                instance ?: MessageRepository(context.applicationContext).also { instance = it }
            }
    }

    suspend fun recordMessage(
        sender: String,
        body: String,
        isSpam: Boolean,
        confidence: Float
    ): Long = withContext(Dispatchers.IO) {
        val id = dbHelper.insert(
            MessageRecord(
                sender = sender,
                body = body,
                timestamp = System.currentTimeMillis(),
                isSpam = isSpam,
                confidence = confidence
            )
        )
        refresh()
        id
    }

    suspend fun correctLabel(id: Long, reviewedSpam: Boolean) = withContext(Dispatchers.IO) {
        dbHelper.updateReviewedLabel(id, reviewedSpam)
        refresh()
    }

    suspend fun markSynced(id: Long) = withContext(Dispatchers.IO) {
        dbHelper.markSynced(id)
        refresh()
    }

    suspend fun getPendingSync(): List<MessageRecord> = withContext(Dispatchers.IO) {
        dbHelper.getPendingSync()
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val all = dbHelper.getAll()
        _messages.postValue(all)
    }
}
