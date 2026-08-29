package com.spamshield.app.data

/**
 * One screened message (real SMS or a manual test) plus its verdict.
 *
 * [reviewedLabel] is null until the user long-presses a row to correct the model — `true`/`false`
 * then records what the message *actually* was, independent of [isSpam] (what the model said).
 * That correction is what gets shipped to the backend so mislabeled patterns feed retraining.
 */
data class MessageRecord(
    val id: Long = 0,
    val sender: String,
    val body: String,
    val timestamp: Long,
    val isSpam: Boolean,
    val confidence: Float,
    val reviewedLabel: Boolean? = null,
    val synced: Boolean = false
)
