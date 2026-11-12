package com.yingcolor.lumanga.data

/**
 * Wire format of the endpoint the user configured.
 *
 * Every service speaks its own dialect: the official OpenAI route lives at `/v1/chat/completions`,
 * Anthropic needs `x-api-key` plus an `anthropic-version` header and a top-level `system` field,
 * Gemini puts the model in the URL and wraps the prompt in `contents`, and a plain Ollama box has
 * its own `/api/chat` path. Reading is not a place to force a single vendor on people, so the
 * choice is explicit and remembered.
 */
enum class ApiProtocol(val id: Int, val label: String) {
    OPENAI(0, "OpenAI 兼容"),
    ANTHROPIC(1, "Anthropic Claude"),
    GEMINI(2, "Google Gemini"),
    OLLAMA(3, "Ollama");

    companion object {
        /** Anything unknown falls back here: it covers official APIs, relay services and locals. */
        val DEFAULT: ApiProtocol = OPENAI

        fun from(id: Int): ApiProtocol = entries.firstOrNull { it.id == id } ?: DEFAULT

        fun coerce(id: Int): Int = from(id).id
    }
}
