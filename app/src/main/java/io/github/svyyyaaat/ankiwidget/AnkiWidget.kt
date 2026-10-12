package io.github.svyyyaaat.ankiwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalGlanceId
import androidx.glance.LocalSize
import androidx.glance.action.Action as GlanceAction
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jsoup.Jsoup

class AnkiWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AnkiWidget()

    /** Periodic updates also fetch fresh data, so reviews done in AnkiDroid show up here. */
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                val manager = GlanceAppWidgetManager(context)
                for (id in appWidgetIds) WidgetController.refreshIfIdle(context, manager.getGlanceIdBy(id))
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

class AnkiWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetController.ensureLoaded(context, id)
        provideContent {
            val prefs = currentState<Preferences>()
            val palette = if (prefs[Keys.SYSTEM_COLORS] ?: true) Palette.system() else Palette.anki
            WidgetContent(prefs, palette)
        }
    }
}

/** The handful of colours the widget uses, either from the system theme or Anki's classic look. */
class Palette(
    val background: ColorProvider,
    val text: ColorProvider,
    val subtle: ColorProvider,
    val again: ColorProvider,
    val hard: ColorProvider,
    val good: ColorProvider,
    val easy: ColorProvider,
    val button: ColorProvider,
    val iconBackground: ColorProvider,
    val iconForeground: ColorProvider,
) {
    // Anki's convention: new = blue (like Easy), learning = red (like Again), review = green (like Good).
    val countNew get() = easy
    val countLearn get() = again
    val countReview get() = good

    companion object {
        /** Material You colours on Android 12+, Material baseline colours before that. */
        @Composable
        fun system(): Palette {
            val c = GlanceTheme.colors
            return Palette(
                background = c.widgetBackground,
                text = c.onSurface,
                subtle = c.onSurfaceVariant,
                again = c.error,
                hard = c.onSurfaceVariant,
                good = c.primary,
                easy = c.tertiary,
                button = c.surfaceVariant,
                iconBackground = c.secondaryContainer,
                iconForeground = c.onSecondaryContainer,
            )
        }

        /** Anki's own reviewer colours, still following light/dark mode. */
        val anki = Palette(
            background = ColorProvider(day = Color(0xFFFAFAFA), night = Color(0xFF000000)),
            text = ColorProvider(day = Color(0xFF000000), night = Color(0xFFFFFFFF)),
            subtle = ColorProvider(day = Color(0xFF757575), night = Color(0xFFAAAAAA)),
            again = ColorProvider(day = Color(0xFFD32F2F), night = Color(0xFFE5675D)),
            hard = ColorProvider(day = Color(0xFF000000), night = Color(0xFFFFFFFF)),
            good = ColorProvider(day = Color(0xFF388E3C), night = Color(0xFF7CC277)),
            easy = ColorProvider(day = Color(0xFF1976D2), night = Color(0xFF4C8BE0)),
            button = ColorProvider(day = Color(0xFFEDEDED), night = Color(0xFF1C1C1C)),
            iconBackground = ColorProvider(day = Color(0xFFE3E3E3), night = Color(0xFF262626)),
            iconForeground = ColorProvider(day = Color(0xFF424242), night = Color(0xFFDDDDDD)),
        )
    }
}

private val OUTER_PADDING = 10.dp
private val TOP_BAR = 30.dp
private val BOTTOM_BAR = 50.dp
private val HINT_BAR = 28.dp
private val GAP = 6.dp

private fun action(name: String, ease: Int = 0): GlanceAction =
    actionRunCallback<WidgetActionCallback>(
        actionParametersOf(WidgetActionCallback.ACTION to name, WidgetActionCallback.EASE to ease),
    )

