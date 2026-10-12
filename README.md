# AnkiWidget

An Android home-screen widget for reviewing your AnkiDroid cards.

It uses AnkiDroid's own on-device API (`com.ichi2.anki.flashcards`), so answers are recorded
straight into your AnkiDroid collection, exactly as if you'd reviewed in the app, and sync
as usual. No AnkiWeb access or second database.

## Features

- Question → tap → answer, with Again / Hard / Good / Easy and AnkiDroid's next intervals
- **Go back** (top-left): on the answer, returns to the question; after answering, opens
  AnkiDroid's reviewer where the last answer can be undone (the API has no undo call)
- **Suspend** (top-left): suspends the current card
- Card HTML rendered natively, including furigana (`<ruby>` and `漢字[かんじ]` syntax),
  font sizes/weights from the note type's CSS, and hidden elements; audio and images are skipped
- Per-widget settings (shown when the widget is added; long-press → settings to change):
  - deck (or AnkiDroid's current deck)
  - system colours (Material You + light/dark) or Anki's classic colours (still light/dark)
  - show new / learning / review counts
  - simplified controls: tap the left half for Again, right half for Good

![Renderer preview](docs/renderer-preview-dark.png)

## Setup

1. Install AnkiDroid and open it once.
2. Install this app, open it and allow access to AnkiDroid.
3. Add the "Anki review" widget from the home screen's widget list.

## Building

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # renderer tests; writes previews to app/build/preview/
```
