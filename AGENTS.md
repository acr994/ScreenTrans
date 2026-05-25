# AGENTS.md

## Project

This repository is an Android screen translation app.

The app captures the screen with MediaProjection, detects text with OCR, sends text blocks to an LLM translation provider, and renders translated text as an overlay.

The current target is to keep the app stable, modular, private, and compatible with modern Android restrictions.

## Repository map

Before modifying code, read `APP_MAP.md` when present.

Use `APP_MAP.md` as a routing guide to find the relevant files faster, not as a perfect source of truth.

Do not scan the whole repository unless the task requires it.

If a task reveals that `APP_MAP.md` is outdated, update only the relevant section.

## Core rules

- Read this file before making changes.
- Read `APP_MAP.md` at repository root before editing (update only relevant sections when architecture/routes change).
- Inspect the existing code before editing.
- Prefer small, reviewable patches.
- Do not rewrite unrelated parts of the app.
- Do not change OCR, overlay, MediaProjection, foreground service, or permission logic unless the task explicitly requires it.
- Preserve existing behavior unless the user explicitly asks for a behavior change.
- Explain risky changes before applying them.
- Do not hardcode API keys, tokens, secrets, private endpoints, or user credentials.
- Do not log API keys, OCR text, prompts, translation responses, clipboard contents, or private screen content in release builds.
- Avoid adding new dependencies unless clearly necessary.
- Keep compatibility with Android foreground service, overlay, notification, and MediaProjection restrictions.

## Android-specific rules

- Be careful with Android 13, Android 14, Android 15, and Android 16 behavior.
- Be careful with MIUI, HyperOS, ColorOS, One UI, and other aggressive battery/background restrictions.
- If changing AndroidManifest.xml, permissions, foreground service types, targetSdk, Gradle config, or notification behavior, explain the user-visible consequence.
- If changing overlay behavior, verify that touches, visibility, rotation, and permission recovery still work.
- If changing MediaProjection behavior, verify permission recovery after app kill, service restart, and screen rotation.
- If changing OCR behavior, verify memory usage and latency.

## Architecture expectations

Keep logic separated by responsibility:

- Settings UI should only manage UI state and persistence calls.
- Translation provider resolution should live in a provider registry or dedicated provider module.
- TranslationEngine should translate and fetch models, not hardcode UI decisions.
- OCR logic should stay isolated from LLM provider logic.
- Overlay rendering should stay isolated from translation provider logic.
- Permissions and lifecycle recovery should not be mixed with provider-specific code.

Avoid making huge classes larger unless absolutely necessary. If adding significant logic, prefer small helper classes or files.

## LLM provider system

The app should support OpenAI-compatible providers through a provider registry.

Use a structure similar to:

data class LlmProvider(
    val id: String,
    val displayName: String,
    val baseUrl: String?,
    val supportsModelFetch: Boolean = true,
    val defaultModel: String,
    val fallbackModels: List<String>
)

The provider registry should include at least:

- DeepSeek
- OpenAI
- OpenRouter
- Groq
- Mistral
- Together AI
- SiliconFlow
- Custom OpenAI-compatible endpoint

Normal providers must resolve their Base URL internally.

The Base URL field must not be visible for normal providers.

The Base URL field should appear only when the selected provider is Custom OpenAI-compatible endpoint.

Do not implement native Anthropic, Gemini, Claude, or non-OpenAI-compatible APIs unless the user explicitly asks for a separate provider-adapter task.

For now, preserve OpenAI-compatible behavior:

- POST /chat/completions for translation.
- GET /models for model discovery when supported.

## LLM settings UI rules

In Settings > LLM, the preferred UI is:

Provider:
- Dropdown.

API Key:
- Text field.
- Do not erase the saved key when provider changes.

Model:
- Dropdown whose values depend on the selected provider.
- Use live /models results when possible.
- Use fallback models when /models fails.
- Allow manual model entry only for Custom provider or when there is already existing manual-entry behavior.

Target Language:
- Keep existing behavior.

System Prompt:
- Keep existing behavior.

Base URL:
- Hidden by default.
- Visible only for Custom OpenAI-compatible endpoint.

Save:
- Must persist provider, API key, model, target language, system prompt, and custom Base URL when applicable.

## Provider/model behavior

When provider changes:

