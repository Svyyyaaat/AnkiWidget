package io.github.svyyyaaat.ankiwidget

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import org.json.JSONArray

/**
 * Thin wrapper over AnkiDroid's public ContentProvider API
 * (see FlashCardsContract in the AnkiDroid repository).
 *
 * Everything goes straight into the user's AnkiDroid collection on the device:
 * answering here is the same as answering inside AnkiDroid.
 */
object AnkiDroid {
    const val PACKAGE = "com.ichi2.anki"
    const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

    /** Deck id meaning "whatever deck is currently selected in AnkiDroid". */
    const val CURRENT_DECK = -1L

    private val AUTHORITY_URI: Uri = Uri.parse("content://com.ichi2.anki.flashcards")
    private val SCHEDULE_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "schedule")
    private val DECKS_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "decks")
    private val SELECTED_DECK_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "selected_deck")
    private val NOTES_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "notes")
    private val MODELS_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "models")

    data class Deck(val id: Long, val name: String)

    data class Counts(val new: Int, val learn: Int, val review: Int)

    data class DueCard(
        val noteId: Long,
        val ord: Int,
        val buttonCount: Int,
        val nextTimes: List<String>,
    )

    data class CardContent(val question: String, val answer: String, val css: String)

    fun isInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun decks(context: Context): List<Deck> {
        val decks = mutableListOf<Deck>()
        context.contentResolver.query(DECKS_URI, arrayOf("deck_id", "deck_name"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                decks += Deck(c.getLong(0), c.getString(1))
            }
        }
        return decks.sortedBy { it.name.lowercase() }
    }

    fun selectedDeckId(context: Context): Long? =
        context.contentResolver.query(SELECTED_DECK_URI, arrayOf("deck_id"), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }

    /** Resolves [CURRENT_DECK] to a real deck id. */
    fun resolveDeckId(context: Context, deckId: Long): Long? =
        if (deckId == CURRENT_DECK) selectedDeckId(context) else deckId

    fun counts(context: Context, deckId: Long): Counts? {
        val uri = Uri.withAppendedPath(DECKS_URI, deckId.toString())
        return context.contentResolver.query(uri, arrayOf("deck_count"), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            // AnkiDroid returns [learn, review, new]
            val arr = JSONArray(c.getString(0))
            Counts(new = arr.optInt(2), learn = arr.optInt(0), review = arr.optInt(1))
        }
    }

    /** The next [limit] cards AnkiDroid would show, in order. */
    fun nextCards(context: Context, deckId: Long, limit: Int): List<DueCard> {
        val (selection, args) =
            if (deckId == CURRENT_DECK) {
                "limit=?" to arrayOf(limit.toString())
            } else {
                "limit=?,deckID=?" to arrayOf(limit.toString(), deckId.toString())
            }
        val projection = arrayOf("note_id", "ord", "button_count", "next_review_times")
        val cards = mutableListOf<DueCard>()
        context.contentResolver.query(SCHEDULE_URI, projection, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                val times = JSONArray(c.getString(3))
                cards += DueCard(
                    noteId = c.getLong(0),
                    ord = c.getInt(1),
                    buttonCount = c.getInt(2),
                    nextTimes = List(times.length()) { times.optString(it) },
                )
            }
        }
        return cards
    }

    /**
     * The card's scheduler queue (0 new, 1/3 learning, 2 review), or null if this AnkiDroid
     * version doesn't expose it (the column was added to the API recently).
     */
    fun cardQueue(context: Context, noteId: Long, ord: Int): Int? =
        try {
            context.contentResolver.query(cardUri(noteId, ord), arrayOf("queue"), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getInt(0) else null
            }
        } catch (e: Exception) {
            null
        }

    private fun cardUri(noteId: Long, ord: Int): Uri =
        Uri.withAppendedPath(Uri.withAppendedPath(Uri.withAppendedPath(NOTES_URI, noteId.toString()), "cards"), ord.toString())

    private val cssCache = mutableMapOf<Long, String>()

    fun cardContent(context: Context, noteId: Long, ord: Int): CardContent {
        val cr = context.contentResolver
        val noteUri = Uri.withAppendedPath(NOTES_URI, noteId.toString())
        val (question, answer) =
            cr.query(cardUri(noteId, ord), arrayOf("question_simple", "answer_pure"), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0).orEmpty() to c.getString(1).orEmpty() else null
            } ?: ("" to "")

        val modelId = cr.query(noteUri, arrayOf("mid"), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
        val css =
            modelId?.let { mid ->
                cssCache.getOrPut(mid) {
                    cr.query(Uri.withAppendedPath(MODELS_URI, mid.toString()), arrayOf("css"), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "" }
                        .orEmpty()
                }
            }.orEmpty()
        return CardContent(question, answer, css)
    }

    /** [ease] is 1..4 (Again, Hard, Good, Easy). */
    fun answer(context: Context, noteId: Long, ord: Int, ease: Int, timeTakenMs: Long) {
        val values = ContentValues().apply {
            put("note_id", noteId)
            put("ord", ord)
            put("answer_ease", ease)
            put("time_taken", timeTakenMs)
        }
        context.contentResolver.update(SCHEDULE_URI, values, null, null)
    }

    fun suspend(context: Context, noteId: Long, ord: Int) {
        val values = ContentValues().apply {
            put("note_id", noteId)
            put("ord", ord)
            put("suspended", 1)
        }
        context.contentResolver.update(SCHEDULE_URI, values, null, null)
    }

    fun playStoreIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
