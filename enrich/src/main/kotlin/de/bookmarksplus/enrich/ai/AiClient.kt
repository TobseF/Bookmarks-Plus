package de.bookmarksplus.enrich.ai

import de.bookmarksplus.i18n.tr
import de.bookmarksplus.enrich.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Duration

/** Provider with OpenAI-compatible chat API. */
enum class AiProvider(private val fixedLabel: String?, val baseUrl: String, val defaultModel: String, val defaultTagModel: String, val keyUrl: String?) {
    OpenRouter("OpenRouter", "https://openrouter.ai/api/v1", "google/gemini-2.5-flash-lite", "google/gemini-2.5-flash-lite", "https://openrouter.ai/settings/keys"),
    NanoGpt("nano-gpt", "https://nano-gpt.com/api/v1", NANO_TEXT_MODEL, NANO_TAG_MODEL, "https://nano-gpt.com/api"),
    Custom(null, "http://localhost:11434/v1", "llama3.2", "llama3.2", null);

    val label: String get() = fixedLabel ?: tr("enrich.ai.customUrl")
}

/** Default models for nano-gpt (result of the price/performance comparison, see docs/AI-MODELLE.md). */
const val NANO_TEXT_MODEL = "deepseek/deepseek-v4.1-flash"
const val NANO_TAG_MODEL = "deepseek/deepseek-v4.1-flash"

data class AiConfig(
    val provider: AiProvider,
    val apiKey: String,
    val model: String,
    val customBaseUrl: String? = null,
) {
    val baseUrl: String get() =
        ((if (provider == AiProvider.Custom) customBaseUrl?.takeIf { it.isNotBlank() } else null) ?: provider.baseUrl).trimEnd('/')
    val usable: Boolean get() = model.isNotBlank() && (apiKey.isNotBlank() || provider == AiProvider.Custom)
}

data class ChatResult(val text: String, val promptTokens: Long, val completionTokens: Long, val cost: Double?)

data class AiModel(
    val id: String,
    val name: String,
    /** Price per token in USD (input/output), if known. */
    val promptPrice: Double?,
    val completionPrice: Double?,
    /** Supported levels for `reasoning_effort` (nano-gpt), empty = unknown. */
    val reasoningEfforts: List<String> = emptyList(),
) {
    /** Turn thinking off or minimize it – unnecessary and expensive for summarizing and tagging. */
    val cheapestEffort: String? get() = listOf("none", "minimal", "low").firstOrNull { it in reasoningEfforts }

    /** Price per 1M input tokens in USD, if known. */
    val pricePerMillion: Double? get() = promptPrice?.times(1_000_000)
}

class AiException(message: String, val status: Int, val retryable: Boolean) : Exception(message)

/** Lean client for `/chat/completions` and `/models` (OpenRouter, nano-gpt, Ollama/LM Studio …). */
class AiClient(private val http: Http = Http()) {

    private val json = Json { ignoreUnknownKeys = true }

    fun chat(cfg: AiConfig, system: String, user: String, maxTokens: Int = 900, temperature: Double = 0.2, reasoningEffort: String? = null): ChatResult {
        val body = buildJsonObject {
            put("model", cfg.model.trim())
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", system) }
                addJsonObject { put("role", "user"); put("content", user) }
            }
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            reasoningEffort?.let { put("reasoning_effort", it) }
            if (cfg.provider == AiProvider.OpenRouter) putJsonObject("usage") { put("include", true) }
        }
        val res = http.post("${cfg.baseUrl}/chat/completions", body.toString(), headers(cfg), timeout = Duration.ofSeconds(90))
        if (!res.ok) throw error(res.status, res.text())
        val root = runCatching { json.parseToJsonElement(res.text().orEmpty()).jsonObject }
            .getOrElse { throw AiException(tr("enrich.ai.notJson"), res.status, true) }
        root["error"]?.let { e -> throw AiException(message(e) ?: tr("enrich.ai.providerError"), res.status, true) }
        val msg = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
        val content = when (val c = msg?.get("content")) {
            is JsonPrimitive -> c.contentOrNull
            is JsonArray -> c.joinToString("") { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty() }
            else -> null
        }.orEmpty()
        val usage = root["usage"] as? JsonObject
        return ChatResult(
            text = content,
            promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.longOrNull ?: 0,
            completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.longOrNull ?: 0,
            cost = usage?.get("cost")?.jsonPrimitive?.doubleOrNull,
        )
    }

    /** Available models of the provider (with prices, if delivered). */
    fun models(cfg: AiConfig): List<AiModel> {
        // nano-gpt delivers prices and reasoning levels only with ?detailed=true
        val query = if (cfg.provider == AiProvider.NanoGpt) "?detailed=true" else ""
        val res = http.get("${cfg.baseUrl}/models$query", maxBytes = 16 shl 20, accept = "application/json", extraHeaders = headers(cfg))
        if (!res.ok) throw error(res.status, res.text())
        val data = json.parseToJsonElement(res.text().orEmpty()).jsonObject["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val pricing = o["pricing"] as? JsonObject
            // OpenRouter: USD per token; nano-gpt: USD per million tokens ("unit": "per_million_tokens")
            val perMillion = (pricing?.get("unit") as? JsonPrimitive)?.contentOrNull?.contains("million") == true
            fun price(k: String) = (pricing?.get(k) as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
                ?.let { if (perMillion) it / 1_000_000 else it }
            val efforts = (o["reasoning_efforts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            AiModel(id, o["name"]?.jsonPrimitive?.contentOrNull ?: id, price("prompt"), price("completion"), efforts)
        }.sortedBy { it.id.lowercase() }
    }

    private fun headers(cfg: AiConfig): Map<String, String> = buildMap {
        if (cfg.apiKey.isNotBlank()) put("Authorization", "Bearer ${cfg.apiKey.trim()}")
        put("Accept", "application/json")
        if (cfg.provider == AiProvider.OpenRouter) {
            put("HTTP-Referer", "https://bookmarks-plus.local")
            put("X-Title", "Bookmarks Plus")
        }
    }

    private fun error(status: Int, body: String?): AiException {
        val detail = body?.let { runCatching { json.parseToJsonElement(it).jsonObject["error"]?.let(::message) }.getOrNull() }
            ?: body?.take(200)?.takeIf { it.isNotBlank() }
        val text = when (status) {
            0 -> tr("enrich.ai.unreachable")
            401, 403 -> tr("enrich.ai.invalidKey")
            402 -> tr("enrich.ai.noCredit")
            404 -> tr("enrich.ai.notFound")
            429 -> tr("enrich.ai.rateLimit")
            else -> "HTTP $status"
        }
        return AiException(if (detail != null && status != 0) "$text: $detail" else text, status, status == 0 || status == 429 || status >= 500)
    }

    private fun message(e: kotlinx.serialization.json.JsonElement): String? = when (e) {
        is JsonPrimitive -> e.contentOrNull
        is JsonObject -> e["message"]?.jsonPrimitive?.contentOrNull
        else -> null
    }
}