- Resolve provider Base URL internally.
- Refresh available models for that provider.
- If the current model is invalid for the new provider, select the provider default model.
- Do not erase API key.
- Do not erase target language.
- Do not erase system prompt.
- Do not erase custom Base URL unless the user explicitly edits it.

Model fetching:

- Prefer live model list from /models.
- Never block the settings screen indefinitely.
- Show loading state while fetching models if the UI supports it.
- Handle timeout, 401, 403, 404, 429, malformed JSON, empty response, and network errors gracefully.
- On failure, use fallbackModels.
- Show a readable error through existing UI patterns such as Toast, Snackbar, status text, or logs appropriate for debug builds.
- Do not leak secrets in errors or logs.

## Backward compatibility

Preserve old settings.

If existing settings contain baseUrl and modelName:

- If baseUrl matches DeepSeek, migrate providerId to deepseek.
- If baseUrl matches OpenAI, migrate providerId to openai.
- If baseUrl matches OpenRouter, migrate providerId to openrouter.
- If baseUrl matches Groq, migrate providerId to groq.
- If baseUrl matches Mistral, migrate providerId to mistral.
- If baseUrl matches Together AI, migrate providerId to together.
- If baseUrl matches SiliconFlow, migrate providerId to siliconflow.
- Otherwise, migrate providerId to custom and preserve the old baseUrl as customBaseUrl.

Do not break users who already saved a manual Base URL.

Do not silently discard modelName.

Do not silently discard API key.

## TranslationEngine rules

TranslationEngine should:

- Resolve Base URL from selected provider.
- Use customBaseUrl only when provider is Custom.
- Preserve current request payload behavior unless the task explicitly changes it.
- Preserve streaming behavior if currently implemented.
- Preserve token/cost statistics if currently implemented.
- Handle HTTP errors cleanly.
- Handle malformed provider responses cleanly.
- Avoid crashing on empty translation output.
- Avoid logging private text in release builds.

## Security and privacy

Screen content is sensitive.

Do not log:

- OCR text.
- Raw screenshots.
- Cropped screenshots.
- Translation prompts.
- Translation responses.
- API keys.
- Authorization headers.
- Clipboard contents.
- Full request bodies.
- Full response bodies.

API keys should not be stored in plain text if a secure storage abstraction already exists.

If secure storage does not exist, propose Android Keystore or EncryptedSharedPreferences as a follow-up, but do not perform a large storage migration unless requested.

## Testing and validation

After code changes, try to run:

./gradlew assembleDebug
./gradlew test
./gradlew lint

If these commands cannot run, report exactly why.

Always report:

- Files changed.
- Summary of changes.
- Build/test/lint result.
- Manual test checklist.
- Known limitations.

## Manual test checklist

For LLM provider/settings changes, verify:

- Settings > LLM opens correctly.
- Base URL is hidden by default.
- Provider dropdown appears.
- DeepSeek provider can be selected.
- OpenAI provider can be selected.
- OpenRouter provider can be selected.
- Groq provider can be selected.
- Mistral provider can be selected.
- Together AI provider can be selected.
- SiliconFlow provider can be selected.
- Custom provider can be selected.
- Base URL appears only for Custom provider.
- Model dropdown changes when provider changes.
- Model fallback list appears if /models fails.
- API key is preserved when changing provider.
- Model selection persists after Save.
- Provider selection persists after app restart.
- Custom Base URL persists after app restart.
- Translation uses the resolved provider Base URL.
- Invalid API key produces a readable error.
- Network failure produces a readable error.
- No private OCR text or API key appears in release logs.

## Kotlin style

- Keep code simple.
- Prefer explicit names.
- Avoid clever abstractions unless they reduce real duplication.
- Avoid broad try/catch blocks that hide errors.
- Avoid global mutable state unless already used consistently in the project.
- Keep suspend/coroutine behavior lifecycle-safe.
- Avoid blocking the main thread.
- Do network requests off the main thread.

## Gradle and dependency rules

- Do not upgrade Gradle, AGP, Kotlin, Compose, Material, ONNX Runtime, or Android SDK versions unless the task requires it.
- Do not add new libraries for a simple dropdown/provider registry.
- If a dependency is added, explain why existing tools were insufficient.

## Done criteria

A task is done only when:

- The requested change is implemented.
- Backward compatibility is considered.
- Error handling is considered.
- Privacy impact is considered.
- Build/test/lint status is reported.
- Manual testing steps are provided.
- No unrelated files are modified unnecessarily.
