package io.github.svyyyaaat.ankiwidget

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Per-widget settings and review state, stored in the widget's Glance preferences. */
object Keys {
    // Settings
    val DECK_ID = longPreferencesKey("deck_id")
    val SYSTEM_COLORS = booleanPreferencesKey("system_colors")
    val SHOW_COUNTS = booleanPreferencesKey("show_counts")
    val SIMPLE_CONTROLS = booleanPreferencesKey("simple_controls")

    // Review state
    val STATUS = stringPreferencesKey("status")
    val MESSAGE = stringPreferencesKey("message")
    val SIDE = stringPreferencesKey("side")
    val SHOWN_AT = longPreferencesKey("shown_at")
    val COUNT_NEW = intPreferencesKey("count_new")
    val COUNT_LEARN = intPreferencesKey("count_learn")
    val COUNT_REVIEW = intPreferencesKey("count_review")
    val HAS_COUNTS = booleanPreferencesKey("has_counts")

    /** Ease of the held answer; 0 means the card is being suspended. */
    val PENDING_EASE = intPreferencesKey("pending_ease")
    val PENDING_TAKEN = longPreferencesKey("pending_taken")
}

/** A card with everything needed to show it, stored under a key prefix. */
data class CardSnapshot(
    val noteId: Long,
    val ord: Int,
    val question: String,
    val answer: String,
    val css: String,
    val nextTimes: List<String>,
    /** Scheduler queue (0 new, 1/3 learning, 2 review), or -1 if unknown. */
    val queue: Int,
) {
    fun isSameCard(noteId: Long, ord: Int) = this.noteId == noteId && this.ord == ord
}

class CardSlot(prefix: String) {
    private val noteId = longPreferencesKey("${prefix}note_id")
    private val ord = intPreferencesKey("${prefix}ord")
    private val question = stringPreferencesKey("${prefix}question")
    private val answer = stringPreferencesKey("${prefix}answer")
    private val css = stringPreferencesKey("${prefix}css")
    private val nextTimes = stringPreferencesKey("${prefix}next_times")
    private val queue = intPreferencesKey("${prefix}queue")

    fun read(p: Preferences): CardSnapshot? {
        val id = p[noteId] ?: return null
        return CardSnapshot(
            noteId = id,
            ord = p[ord] ?: 0,
            question = p[question].orEmpty(),
            answer = p[answer].orEmpty(),
            css = p[css].orEmpty(),
            nextTimes = p[nextTimes]?.split(TIMES_SEPARATOR).orEmpty(),
            queue = p[queue] ?: -1,
        )
    }

    fun write(p: MutablePreferences, card: CardSnapshot?) {
        if (card == null) {
            listOf(noteId, ord, question, answer, css, nextTimes, queue).forEach { p.remove(it) }
            return
        }
        p[noteId] = card.noteId
        p[ord] = card.ord
        p[question] = card.question
        p[answer] = card.answer
        p[css] = card.css
        p[nextTimes] = card.nextTimes.joinToString(TIMES_SEPARATOR)
        p[queue] = card.queue
    }
}

object Slots {
    /** The card on screen. */
    val CURRENT = CardSlot("")

    /** The card after it, fetched ahead of time so answering is instant. */
    val NEXT = CardSlot("next_")

    /** The card just answered: held back from AnkiDroid so Back can undo it. */
    val PENDING = CardSlot("pending_")
}

object Status {
    const val CARD = "card"
    const val LOADING = "loading"
    const val DONE = "done"
    const val NOT_INSTALLED = "not_installed"
    const val NO_PERMISSION = "no_permission"
    const val ERROR = "error"
}

object Side {
    const val QUESTION = "q"
    const val ANSWER = "a"
}

object Action {
    const val SHOW_ANSWER = "show_answer"
    const val ANSWER = "answer"
    const val BACK = "back"
    const val SUSPEND = "suspend"

    /** Reload from AnkiDroid, keeping the held answer. */
    const val REFRESH = "refresh"

    /** Write the held answer to AnkiDroid, then reload. */
    const val COMMIT = "commit"
}