@Composable
private fun WidgetContent(prefs: Preferences, palette: Palette) {
    val bg = GlanceModifier.fillMaxSize().appWidgetBackground().background(palette.background)
        .let { if (Build.VERSION.SDK_INT >= 31) it.cornerRadius(android.R.dimen.system_app_widget_background_radius) else it }
        .padding(OUTER_PADDING)
    Box(modifier = bg) {
        when (prefs[Keys.STATUS]) {
            Status.CARD -> CardContent(prefs, palette)
            Status.DONE -> Message(
                prefs, palette,
                cardIcons = true,
                title = "All done for now",
                hint = "Tap to check again",
                onClick = action(Action.REFRESH),
            )
            Status.NOT_INSTALLED -> Message(
                prefs, palette,
                title = "AnkiDroid isn't installed",
                hint = "Tap to get it from the Play Store",
                onClick = actionStartActivity(AnkiDroid.playStoreIntent()),
            )
            Status.NO_PERMISSION -> Message(
                prefs, palette,
                title = "Needs access to AnkiDroid",
                hint = "Tap to set up",
                onClick = actionStartActivity(configIntent()),
            )
            Status.ERROR -> Message(
                prefs, palette,
                title = "Couldn't reach AnkiDroid",
                hint = (prefs[Keys.MESSAGE]?.let { "$it\n" } ?: "") + "Tap to retry",
                onClick = action(Action.REFRESH),
            )
            else -> Message(prefs, palette, cardIcons = true, title = "Loading…", hint = null, onClick = action(Action.REFRESH))
        }
    }
}

@Composable
private fun configIntent(): Intent {
    val context = LocalContext.current
    val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(LocalGlanceId.current)
    return Intent(context, ConfigActivity::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

@Composable
private fun Message(
    prefs: Preferences,
    palette: Palette,
    title: String,
    hint: String?,
    onClick: GlanceAction,
    cardIcons: Boolean = false,
) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        // No card on screen, but Back can still undo the last answer.
        TopBar(prefs, palette, back = cardIcons, suspend = false)
        Column(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight().clickable(onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = TextStyle(color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
            )
            if (hint != null) {
                Spacer(GlanceModifier.height(4.dp))
                Text(hint, style = TextStyle(color = palette.subtle, fontSize = 12.sp, textAlign = TextAlign.Center))
            }
        }
    }
}

@Composable
private fun TopBar(prefs: Preferences, palette: Palette, back: Boolean, suspend: Boolean) {
    val counts = displayedCounts(prefs)?.takeIf { prefs[Keys.SHOW_COUNTS] ?: true }
    if (!back && !suspend && counts == null) return
    Row(modifier = GlanceModifier.fillMaxWidth().height(TOP_BAR), verticalAlignment = Alignment.CenterVertically) {
        if (back) {
            // On the answer side: back to the question. Otherwise: undo the last answer, if any.
            val canGoBack = (prefs[Keys.SIDE] == Side.ANSWER && Slots.CURRENT.read(prefs) != null) ||
                Slots.PENDING.read(prefs) != null
            CircleIcon(R.drawable.ic_back, "Go back", palette, action(Action.BACK).takeIf { canGoBack })
        }
        if (suspend) {
            Spacer(GlanceModifier.width(6.dp))
            CircleIcon(R.drawable.ic_suspend, "Suspend card", palette, action(Action.SUSPEND))
        }
        Spacer(GlanceModifier.defaultWeight())
        if (counts != null) {
            Count(counts.new, palette.countNew)
            Count(counts.learn, palette.countLearn)
            Count(counts.review, palette.countReview)
        }
    }
}

@Composable
private fun Count(n: Int, color: ColorProvider) {
    Text(
        n.toString(),
        modifier = GlanceModifier.padding(start = 8.dp),
        style = TextStyle(color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
private fun CircleIcon(icon: Int, description: String, palette: Palette, onClick: GlanceAction?) {
    val modifier = GlanceModifier.size(TOP_BAR).let { if (onClick != null) it.clickable(onClick) else it }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Image(
            provider = ImageProvider(R.drawable.circle),
            contentDescription = null,
            modifier = GlanceModifier.size(TOP_BAR),
            colorFilter = ColorFilter.tint(palette.iconBackground),
        )
        Image(
            provider = ImageProvider(icon),
            contentDescription = description,
            modifier = GlanceModifier.size(16.dp),
            // Dimmed when there's nothing to go back to.
            colorFilter = ColorFilter.tint(if (onClick != null) palette.iconForeground else palette.subtle),
        )
    }
}

@Composable
private fun CardContent(prefs: Preferences, palette: Palette) {
    val onAnswer = prefs[Keys.SIDE] == Side.ANSWER
    val simple = prefs[Keys.SIMPLE_CONTROLS] ?: false
    val bottom: Dp = if (simple) HINT_BAR else BOTTOM_BAR
    val size = LocalSize.current
    val cardWidth = size.width - OUTER_PADDING * 2
    val cardHeight = size.height - OUTER_PADDING * 2 - TOP_BAR - bottom - GAP * 2
    val card = Slots.CURRENT.read(prefs)
    val html = (if (onAnswer) card?.answer else card?.question).orEmpty()
    val times = card?.nextTimes.orEmpty()

    Column(modifier = GlanceModifier.fillMaxSize()) {
        TopBar(prefs, palette, back = true, suspend = true)
        Spacer(GlanceModifier.height(GAP))
        Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                CardImage(html, card?.css.orEmpty(), cardWidth, cardHeight, palette, GlanceModifier.fillMaxWidth().defaultWeight())
                Spacer(GlanceModifier.height(GAP))
                when {
                    simple && !onAnswer -> HintRow("Tap to show answer", null, palette)
                    simple -> HintRow("‹ Again  ${times.getOrNull(0).orEmpty()}", "Good  ${times.getOrNull(2).orEmpty()} ›", palette)
                    !onAnswer -> Pill(action(Action.SHOW_ANSWER), palette, GlanceModifier.fillMaxWidth()) {
                        Text("Show answer", style = TextStyle(color = palette.text, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                    }
                    else -> AnswerButtons(times, palette)
                }
            }
            // Touch targets laid over the card.
            if (!onAnswer) {
                Box(modifier = GlanceModifier.fillMaxSize().clickable(action(Action.SHOW_ANSWER))) {}
            } else if (simple) {
                Row(modifier = GlanceModifier.fillMaxSize()) {
                    Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight().clickable(action(Action.ANSWER, 1))) {}
                    Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight().clickable(action(Action.ANSWER, 3))) {}
                }
            }
        }
    }
}

@Composable
private fun CardImage(html: String, css: String, width: Dp, height: Dp, palette: Palette, modifier: GlanceModifier) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val bitmap = CardRenderer.render(html, css, (width.value * density).toInt(), (height.value * density).toInt(), density)
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = Jsoup.parse(html).text(),
        modifier = modifier,
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(palette.text),
    )
}

