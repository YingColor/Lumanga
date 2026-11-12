package com.yingcolor.lumanga.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException

/**
 * The HTTP side of translation: one place that knows how four different services want to be
 * asked, and how to say out loud why a request failed.
 *
 * Three failures of the first version are fixed here:
 *  - `192.168.1.10:8080` is not a URL — without a scheme it threw before a byte was sent, and the
 *    UI could only say "获取失败". A bare host is now treated as `http://`.
 *  - `{api}/chat/completions` misses the `/v1` that both OpenAI and llama.cpp expect, so a
 *    perfectly good server answered 404. A version segment is added when the address lacks one.
 *  - a 12 s read timeout killed local models, which routinely need longer than that on CPU.
 *
 * Every failure carries a short Chinese reason so the settings screen can distinguish a wrong key
 * from a closed port instead of blaming the user's network for everything.
 */
object ApiClient {
    private const val TAG = "ApiClient"

    private const val CONNECT_TIMEOUT = 8_000

    /**
     * A local model answering one page of bubbles can take well over a minute on CPU, and a
     * self-hosted server is exactly the setup this setting exists for.
     */
    private const val CHAT_TIMEOUT = 120_000
    private const val LIST_TIMEOUT = 15_000

    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val TEMPERATURE = 0.2
    private const val MAX_TOKENS = 1200

    /** `/v1`, `/v1beta`, `/v2` … anything that already looks like a version segment. */
    private val VERSION_TAIL = Regex("/v\\d+[a-z0-9]*$", RegexOption.IGNORE_CASE)

    private const val CHAT_SUFFIX = "/chat/completions"
    private const val GEMINI_SUFFIX = ":generateContent"

    sealed interface ChatResult {
        data class Ok(val text: String) : ChatResult
        data class Fail(val reason: String, val retryable: Boolean = false) : ChatResult
    }

    sealed interface ModelsResult {
        data class Ok(val ids: List<String>) : ModelsResult
        data class Fail(val reason: String) : ModelsResult
    }

    private sealed interface Raw {
        data class Ok(val body: String) : Raw
        data class Fail(
            val reason: String,
            val notFound: Boolean = false,
            val retryable: Boolean = false
        ) : Raw
    }

    fun chat(
        protocol: ApiProtocol,
        api: String,
        model: String,
        apiKey: String,
        system: String,
        user: String
    ): ChatResult {
        val base = baseOf(api)
        if (base.isEmpty()) return ChatResult.Fail("没填服务地址")
        if (model.isBlank()) return ChatResult.Fail("没填模型名称")
        val body = chatBody(protocol, model, system, user)
        var last = "请求失败"
        for (endpoint in chatEndpoints(protocol, base, model)) {
            when (val raw = request(protocol, endpoint, "POST", apiKey, body, CHAT_TIMEOUT)) {
                is Raw.Ok -> {
                    val content = extractContent(protocol, raw.body)
                    if (content.isNullOrBlank()) {
                        Log.w(TAG, "empty content $endpoint ${raw.body.take(300)}")
                        return ChatResult.Fail("接口返回里没有译文")
                    }
                    return ChatResult.Ok(content)
                }
                is Raw.Fail -> {
                    last = raw.reason
                    // 404 means this path does not exist; the next candidate may. A wrong key or a
                    // closed port will fail identically on every path, so stop early.
                    if (!raw.notFound) return ChatResult.Fail(raw.reason, raw.retryable)
                }
            }
        }
        return ChatResult.Fail(last)
    }

    fun listModels(protocol: ApiProtocol, api: String, apiKey: String): ModelsResult {
        val base = baseOf(api)
        if (base.isEmpty()) return ModelsResult.Fail("没填服务地址")
        var last = "请求失败"
        for (endpoint in modelEndpoints(protocol, base)) {
            when (val raw = request(protocol, endpoint, "GET", apiKey, null, LIST_TIMEOUT)) {
                is Raw.Ok -> {
                    val ids = modelIds(protocol, raw.body)
                    return ModelsResult.Ok(ids)
                }
                is Raw.Fail -> {
                    last = raw.reason
                    if (!raw.notFound) return ModelsResult.Fail(raw.reason)
                }
            }
        }
        return ModelsResult.Fail(last)
    }

    // ---------------------------------------------------------------- addresses