/** Separator for next-review times; can't appear in interval strings like "<10m" or "2d". */
const val TIMES_SEPARATOR = "\u001f"

/** Counts as shown: AnkiDroid's counts minus the held answer, which AnkiDroid hasn't seen yet. */
fun displayedCounts(prefs: Preferences): AnkiDroid.Counts? {
    if (prefs[Keys.HAS_COUNTS] != true) return null
    var new = prefs[Keys.COUNT_NEW] ?: 0
    var learn = prefs[Keys.COUNT_LEARN] ?: 0
    var review = prefs[Keys.COUNT_REVIEW] ?: 0
    when (Slots.PENDING.read(prefs)?.queue) {
        0 -> new--
        1, 3 -> learn--
        2 -> review--
    }
    return AnkiDroid.Counts(new.coerceAtLeast(0), learn.coerceAtLeast(0), review.coerceAtLeast(0))
}

class WidgetActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val action = parameters[ACTION] ?: return
        WidgetController.perform(context, glanceId, action, parameters[EASE] ?: 0)
    }

    companion object {
        val ACTION = ActionParameters.Key<String>("action")
        val EASE = ActionParameters.Key<Int>("ease")
    }
}

/** Writes the held answer once the widget has been left alone for a while. */
class CommitWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val appWidgetId = inputData.getInt(KEY_WIDGET, -1)
        val glanceId = runCatching { GlanceAppWidgetManager(applicationContext).getGlanceIdBy(appWidgetId) }
            .getOrNull() ?: return Result.success()
        WidgetController.perform(applicationContext, glanceId, Action.COMMIT)
        return Result.success()
    }

    companion object {
        const val KEY_WIDGET = "app_widget_id"
    }
}

/**
 * Review flow. Taps update the widget's state first and redraw straight away; talking to
 * AnkiDroid (which may need to start its process) happens afterwards, in [sync].
 *
 * AnkiDroid's API can't undo, so an answer is held back ([Slots.PENDING]) and only written
 * when the next card is answered or after [COMMIT_DELAY_MINUTES] idle. Back just drops it.
 */
object WidgetController {
    /** Guards quick state edits. */
    private val stateMutex = Mutex()

    /** Serialises AnkiDroid work. */
    private val syncMutex = Mutex()

    const val COMMIT_DELAY_MINUTES = 2L

    /** Anki's default cap on recorded answer time. */
    private const val MAX_ANSWER_MS = 60_000L

    /** How far into AnkiDroid's queue to look for the cards we hold. */
    private const val QUEUE_LOOKAHEAD = 8

    private class Commit(val noteId: Long, val ord: Int, val ease: Int, val takenMs: Long)

    suspend fun perform(context: Context, id: GlanceId, action: String, ease: Int = 0) {
        var commit: Commit? = null
        var needsSync = false
        var holdsAnswer = false
        stateMutex.withLock {
            updateAppWidgetState(context, id) { prefs ->
                val result = applyLocally(prefs, action, ease)
                commit = result.first
                needsSync = result.second
                holdsAnswer = Slots.PENDING.read(prefs) != null
            }
        }
        AnkiWidget().update(context, id)
        if (holdsAnswer && action in setOf(Action.ANSWER, Action.SUSPEND)) scheduleCommit(context, id)
        if (needsSync) sync(context, id, commit)
    }

    /** Periodic update: write any held answer and pick up reviews done in AnkiDroid. */
    suspend fun refreshIfIdle(context: Context, id: GlanceId) = perform(context, id, Action.COMMIT)

    /** First render of a new widget: load a card. */
    suspend fun ensureLoaded(context: Context, id: GlanceId) {
        val prefs = getAppWidgetState<Preferences>(context, PreferencesGlanceStateDefinition, id)
        if (prefs[Keys.STATUS] == null) sync(context, id, commit = null, redraw = false)
    }