@Composable
private fun HintRow(left: String, right: String?, palette: Palette) {
    Row(modifier = GlanceModifier.fillMaxWidth().height(HINT_BAR), verticalAlignment = Alignment.CenterVertically) {
        if (right == null) {
            Text(
                left,
                modifier = GlanceModifier.fillMaxWidth(),
                style = TextStyle(color = palette.subtle, fontSize = 13.sp, textAlign = TextAlign.Center),
            )
        } else {
            Text(
                left,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = palette.again, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            )
            Text(
                right,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = palette.good, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End),
            )
        }
    }
}

@Composable
private fun AnswerButtons(times: List<String>, palette: Palette) {
    val labels = listOf("Again" to palette.again, "Hard" to palette.hard, "Good" to palette.good, "Easy" to palette.easy)
    Row(modifier = GlanceModifier.fillMaxWidth().height(BOTTOM_BAR)) {
        labels.forEachIndexed { i, (label, color) ->
            if (i > 0) Spacer(GlanceModifier.width(4.dp))
            Pill(action(Action.ANSWER, i + 1), palette, GlanceModifier.defaultWeight()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    times.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = TextStyle(color = color, fontSize = 11.sp))
                    }
                    Text(label, style = TextStyle(color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                }
            }
        }
    }
}

@Composable
private fun Pill(onClick: GlanceAction, palette: Palette, modifier: GlanceModifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.height(BOTTOM_BAR).background(palette.button).cornerRadius(14.dp).clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
