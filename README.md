# WBoard

Minimalist, lightweight, privacy-first Android keyboard (IME) with native in-app **Indonesian (`id-ID`) voice input** and **on-device Indonesian text prediction**.

## Privacy & Security Architecture

- **Zero Network Access**: `android.permission.INTERNET` is intentionally omitted from `AndroidManifest.xml`.
- **Zero Cloud Backups**: `android:allowBackup="false"`, `fullBackupContent`, and `dataExtractionRules` exclude all preferences and local files from cloud backup and device transfer.
- **Password & Incognito Shield (`PrivacyGuard`)**: Automatically disables text prediction, autocorrect, local word learning, and the voice microphone on password/PIN fields and honors `IME_FLAG_NO_PERSONALIZED_LEARNING` / incognito flags.
- **Strict Offline Voice Mode (Default ON)**: On Android 12+ (API 31+), `IndonesianVoiceController` uses `SpeechRecognizer.createOnDeviceSpeechRecognizer` for `id-ID` without falling back to cloud speech services unless the user disables Strict Offline Mode.
- **Opt-In Local Learning**: Word learning is disabled by default; when enabled, it stays in private app storage (`filesDir/user_lexicon.tsv`, capped at 500 words) and can be wiped in one tap.

## Features

- **Ultra-Lightweight UI**: Canvas-rendered `WBoardKeyboardView` and `SuggestionStripView` with zero bloated UI frameworks (~minimal APK footprint).
- **Native Indonesian Prediction (`IndonesianPredictionEngine`)**:
  - Trie-based prefix completion over curated Indonesian vocabulary (`assets/dict/id_unigrams.tsv`)
  - Bigram next-word prediction (`assets/dict/id_bigrams.tsv`)
  - Indonesian shorthand expansion (`yg` → `yang`, `tdk` → `tidak`, etc. via `assets/dict/id_shorthands.tsv`)
  - QWERTY-proximity weighted Damerau-Levenshtein typo correction with immediate Backspace undo
- **Native In-App Indonesian Voice Input**:
  - Inline listening bar with animated RMS audio level, `OFFLINE` / `ID` badge, and live partial transcription preview
  - Spoken Indonesian punctuation commands (`titik`, `koma`, `tanda tanya`, `tanda seru`, `baris baru`)
- **Ergonomics**:
  - Light, Dark, AMOLED Black, and System Auto themes
  - Spacebar horizontal swipe for cursor movement
  - Long-press top row for digits (`1`–`0`) and `.` for punctuation, plus optional dedicated number row

## Project Structure

- `app/src/main/java/com/wboard/ime/ime/` — `WBoardImeService`, `WBoardKeyboardView`, `SuggestionStripView`, `KeyboardLayouts`
- `app/src/main/java/com/wboard/ime/prediction/` — `IndonesianPredictionEngine`, `UserLexiconStore`
- `app/src/main/java/com/wboard/ime/voice/` — `IndonesianVoiceController`, `IndonesianVoicePostProcessor`
- `app/src/main/java/com/wboard/ime/privacy/` — `PrivacyGuard`
- `app/src/main/java/com/wboard/ime/prefs/` — `WBoardPrefs`
- `app/src/main/java/com/wboard/ime/ui/` — `MainActivity`, `SettingsActivity`, `KeyboardTheme`
- `app/src/main/assets/dict/` — Indonesian unigram, bigram, and shorthand TSV lexicons

## Build & Test

Open `/sdcard/Projects/WBoard` in Android Studio or build with an Android SDK + JDK 17 environment:

```bash
./gradlew testDebugUnitTest assembleDebug
```
