package com.angylabs.gyglass.ai

import android.content.Context
import android.util.Base64
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.TextBlockParam
import com.angylabs.gyglass.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

enum class AiProvider(val label: String, val defaultModel: String, val supportsImages: Boolean) {
    GEMINI("Gemini (Google)", "gemini-4-hydrogen", true),
    CLAUDE("Claude (Anthropic)", "claude-opus-5-5", true),
    DEEPSEEK("DeepSeek", "deepseek-flash", true)
}

class AiSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("gyglass_ai", Context.MODE_PRIVATE)

    fun key(p: AiProvider): String = prefs.getString("key_${p.name}", "") ?: ""
    fun model(p: AiProvider): String {
        val stored = prefs.getString("model_${p.name}", p.defaultModel)?.ifBlank { p.defaultModel } ?: p.defaultModel
        return if ((p == AiProvider.GEMINI && stored == "gemini-2.5-flash") || (p == AiProvider.DEEPSEEK && stored == "deepseek-chat")) p.defaultModel else stored
    }

    fun save(p: AiProvider, key: String, model: String) {
        prefs.edit().putString("key_${p.name}", key.trim()).putString("model_${p.name}", model.trim()).apply()
    }

    fun getString(name: String, def: String): String = prefs.getString(name, def) ?: def
    fun putString(name: String, value: String) {
        prefs.edit().putString(name, value).apply()
    }

    var defaultProvider: AiProvider
        get() = runCatching { AiProvider.valueOf(getString(KEY_DEFAULT_PROVIDER, AiProvider.DEEPSEEK.name)) }.getOrDefault(AiProvider.DEEPSEEK)
        set(v) = putString(KEY_DEFAULT_PROVIDER, v.name)

    companion object {
        const val KEY_DEFAULT_PROVIDER = "assistant_provider"
    }
}

class AiService(private val settings: AiSettings) {