    /**
     * Turns whatever was typed into a base URL.
     *
     * People paste `192.168.1.10:8080`, the full `…/v1/chat/completions`, or a complete Gemini
     * `…/models/x:generateContent`. All three should work without coaching.
     */
    private fun baseOf(api: String): String {
        var base = api.trim()
        if (base.isEmpty()) return ""
        if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) {
            base = "http://$base"
        }
        base = base.trimEnd('/')
        if (base.endsWith(CHAT_SUFFIX)) base = base.removeSuffix(CHAT_SUFFIX).trimEnd('/')
        base = base.removeSuffix("/completions").trimEnd('/')
        if (base.contains(GEMINI_SUFFIX)) {
            base = base.substringBefore(GEMINI_SUFFIX).trimEnd('/')
            if (base.contains("/models/")) base = base.substringBefore("/models/").trimEnd('/')
        }
        return base
    }

    /** Keeps the version the user typed, and offers the usual one as a fallback when there is none. */
    private fun withVersion(base: String, version: String): List<String> =
        if (VERSION_TAIL.containsMatchIn(base)) listOf(base) else listOf("$base/$version", base)

    private fun stripVersion(base: String): String =
        if (VERSION_TAIL.containsMatchIn(base)) base.replace(VERSION_TAIL, "") else base

    /**
     * Ollama's OpenAI-compatible route lives under `/v1`, so a user who pasted a `/v1` address and
     * then switched to the native format still gets the right path.
     */
    private fun chatEndpoints(protocol: ApiProtocol, base: String, model: String): List<String> =
        when (protocol) {
            ApiProtocol.OPENAI -> withVersion(base, "v1").map { "$it$CHAT_SUFFIX" }
            ApiProtocol.ANTHROPIC -> withVersion(base, "v1").map { "$it/messages" }
            ApiProtocol.GEMINI -> withVersion(base, "v1beta").map {
                "$it/models/${geminiModel(model)}$GEMINI_SUFFIX"
            }
            ApiProtocol.OLLAMA -> listOf(stripVersion(base) + "/api/chat")
        }

    private fun modelEndpoints(protocol: ApiProtocol, base: String): List<String> =
        when (protocol) {
            ApiProtocol.OPENAI -> withVersion(base, "v1").map { "$it/models" }
            ApiProtocol.ANTHROPIC -> withVersion(base, "v1").map { "$it/models?limit=1000" }
            ApiProtocol.GEMINI -> withVersion(base, "v1beta").map { "$it/models?pageSize=1000" }
            ApiProtocol.OLLAMA -> listOf(stripVersion(base) + "/api/tags")
        }

    /** Gemini takes the bare model id; a pasted `models/…` prefix would double up in the path. */
    private fun geminiModel(model: String): String = model.trim().removePrefix("models/")

    // ---------------------------------------------------------------- requests

    private fun request(
        protocol: ApiProtocol,
        endpoint: String,
        method: String,
        apiKey: String,
        body: String?,
        timeout: Int
    ): Raw {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = timeout
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Lumanga/1.0")
                // Never hand a kept-alive socket back for reuse. A self-hosted server can close
                // the connection the instant it finishes answering, and the next page would then
                // reuse a dead socket and fail immediately — which is exactly how a batch run
                // loses a page every few minutes while single page reads stay fine.
                setRequestProperty("Connection", "close")
                when (protocol) {
                    ApiProtocol.OPENAI ->
                        if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
                    ApiProtocol.ANTHROPIC -> {
                        if (apiKey.isNotBlank()) setRequestProperty("x-api-key", apiKey)
                        setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
                    }
                    ApiProtocol.GEMINI ->
                        if (apiKey.isNotBlank()) setRequestProperty("x-goog-api-key", apiKey)
                    // A local Ollama needs no key, and sending one to a proxy that rejects unknown
                    // headers would only break a setup that worked before.
                    ApiProtocol.OLLAMA -> Unit
                }
            }
            if (body != null) {
                val payload = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(payload.size)
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(payload) }
            }
            val code = connection.responseCode
            val text = if (code in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                runCatching {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull().orEmpty()
            }
            if (code !in 200..299) {
                Log.w(TAG, "http=$code $endpoint ${text.take(200)}")
                return Raw.Fail(
                    reason = httpReason(code),
                    notFound = code == 404,
                    retryable = code == 429 || code >= 500
                )
            }
            return Raw.Ok(text)
        } catch (t: Throwable) {
            Log.w(TAG, "fail ${t.javaClass.simpleName}: ${t.message} $endpoint")
            return Raw.Fail(explain(t), retryable = isRetryable(t))
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * A batch run sends pages back to back, and a self-hosted server often drops an idle
     * keep-alive socket right after answering — the next request reuses that dead socket and dies
     * with "unexpected end of stream" or "connection reset" within seconds. One more try on a
     * fresh connection fixes it. A refused port or an unknown host will fail identically, so
     * those are not retried; that would only double the wait before the error appears.
     */
    private fun isRetryable(t: Throwable): Boolean = when (t) {
        is SocketTimeoutException -> true
        is ConnectException, is UnknownHostException -> false
        is SocketException -> true
        else -> false
    }

    private fun httpReason(code: Int): String = when (code) {
        400 -> "HTTP 400：请求被拒绝，可能是模型名不对"
        401, 403 -> "HTTP $code：Key 不对或没有权限"
        404 -> "HTTP 404：地址路径不对"
        413 -> "HTTP 413：本页文字太多，超出服务的请求上限"
        429 -> "HTTP 429：请求太频繁，被限流"
        in 500..599 -> "HTTP $code：服务端错误"
        else -> "HTTP $code"
    }

    private fun explain(t: Throwable): String = when (t) {
        is MalformedURLException -> "地址格式不对"
        is UnknownServiceException -> "系统禁止了明文 HTTP 请求"
        is UnknownHostException -> "找不到主机，检查地址写对没有"
        is ConnectException -> "连不上：端口没开，或者服务没启动"
        is SocketTimeoutException -> "超时：服务没有在限定时间内回复"
        is SocketException -> "连接被服务端断开了（本地服务常见，可重试）"
        is SSLException -> "HTTPS 握手失败，证书可能有问题"
        is IOException -> "网络错误（${t.javaClass.simpleName}）"
        else -> "请求失败（${t.javaClass.simpleName}）"
    }

    // ---------------------------------------------------------------- payloads

    private fun chatBody(
        protocol: ApiProtocol,
        model: String,
        system: String,
        user: String
    ): String = when (protocol) {
        ApiProtocol.OPENAI -> JSONObject()
            .put("model", model)
            .put("temperature", TEMPERATURE)
            .put("max_tokens", MAX_TOKENS)
            .put("messages", messages(system, user))
            .toString()

        ApiProtocol.OLLAMA -> JSONObject()
            .put("model", model)
            // Without this the reply streams and parses as garbage.
            .put("stream", false)
            .put("options", JSONObject().put("temperature", TEMPERATURE).put("num_predict", MAX_TOKENS))
            .put("messages", messages(system, user))
            .toString()

        ApiProtocol.ANTHROPIC -> JSONObject()
            .put("model", model)
            .put("max_tokens", MAX_TOKENS)
            .put("temperature", TEMPERATURE)
            // Anthropic takes the system prompt out of band, not as a message role.
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
            .toString()

        ApiProtocol.GEMINI -> JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", user)))
                )
            )
            .put(
                "generationConfig",
                JSONObject().put("temperature", TEMPERATURE).put("maxOutputTokens", MAX_TOKENS)
            )
            .toString()
    }

    private fun messages(system: String, user: String): JSONArray = JSONArray()
        .put(JSONObject().put("role", "system").put("content", system))
        .put(JSONObject().put("role", "user").put("content", user))

    private fun extractContent(protocol: ApiProtocol, body: String): String? = runCatching {
        when (protocol) {
            ApiProtocol.OPENAI -> {
                val choice = JSONObject(body).getJSONArray("choices").getJSONObject(0)
                choice.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() }
                    ?: choice.optString("text")
            }
            ApiProtocol.ANTHROPIC -> {
                val parts = JSONObject(body).getJSONArray("content")
                buildString {
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue
                        if (part.optString("type") == "text") append(part.optString("text"))
                    }
                }
            }
            ApiProtocol.GEMINI -> {
                val parts = JSONObject(body)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                buildString {
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue
                        // A thinking model may return its reasoning as an earlier part.
                        if (part.optBoolean("thought")) continue
                        append(part.optString("text"))
                    }
                }
            }
            ApiProtocol.OLLAMA -> JSONObject(body).optJSONObject("message")?.optString("content")
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun modelIds(protocol: ApiProtocol, body: String): List<String> = runCatching {
        val ids = LinkedHashSet<String>()
        when (protocol) {
            ApiProtocol.OPENAI, ApiProtocol.ANTHROPIC -> {
                val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val id = data.optJSONObject(i)?.optString("id").orEmpty()
                    if (id.isNotBlank()) ids.add(id)
                }
            }
            ApiProtocol.GEMINI -> {
                val models = JSONObject(body).optJSONArray("models") ?: JSONArray()
                for (i in 0 until models.length()) {
                    val name = models.optJSONObject(i)?.optString("name").orEmpty()
                        .removePrefix("models/")
                    if (name.isNotBlank()) ids.add(name)
                }
            }
            ApiProtocol.OLLAMA -> {
                val models = JSONObject(body).optJSONArray("models") ?: JSONArray()
                for (i in 0 until models.length()) {
                    val name = models.optJSONObject(i)?.optString("name").orEmpty()
                    if (name.isNotBlank()) ids.add(name)
                }
            }
        }
        ids.toList()
    }.getOrDefault(emptyList())
}