    /** Returns the answer to write to AnkiDroid (if any) and whether AnkiDroid needs to be consulted. */
    private fun applyLocally(prefs: MutablePreferences, action: String, ease: Int): Pair<Commit?, Boolean> {
        val current = Slots.CURRENT.read(prefs)
        when (action) {
            Action.SHOW_ANSWER -> {
                if (current != null) prefs[Keys.SIDE] = Side.ANSWER
                return null to false
            }

            Action.BACK -> {
                if (prefs[Keys.SIDE] == Side.ANSWER && current != null) {
                    prefs[Keys.SIDE] = Side.QUESTION
                    return null to false
                }
                val pending = Slots.PENDING.read(prefs) ?: return null to false
                // Undo: the held card comes back, the current one becomes "next" again.
                current?.let { Slots.NEXT.write(prefs, it) }
                Slots.CURRENT.write(prefs, pending)
                Slots.PENDING.write(prefs, null)
                prefs[Keys.SIDE] = Side.QUESTION
                prefs[Keys.SHOWN_AT] = System.currentTimeMillis() - (prefs[Keys.PENDING_TAKEN] ?: 0L)
                prefs[Keys.STATUS] = Status.CARD
                return null to false
            }

            Action.ANSWER, Action.SUSPEND -> {
                if (current == null) return null to true
                if (action == Action.ANSWER && (prefs[Keys.SIDE] != Side.ANSWER || ease !in 1..4)) return null to false
                val previous = takePending(prefs)
                Slots.PENDING.write(prefs, current)
                prefs[Keys.PENDING_EASE] = if (action == Action.SUSPEND) 0 else ease
                prefs[Keys.PENDING_TAKEN] =
                    (System.currentTimeMillis() - (prefs[Keys.SHOWN_AT] ?: 0L)).coerceIn(0L, MAX_ANSWER_MS)
                val next = Slots.NEXT.read(prefs)
                Slots.CURRENT.write(prefs, next)
                Slots.NEXT.write(prefs, null)
                prefs[Keys.SIDE] = Side.QUESTION
                prefs[Keys.SHOWN_AT] = System.currentTimeMillis()
                prefs[Keys.STATUS] = if (next != null) Status.CARD else Status.LOADING
                return previous to true
            }

            Action.COMMIT -> return takePending(prefs) to true

            else -> return null to true
        }
    }

    private fun takePending(prefs: MutablePreferences): Commit? {
        val pending = Slots.PENDING.read(prefs) ?: return null
        Slots.PENDING.write(prefs, null)
        return Commit(pending.noteId, pending.ord, prefs[Keys.PENDING_EASE] ?: 0, prefs[Keys.PENDING_TAKEN] ?: 0L)
    }