    suspend fun test(p: AiProvider): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val key = settings.key(p)
            require(key.isNotBlank()) { "Falta el token/API key" }
            when (p) {
                AiProvider.CLAUDE -> testClaude(key)
                AiProvider.DEEPSEEK -> testDeepSeek(key)
                AiProvider.GEMINI -> testGemini(key)
            }
        }.onSuccess { AppLog.log("IA", "${p.label} prueba OK: $it") }
            .onFailure { AppLog.log("IA", "${p.label} prueba FALLO: ${it.message}") }
    }

    suspend fun transcribe(wav: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val key = settings.key(AiProvider.GEMINI)
            require(key.isNotBlank()) { "Para transcribir la voz se necesita la API key de Gemini" }
            val start = System.currentTimeMillis()
            val prompt = "Transcribe literalmente lo que dice la persona en este audio. Devuelve solo el texto transcrito, sin comentarios. Si no se entiende nada, devuelve una cadena vacía."
            val system = "Eres un transcriptor de voz preciso."
            val text = askGemini(key, settings.model(AiProvider.GEMINI), prompt, null, wav, system)
            AppLog.log("IA", "Transcripcion en ${System.currentTimeMillis() - start} ms: \"$text\"")
            text
        }.onFailure { AppLog.log("IA", "Transcripcion error: ${it.message}") }
    }

    suspend fun ask(
        p: AiProvider,
        prompt: String,
        jpeg: ByteArray?,
        wav: ByteArray? = null,
        modelOverride: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val key = settings.key(p)
            require(key.isNotBlank()) { "Falta el token/API key" }
            val model = modelOverride ?: settings.model(p)
            AppLog.log("IA", "${p.label} [$model] consulta: \"${prompt.take(200)}\" imagen=${jpeg?.size ?: 0} bytes audio=${wav?.size ?: 0} bytes")
            val start = System.currentTimeMillis()
            val answer = when (p) {
                AiProvider.CLAUDE -> askClaude(key, model, prompt, jpeg)
                AiProvider.DEEPSEEK -> askDeepSeek(key, model, prompt, jpeg)
                AiProvider.GEMINI -> askGemini(key, model, prompt, jpeg, wav)
            }
            AppLog.log("IA", "${p.label} respondio en ${System.currentTimeMillis() - start} ms: ${answer.take(300)}")
            answer
        }.onFailure { AppLog.log("IA", "${p.label} error: ${it.message}") }
    }

    private fun claudeClient(key: String) = AnthropicOkHttpClient.builder().apiKey(key).build()

    private fun testClaude(key: String): String {
        val client = claudeClient(key)
        try {
            val ids = client.models().list().data().map { it.id() }
            return "Conectado. Modelos disponibles: ${ids.take(6).joinToString()}"
        } finally {
            client.close()
        }
    }

    private fun askClaude(key: String, model: String, prompt: String, jpeg: ByteArray?): String {
        val client = claudeClient(key)
        try {
            val builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(SYSTEM_PROMPT)
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            if (jpeg != null) {
                val image = ImageBlockParam.builder()
                    .source(
                        Base64ImageSource.builder()
                            .data(Base64.encodeToString(jpeg, Base64.NO_WRAP))
                            .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                            .build()
                    )
                    .build()
                builder.addUserMessageOfContentBlockParams(
                    listOf(
                        ContentBlockParam.ofImage(image),
                        ContentBlockParam.ofText(TextBlockParam.builder().text(prompt).build())
                    )
                )
            } else {
                builder.addUserMessage(prompt)
            }
            val message = client.messages().create(builder.build())
            val stop = message.stopReason().map { it.toString() }.orElse("")
            if (stop.equals("refusal", ignoreCase = true)) {
                return "Claude rechazo la solicitud (refusal)."
            }
            val text = message.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("\n").trim()
            return text.ifEmpty { "(respuesta vacia, stop_reason=$stop)" }
        } finally {
            client.close()
        }
    }

    private fun testDeepSeek(key: String): String {
        val (code, body) = http("GET", "https://api.deepseek.com/models", mapOf("Authorization" to "Bearer $key"), null)
        check(code in 200..299) { "HTTP $code: ${body.take(300)}" }
        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
        val ids = (0 until data.length()).map { data.getJSONObject(it).optString("id") }
        return "Conectado. Modelos disponibles: ${ids.joinToString()}"
    }

    private fun askDeepSeek(key: String, model: String, prompt: String, jpeg: ByteArray?): String {
        val userContent: Any = if (jpeg != null) {
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP))
                    )
                )
        } else {
            prompt
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", userContent))
        val payload = JSONObject().put("model", model).put("messages", messages).put("stream", false)
        val (code, body) = http(
            "POST", "https://api.deepseek.com/chat/completions",
            mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"), payload.toString()
        )
        check(code in 200..299) { "DeepSeek HTTP $code${if (jpeg != null) " (con imagen)" else ""}: ${body.take(400)}" }
        val text = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        return text.trim()
    }

    private fun testGemini(key: String): String {
        val (code, body) = http("GET", "https://generativelanguage.googleapis.com/v1beta/models?pageSize=50", mapOf("x-goog-api-key" to key), null)
        check(code in 200..299) { "HTTP $code: ${body.take(300)}" }
        val models = JSONObject(body).optJSONArray("models") ?: JSONArray()
        val ids = (0 until models.length()).map { models.getJSONObject(it).optString("name").removePrefix("models/") }
            .filter { it.startsWith("gemini") }
        return "Conectado. Modelos disponibles: ${ids.take(8).joinToString()}"
    }

    private fun askGemini(
        key: String,
        model: String,
        prompt: String,
        jpeg: ByteArray?,
        wav: ByteArray? = null,
        system: String = SYSTEM_PROMPT
    ): String {
        val input = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        if (jpeg != null) {
            input.put(
                JSONObject()
                    .put("type", "image")
                    .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                    .put("mime_type", "image/jpeg")
            )
        }
        if (wav != null) {
            input.put(
                JSONObject()
                    .put("type", "audio")
                    .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))
                    .put("mime_type", "audio/wav")
            )
        }
        val payload = JSONObject()
            .put("model", model)
            .put("system_instruction", system)
            .put("input", input)
            .put("generation_config", JSONObject().put("max_output_tokens", 2048))
        val (code, body) = http(
            "POST", "https://generativelanguage.googleapis.com/v1beta/interactions",
            mapOf("x-goog-api-key" to key, "Content-Type" to "application/json", "Api-Revision" to GEMINI_API_REVISION),
            payload.toString()
        )
        if (code in 200..299) return parseInteraction(body)
        AppLog.log("IA", "Gemini Interactions HTTP $code: ${body.take(300)}. Probando generateContent")
        return askGeminiLegacy(key, model, prompt, jpeg, wav, system, "Interactions HTTP $code: ${body.take(200)}")
    }

    private fun parseInteraction(body: String): String {
        val json = JSONObject(body)
        val texts = mutableListOf<String>()
        val steps = json.optJSONArray("steps") ?: json.optJSONArray("outputs") ?: JSONArray()
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            val type = step.optString("type")
            if (type == "thought" || type == "function_call") continue
            val content = step.optJSONArray("content")
            if (content != null) {
                for (j in 0 until content.length()) {
                    val block = content.optJSONObject(j) ?: continue
                    if (block.optString("type", "text") == "text") block.optString("text").takeIf { it.isNotBlank() }?.let { texts += it }
                }
            } else if (step.optString("text").isNotBlank()) {
                texts += step.optString("text")
            }
        }
        if (texts.isEmpty() && json.optString("output_text").isNotBlank()) texts += json.optString("output_text")
        return texts.joinToString(LF).trim().ifEmpty { "(respuesta sin texto: ${body.take(300)})" }
    }

    private fun askGeminiLegacy(
        key: String,
        model: String,
        prompt: String,
        jpeg: ByteArray?,
        wav: ByteArray?,
        system: String,
        previousError: String
    ): String {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (jpeg != null) {
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                )
            )
        }
        if (wav != null) {
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "audio/wav").put("data", Base64.encodeToString(wav, Base64.NO_WRAP))
                )
            )
        }
        val payload = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        val (code, body) = http(
            "POST", "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            mapOf("x-goog-api-key" to key, "Content-Type" to "application/json"), payload.toString()
        )
        check(code in 200..299) { "$previousError | generateContent HTTP $code: ${body.take(300)}" }
        val candidates = JSONObject(body).optJSONArray("candidates") ?: return "(sin candidatos: ${body.take(300)})"
        val outParts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        return (0 until outParts.length()).joinToString(LF) { outParts.getJSONObject(it).optString("text") }.trim()
    }

    private fun http(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 120000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream: InputStream? = if (code >= 400) conn.errorStream else conn.inputStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        return code to text
    }

    companion object {
        const val GEMINI_API_REVISION = "2026-05-20"
        private const val LF = "\n"
        const val SYSTEM_PROMPT =
            "Eres el asistente de unas gafas inteligentes para una persona ciega o con baja visión. " +
                "Responde siempre en español, de forma breve, clara y fácil de escuchar por voz. " +
                "Si recibes una imagen, describe primero lo más importante para orientarse y la seguridad " +
                "(obstáculos, escalones, vehículos, personas cercanas), luego el texto visible y después el resto. " +
                "Usa referencias de reloj o izquierda/derecha y distancias aproximadas. No uses emojis ni formato markdown."
    }
}
