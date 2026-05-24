# APP_MAP

## 1) App purpose (high level)
ScreenTrans is an Android app that captures the screen (MediaProjection), runs OCR on captured regions/fullscreen, sends text blocks to OpenAI-compatible LLM providers for translation, and draws translated overlays above original content.

## 2) Main modules/packages
- `app/src/main/kotlin/io/github/acr994/livetranslate/`: core app logic (capture, OCR, translation, overlay, settings, services).
- `app/src/main/kotlin/io/github/acr994/livetranslate/ui/theme/`: Compose theme tokens.
- `app/src/main/res/`: Android resources (strings, themes, icons, XML rules).
- `app/src/main/assets/`: OCR models/dictionaries (`*.onnx`, `dict.txt`).

## 3) Key files and responsibilities
- `MainActivity.kt`: app entry UI, permission/status entry points.
- `SettingsActivity.kt`: settings UI, including LLM/OCR/overlay related knobs.
- `ScreenTransApplication.kt`: app-level initialization.
- `QuickSettingsService.kt`: quick settings tile integration.
- `LogManager.kt`, `LogActivity.kt`: in-app log collection/display.
- `StatisticsActivity.kt`, `TokenStatsManager.kt`: usage/token statistics.

## 4) Settings / data persistence
- `AppSettings.kt`: settings schema/defaults and migration helpers.
- `PreferenceManager.kt`: DataStore read/write and settings flow.
- `SettingsActivity.kt`: binds UI controls to persisted settings.

## 5) OCR-related files
- `OcrEngine.kt`: ONNX OCR inference pipeline orchestration.
- `OcrEntities.kt`: OCR data models.
- `OcrPostProcessor.kt`: OCR post-processing/cleanup.
- Assets: `app/src/main/assets/det_mobile.onnx`, `rec_mobile.onnx`, `dict.txt`.

## 6) LLM / provider / translation files
- `LlmProviderRegistry.kt`: provider definitions, base URL/default/fallback model routing.
- `TranslationEngine.kt`: `/chat/completions` translation calls and `/models` discovery handling.
- `AppSettings.kt` + `PreferenceManager.kt`: persisted provider/apiKey/model/baseUrl/systemPrompt/targetLanguage.
- `TokenStatsManager.kt`: token/cost accounting.

## 7) Overlay files
- `OverlayManager.kt`: overlay lifecycle, attach/remove/update flow.
- `OverlayView.kt`: translated text block rendering logic.
- `RegionSelectionView.kt`: drag-select / region UI for capture area.
- `FontUtils.kt`: font handling used by overlay text rendering.

## 8) MediaProjection / service / permission files
- `FloatingService.kt`: foreground service, floating ball, projection lifecycle, capture trigger.
- `ProjectionProxyActivity.kt`: transparent permission bridge for MediaProjection re-authorization.
- `ScreenCaptureHelper.kt`: `ImageReader` -> `Bitmap` extraction.
- `MainActivity.kt`: startup permission checks and service start flow.
- `app/src/main/AndroidManifest.xml`: service/activity declarations and sensitive permissions.

## 9) Build / Gradle files
- Root: `settings.gradle`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`.
- App module: `app/build.gradle.kts`.
- Wrapper: `gradle/wrapper/gradle-wrapper.properties`, `gradlew`, `gradlew.bat`.

## 10) Common change routing
- If changing **LLM providers**, inspect: `LlmProviderRegistry.kt`, `TranslationEngine.kt`, `SettingsActivity.kt`, `AppSettings.kt`, `PreferenceManager.kt`.
- If changing **OCR**, inspect: `OcrEngine.kt`, `OcrPostProcessor.kt`, `OcrEntities.kt`, `FloatingService.kt`, OCR assets under `app/src/main/assets/`.
- If changing **overlay UI**, inspect: `OverlayManager.kt`, `OverlayView.kt`, `RegionSelectionView.kt`, `FontUtils.kt`, related settings in `AppSettings.kt`.
- If changing **permissions / projection / foreground service**, inspect: `AndroidManifest.xml`, `MainActivity.kt`, `FloatingService.kt`, `ProjectionProxyActivity.kt`, `QuickSettingsService.kt`.
- If changing **Gradle/build**, inspect: `app/build.gradle.kts`, root `build.gradle.kts`, `settings.gradle`, `gradle/libs.versions.toml`, wrapper files.

## 11) Known files not to touch unless required
- `app/src/main/assets/*.onnx` and `dict.txt` (model artifacts).
- `app/src/main/AndroidManifest.xml` (permissions/service behavior sensitive).
- `app/build.gradle.kts` and `gradle/libs.versions.toml` (toolchain/dependency stability sensitive).
- `FloatingService.kt`, `ProjectionProxyActivity.kt`, `OverlayView.kt`, `OcrEngine.kt` (core runtime path; high regression risk).

## 12) Last updated
- 2026-05-24
