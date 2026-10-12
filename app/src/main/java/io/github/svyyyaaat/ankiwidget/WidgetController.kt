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
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    val NOTE_ID = longPreferencesKey("note_id")
    val ORD = intPreferencesKey("ord")
    val SIDE = stringPreferencesKey("side")
    val SHOWN_AT = longPreferencesKey("shown_at")
    val NEXT_TIMES = stringPreferencesKey("next_times")
    val QUESTION = stringPreferencesKey("question")
    val ANSWER = stringPreferencesKey("answer")
    val CSS = stringPreferencesKey("css")
    val COUNT_NEW = intPreferencesKey("count_new")
    val COUNT_LEARN = intPreferencesKey("count_learn")
    val COUNT_REVIEW = intPreferencesKey("count_review")
    val HAS_COUNTS = booleanPreferencesKey("has_counts")
    val CAN_UNDO = booleanPreferencesKey("can_undo")
    val RESOLVED_DECK = longPreferencesKey("resolved_deck")
}

object Status {
    const val CARD = "card"
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
    const val REFRESH = "refresh"
}

/** Separator for next-review times; can't appear in interval strings like "<10m" or "2d". */
const val TIMES_SEPARATOR = "\u001f"

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

object WidgetController {
    private val mutex = Mutex()

    /** If an answer has been on screen this long, a periodic update replaces it with a fresh card. */
    private const val STALE_ANSWER_MS = 30 * 60 * 1000L

    /** Anki's default cap on recorded answer time. */
    private const val MAX_ANSWER_MS = 60_000L

    suspend fun perform(context: Context, id: GlanceId, action: String, ease: Int = 0) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                updateAppWidgetState(context, id) { prefs ->
                    apply(context, prefs, action, ease)
                }
            }
        }
        AnkiWidget().update(context, id)
    }

    /** Reloads the next card. Used after (re)configuration. */
    suspend fun refresh(context: Context, id: GlanceId) = perform(context, id, Action.REFRESH)

    /** Periodic refresh: picks up reviews done elsewhere, but doesn't yank an answer the user is reading. */
    suspend fun refreshIfIdle(context: Context, id: GlanceId) {
        val prefs = getAppWidgetState<Preferences>(context, PreferencesGlanceStateDefinition, id)
        val readingAnswer = prefs[Keys.SIDE] == Side.ANSWER &&
            System.currentTimeMillis() - (prefs[Keys.SHOWN_AT] ?: 0L) < STALE_ANSWER_MS
        if (readingAnswer && prefs[Keys.STATUS] == Status.CARD) {
            AnkiWidget().update(context, id)
        } else {
            refresh(context, id)
        }
    }

    /** First render of a new widget: load a card without triggering another update. */
    suspend fun ensureLoaded(context: Context, id: GlanceId) {
        val prefs = getAppWidgetState<Preferences>(context, PreferencesGlanceStateDefinition, id)
        if (prefs[Keys.STATUS] != null) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                updateAppWidgetState(context, id) { load(context, it) }
            }
        }
    }

    private fun apply(context: Context, prefs: MutablePreferences, action: String, ease: Int) {
        if (action == Action.REFRESH || prefs[Keys.STATUS] != Status.CARD) {
            load(context, prefs)
            return
        }
        val noteId = prefs[Keys.NOTE_ID] ?: return load(context, prefs)
        val ord = prefs[Keys.ORD] ?: return load(context, prefs)
        try {
            when (action) {
                Action.BACK -> prefs[Keys.SIDE] = Side.QUESTION

                Action.SHOW_ANSWER -> {
                    // The card may have been reviewed in AnkiDroid meanwhile; only flip if it's still next.
                    val next = AnkiDroid.nextCard(context, prefs[Keys.DECK_ID] ?: AnkiDroid.CURRENT_DECK)
                    if (next?.noteId == noteId && next.ord == ord) {
                        prefs[Keys.SIDE] = Side.ANSWER
                        prefs[Keys.NEXT_TIMES] = next.nextTimes.joinToString(TIMES_SEPARATOR)
                    } else {
                        load(context, prefs)
                    }
                }

                Action.ANSWER, Action.SUSPEND -> {
                    val next = AnkiDroid.nextCard(context, prefs[Keys.DECK_ID] ?: AnkiDroid.CURRENT_DECK)
                    if (next?.noteId == noteId && next.ord == ord) {
                        if (action == Action.SUSPEND) {
                            AnkiDroid.suspend(context, noteId, ord)
                        } else if (prefs[Keys.SIDE] == Side.ANSWER && ease in 1..4) {
                            val taken = (System.currentTimeMillis() - (prefs[Keys.SHOWN_AT] ?: 0L))
                                .coerceIn(0L, MAX_ANSWER_MS)
                            AnkiDroid.answer(context, noteId, ord, ease, taken)
                        }
                        prefs[Keys.CAN_UNDO] = true
                    }
                    load(context, prefs)
                }
            }
        } catch (e: Exception) {
            setError(prefs, e)
        }
    }

    private fun load(context: Context, prefs: MutablePreferences) {
        for (key in listOf(Keys.QUESTION, Keys.ANSWER, Keys.CSS, Keys.NEXT_TIMES, Keys.MESSAGE, Keys.NOTE_ID, Keys.ORD)) {
            prefs.remove(key)
        }
        prefs[Keys.HAS_COUNTS] = false
        when {
            !AnkiDroid.isInstalled(context) -> prefs[Keys.STATUS] = Status.NOT_INSTALLED
            !AnkiDroid.hasPermission(context) -> prefs[Keys.STATUS] = Status.NO_PERMISSION
            else -> try {
                val deckSetting = prefs[Keys.DECK_ID] ?: AnkiDroid.CURRENT_DECK
                val deck = AnkiDroid.resolveDeckId(context, deckSetting)
                prefs[Keys.RESOLVED_DECK] = deck ?: AnkiDroid.CURRENT_DECK
                deck?.let { AnkiDroid.counts(context, it) }?.let { counts ->
                    prefs[Keys.COUNT_NEW] = counts.new
                    prefs[Keys.COUNT_LEARN] = counts.learn
                    prefs[Keys.COUNT_REVIEW] = counts.review
                    prefs[Keys.HAS_COUNTS] = true
                }
                val card = AnkiDroid.nextCard(context, deckSetting)
                if (card == null) {
                    prefs[Keys.STATUS] = Status.DONE
                } else {
                    val content = AnkiDroid.cardContent(context, card.noteId, card.ord)
                    prefs[Keys.STATUS] = Status.CARD
                    prefs[Keys.NOTE_ID] = card.noteId
                    prefs[Keys.ORD] = card.ord
                    prefs[Keys.SIDE] = Side.QUESTION
                    prefs[Keys.SHOWN_AT] = System.currentTimeMillis()
                    prefs[Keys.NEXT_TIMES] = card.nextTimes.joinToString(TIMES_SEPARATOR)
                    prefs[Keys.QUESTION] = content.question
                    prefs[Keys.ANSWER] = content.answer
                    prefs[Keys.CSS] = content.css
                }
            } catch (e: Exception) {
                setError(prefs, e)
            }
        }
    }

    private fun setError(prefs: MutablePreferences, e: Exception) {
        prefs[Keys.STATUS] = if (e is SecurityException) Status.NO_PERMISSION else Status.ERROR
        prefs[Keys.MESSAGE] = e.message ?: e.javaClass.simpleName
    }
}
