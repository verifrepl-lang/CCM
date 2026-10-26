package com.crabcode.ide.ai.providers

import kotlinx.serialization.Serializable

/** Same API families as desktop CrabCode: openai | anthropic | gemini | custom. */
typealias ProviderApi = String

@Serializable
data class StoredModel(
    val id: String,
    val label: String = id,
    val contextWindow: Int? = null,
    val vision: Boolean = false
)

@Serializable
data class ProviderConfig(
    val id: String,
    val catalogId: String,
    val name: String,
    val api: ProviderApi,
    val baseUrl: String,
    val models: List<StoredModel> = emptyList(),
    val activeModel: String? = null,
    val enabled: Boolean = true,
    val apiKeyVaultKey: String? = null   // secret lives in UserVault, never in this file
)

/**
 * Built-in provider catalog — mirrors the desktop catalog
 * (OpenAI, Anthropic, Gemini, OpenRouter, Mistral, DeepSeek, Groq, ...).
 */
object ProviderCatalog {
    val OPENAI = "https://api.openai.com/v1"
    val ANTHROPIC = "https://api.anthropic.com"
    val GEMINI = "https://generativelanguage.googleapis.com/v1beta"
    val OPENROUTER = "https://openrouter.ai/api/v1"
    val MISTRAL = "https://api.mistral.ai/v1"
    val DEEPSEEK = "https://api.deepseek.com/v1"
    val GROQ = "https://api.groq.com/openai/v1"
    val CEREBRAS = "https://api.cerebras.ai/v1"
    val TOGETHER = "https://api.together.xyz/v1"
    val FIREWORKS = "https://api.fireworks.ai/inference/v1"
    val PERPLEXITY = "https://api.perplexity.ai"
    val COHERE = "https://api.cohere.com/v2"
    val XAI = "https://api.x.ai/v1"
    val MOONSHOT = "https://api.moonshot.cn/v1"
    val GITHUB_MODELS = "https://models.inference.ai.azure.com"
    val HUGGINGFACE = "https://router.huggingface.co/v1"

    fun defaults(): List<ProviderConfig> = listOf(
        ProviderConfig("openai", "openai", "OpenAI", "openai", OPENAI, listOf(
            StoredModel("gpt-4.1", contextWindow = 1_000_000),
            StoredModel("gpt-4.1-mini", contextWindow = 1_000_000),
            StoredModel("o3-mini"), StoredModel("gpt-4o", vision = true), StoredModel("gpt-4o-mini", vision = true)
        )),
        ProviderConfig("anthropic", "anthropic", "Anthropic", "anthropic", ANTHROPIC, listOf(
            StoredModel("claude-sonnet-4-5", contextWindow = 200_000, vision = true),
            StoredModel("claude-opus-4-1", contextWindow = 200_000, vision = true),
            StoredModel("claude-haiku-4-5", contextWindow = 200_000, vision = true)
        )),
        ProviderConfig("google", "google", "Google Gemini", "gemini", GEMINI, listOf(
            StoredModel("gemini-2.5-pro", contextWindow = 1_000_000, vision = true),
            StoredModel("gemini-2.5-flash", contextWindow = 1_000_000, vision = true),
            StoredModel("gemini-2.0-flash", contextWindow = 1_000_000, vision = true)
        )),
        ProviderConfig("openrouter", "openrouter", "OpenRouter", "openai", OPENROUTER, listOf(
            StoredModel("openrouter/auto"), StoredModel("anthropic/claude-sonnet-4.5"),
            StoredModel("google/gemini-2.5-pro"), StoredModel("deepseek/deepseek-chat")
        )),
        ProviderConfig("mistral", "mistral", "Mistral AI", "openai", MISTRAL, listOf(
            StoredModel("mistral-large-latest"), StoredModel("codestral-latest"), StoredModel("mistral-small-latest")
        )),
        ProviderConfig("deepseek", "deepseek", "DeepSeek", "openai", DEEPSEEK, listOf(
            StoredModel("deepseek-chat", contextWindow = 128_000), StoredModel("deepseek-reasoner", contextWindow = 128_000)
        )),
        ProviderConfig("groq", "groq", "Groq", "openai", GROQ, listOf(
            StoredModel("llama-3.3-70b-versatile"), StoredModel("qwen/qwen3-32b"), StoredModel("moonshotai/kimi-k2-instruct")
        )),
        ProviderConfig("cerebras", "cerebras", "Cerebras", "openai", CEREBRAS, listOf(StoredModel("llama-3.3-70b"))),
        ProviderConfig("together", "together", "Together AI", "openai", TOGETHER, listOf(StoredModel("meta-llama/Llama-3.3-70B-Instruct-Turbo"))),
        ProviderConfig("fireworks", "fireworks", "Fireworks AI", "openai", FIREWORKS, listOf(StoredModel("accounts/fireworks/models/llama-v3p3-70b-instruct"))),
        ProviderConfig("perplexity", "perplexity", "Perplexity", "openai", PERPLEXITY, listOf(StoredModel("sonar-pro"), StoredModel("sonar"))),
        ProviderConfig("cohere", "cohere", "Cohere", "openai", COHERE, listOf(StoredModel("command-r-plus-08-2024"))),
        ProviderConfig("xai", "xai", "xAI", "openai", XAI, listOf(StoredModel("grok-4", vision = true), StoredModel("grok-3-mini"))),
        ProviderConfig("moonshot", "moonshot", "Moonshot AI", "openai", MOONSHOT, listOf(StoredModel("kimi-k2-0905-preview"))),
        ProviderConfig("github", "github", "GitHub Models", "openai", GITHUB_MODELS, listOf(StoredModel("gpt-4o"))),
        ProviderConfig("huggingface", "huggingface", "Hugging Face", "openai", HUGGINGFACE, listOf(StoredModel("deepseek-ai/DeepSeek-V3"))),
        // Free OpenCode-compatible endpoints (same spirit as shared/opencodeFreeModels)
        ProviderConfig("opencode", "opencode", "OpenCode (бесплатные модели)", "openai", "https://opencode.ai/zen/v1", listOf(
            StoredModel("code-gemma-2b-free"), StoredModel("qwen-coder-2.5-free"), StoredModel("devstral-small-free")
        ))
    )

    fun byCatalogId(id: String): ProviderConfig? = defaults().firstOrNull { it.catalogId == id }

    const val CUSTOM_TEMPLATE = "custom"
}
