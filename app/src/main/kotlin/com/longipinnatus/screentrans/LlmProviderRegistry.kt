package com.longipinnatus.screentrans

data class LlmProvider(
    val id: String,
    val displayName: String,
    val baseUrl: String?,
    val supportsModelFetch: Boolean = true,
    val defaultModel: String,
    val fallbackModels: List<String>
)

object LlmProviderRegistry {
    const val PROVIDER_DEEPSEEK = "deepseek"
    const val PROVIDER_OPENAI = "openai"
    const val PROVIDER_OPENROUTER = "openrouter"
    const val PROVIDER_GROQ = "groq"
    const val PROVIDER_MISTRAL = "mistral"
    const val PROVIDER_TOGETHER = "together"
    const val PROVIDER_SILICONFLOW = "siliconflow"
    const val PROVIDER_CUSTOM = "custom"

    val providers: List<LlmProvider> = listOf(
        LlmProvider(PROVIDER_DEEPSEEK, "DeepSeek", "https://api.deepseek.com", true, "deepseek-chat", listOf("deepseek-chat", "deepseek-v4-flash")),
        LlmProvider(PROVIDER_OPENAI, "OpenAI", "https://api.openai.com/v1", true, "gpt-4o-mini", listOf("gpt-4o-mini", "gpt-4.1-mini")),
        LlmProvider(PROVIDER_OPENROUTER, "OpenRouter", "https://openrouter.ai/api/v1", true, "openai/gpt-4o-mini", listOf("openai/gpt-4o-mini", "deepseek/deepseek-chat-v3-0324:free")),
        LlmProvider(PROVIDER_GROQ, "Groq", "https://api.groq.com/openai/v1", true, "llama-3.3-70b-versatile", listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant")),
        LlmProvider(PROVIDER_MISTRAL, "Mistral", "https://api.mistral.ai/v1", true, "mistral-small-latest", listOf("mistral-small-latest", "mistral-large-latest")),
        LlmProvider(PROVIDER_TOGETHER, "Together AI", "https://api.together.xyz/v1", true, "meta-llama/Meta-Llama-3.1-8B-Instruct-Turbo", listOf("meta-llama/Meta-Llama-3.1-8B-Instruct-Turbo", "Qwen/Qwen2.5-7B-Instruct-Turbo")),
        LlmProvider(PROVIDER_SILICONFLOW, "SiliconFlow", "https://api.siliconflow.cn/v1", true, "Qwen/Qwen2.5-7B-Instruct", listOf("Qwen/Qwen2.5-7B-Instruct", "deepseek-ai/DeepSeek-V3")),
        LlmProvider(PROVIDER_CUSTOM, "Custom OpenAI-compatible endpoint", null, true, "gpt-4o-mini", listOf("gpt-4o-mini"))
    )

    private val providersById = providers.associateBy { it.id }

    fun getProvider(providerId: String): LlmProvider = providersById[providerId] ?: providers.first()

    fun resolveBaseUrl(settings: AppSettings.SettingsData): String {
        val provider = getProvider(settings.providerId)
        return (provider.baseUrl ?: settings.baseUrl).trimEnd('/')
    }

    fun inferProviderIdFromLegacyBaseUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/').lowercase()
        return when {
            normalized == "https://api.deepseek.com" -> PROVIDER_DEEPSEEK
            normalized == "https://api.openai.com/v1" || normalized == "https://api.openai.com" -> PROVIDER_OPENAI
            normalized.isBlank() -> PROVIDER_DEEPSEEK
            else -> PROVIDER_CUSTOM
        }
    }
}