    private fun scheduleCommit(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val request = OneTimeWorkRequestBuilder<CommitWorker>()
            .setInitialDelay(COMMIT_DELAY_MINUTES, TimeUnit.MINUTES)
            .setInputData(workDataOf(CommitWorker.KEY_WIDGET to appWidgetId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("commit-$appWidgetId", ExistingWorkPolicy.REPLACE, request)
    }

    /** What [sync] learned from AnkiDroid, applied to the state in one short step. */
    private sealed interface SyncResult {
        data class Status(val status: String, val message: String? = null) : SyncResult

        data class Cards(
            val counts: AnkiDroid.Counts?,
            val current: CardSnapshot?,
            val next: CardSnapshot?,
            val currentChanged: Boolean,
        ) : SyncResult
    }

    private suspend fun sync(context: Context, id: GlanceId, commit: Commit?, redraw: Boolean = true) {
        syncMutex.withLock {
            withContext(Dispatchers.IO) {
                val seen = getAppWidgetState<Preferences>(context, PreferencesGlanceStateDefinition, id)
                val result = fetch(context, seen, commit)
                stateMutex.withLock {
                    updateAppWidgetState(context, id) { prefs -> applySync(prefs, seen, result) }
                }
            }
        }
        if (redraw) AnkiWidget().update(context, id)
    }

    private fun fetch(context: Context, seen: Preferences, commit: Commit?): SyncResult {
        if (!AnkiDroid.isInstalled(context)) return SyncResult.Status(Status.NOT_INSTALLED)
        if (!AnkiDroid.hasPermission(context)) return SyncResult.Status(Status.NO_PERMISSION)
        return try {
            val deckSetting = seen[Keys.DECK_ID] ?: AnkiDroid.CURRENT_DECK
            if (commit != null) {
                // Only write it if the card is still due (it may have been reviewed in AnkiDroid meanwhile).
                val queue = AnkiDroid.nextCards(context, deckSetting, QUEUE_LOOKAHEAD)
                if (queue.any { it.noteId == commit.noteId && it.ord == commit.ord }) {
                    if (commit.ease == 0) {
                        AnkiDroid.suspend(context, commit.noteId, commit.ord)
                    } else {
                        AnkiDroid.answer(context, commit.noteId, commit.ord, commit.ease, commit.takenMs)
                    }
                }
            }

            val counts = AnkiDroid.resolveDeckId(context, deckSetting)?.let { AnkiDroid.counts(context, it) }
            val pending = Slots.PENDING.read(seen)
            val queue = AnkiDroid.nextCards(context, deckSetting, QUEUE_LOOKAHEAD)
                .filterNot { pending?.isSameCard(it.noteId, it.ord) == true }

            // Keep the card on screen if it's still due, so nothing changes under the user's finger.
            val shown = Slots.CURRENT.read(seen)
            val keep = shown?.let { s -> queue.firstOrNull { s.isSameCard(it.noteId, it.ord) } }
            val current = when {
                keep != null -> shown.copy(nextTimes = keep.nextTimes)
                else -> queue.firstOrNull()?.let { snapshot(context, it, null) }
            }
            val following = current?.let { c -> queue.firstOrNull { !c.isSameCard(it.noteId, it.ord) } }
            val next = following?.let { snapshot(context, it, Slots.NEXT.read(seen)) }
            SyncResult.Cards(counts, current, next, currentChanged = keep == null)
        } catch (e: SecurityException) {
            SyncResult.Status(Status.NO_PERMISSION, e.message)
        } catch (e: Exception) {
            SyncResult.Status(Status.ERROR, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Builds a snapshot for [due], reusing [known] content if it's the same card. */
    private fun snapshot(context: Context, due: AnkiDroid.DueCard, known: CardSnapshot?): CardSnapshot {
        if (known != null && known.isSameCard(due.noteId, due.ord)) return known.copy(nextTimes = due.nextTimes)
        val content = AnkiDroid.cardContent(context, due.noteId, due.ord)
        return CardSnapshot(
            noteId = due.noteId,
            ord = due.ord,
            question = content.question,
            answer = content.answer,
            css = content.css,
            nextTimes = due.nextTimes,
            queue = AnkiDroid.cardQueue(context, due.noteId, due.ord) ?: -1,
        )
    }

    private fun applySync(prefs: MutablePreferences, seen: Preferences, result: SyncResult) {
        when (result) {
            is SyncResult.Status -> {
                prefs[Keys.STATUS] = result.status
                result.message?.let { prefs[Keys.MESSAGE] = it } ?: prefs.remove(Keys.MESSAGE)
            }

            is SyncResult.Cards -> {
                prefs.remove(Keys.MESSAGE)
                prefs[Keys.HAS_COUNTS] = result.counts != null
                result.counts?.let {
                    prefs[Keys.COUNT_NEW] = it.new
                    prefs[Keys.COUNT_LEARN] = it.learn
                    prefs[Keys.COUNT_REVIEW] = it.review
                }
                // If the user answered or went back while we were fetching, the cards we fetched
                // are out of date; that action queued its own sync, so only take the counts.
                val unchanged = Slots.PENDING.read(prefs)?.let { it.noteId to it.ord } ==
                    Slots.PENDING.read(seen)?.let { it.noteId to it.ord } &&
                    Slots.CURRENT.read(prefs)?.let { it.noteId to it.ord } ==
                    Slots.CURRENT.read(seen)?.let { it.noteId to it.ord }
                if (!unchanged) return

                Slots.CURRENT.write(prefs, result.current)
                Slots.NEXT.write(prefs, result.next)
                if (result.currentChanged) {
                    prefs[Keys.SIDE] = Side.QUESTION
                    prefs[Keys.SHOWN_AT] = System.currentTimeMillis()
                }
                prefs[Keys.STATUS] = if (result.current != null) Status.CARD else Status.DONE
            }
        }
    }
}
