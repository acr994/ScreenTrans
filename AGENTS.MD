## Project overview

This is an Android app for screen translation using MediaProjection, OCR, translation providers, and overlay rendering.

## Main rules

- Do not rewrite large parts of the app unless explicitly requested.
- Prefer small, reviewable patches.
- Preserve existing architecture unless there is a clear reason to refactor.
- Before changing behavior, inspect the relevant files first.
- Explain risky Android permission, overlay, MediaProjection, foreground service, or battery-optimization changes before applying them.
- Avoid adding new dependencies unless necessary.
- Do not hardcode API keys, tokens, endpoints, or private credentials.
- Never log OCR text, API keys, prompts, full translation responses, or clipboard contents in release builds.

## Android-specific rules

- Keep compatibility with modern Android restrictions around MediaProjection, foreground services, notification permissions, and overlay permissions.
- Be careful with MIUI/HyperOS behavior, background restrictions, floating windows, and battery optimization.
- If changing Gradle, AndroidManifest.xml, services, permissions, or targetSdk behavior, explain the consequence.

## Build and verification

- After code changes, check Gradle sync implications.
- Prefer running:
  - ./gradlew assembleDebug
  - ./gradlew test
  - ./gradlew lint
- If commands cannot be run, explain why and say what should be tested manually.

## Coding style

- Keep Kotlin/Java code readable and minimal.
- Avoid massive classes when adding new logic.
- Prefer separating OCR, translation, overlay, permissions, and settings logic.
- Add comments only where behavior is non-obvious.

## Translation provider rules

- Translation providers should be modular.
- Do not assume only one provider exists.
- New providers should not break existing OpenAI-compatible APIs.
- Handle HTTP errors, rate limits, invalid API keys, timeouts, and malformed responses cleanly.

## Done criteria

A task is done only when:
- The change is implemented.
- Important edge cases are considered.
- Build/test/lint status is reported.
- User-facing behavior is explained.
