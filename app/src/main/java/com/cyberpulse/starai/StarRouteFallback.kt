package com.cyberpulse.starai

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

internal object StarRouteFallback {
    private val mask = byteArrayOf(
        0x50, 0x75, 0x6C, 0x73, 0x65, 0x52, 0x6F, 0x75,
        0x74, 0x65, 0x48, 0x69, 0x64, 0x64, 0x65, 0x6E, 0x21
    )

    private fun decode(blob: String): String {
        val cipher = Base64.decode(blob, Base64.DEFAULT)
        val plain = ByteArray(cipher.size) { index ->
            (cipher[index].toInt() xor mask[index % mask.size].toInt()).toByte()
        }
        return plain.toString(Charsets.UTF_8)
    }

    fun call(payloadJson: String): String {
        val key = decode(BuildConfig.STAR_ROUTE_B_KEY_BLOB)
        if (!key.startsWith("sk-")) throw IllegalStateException("Secondary route is not configured")

        val endpoint = decode(BuildConfig.STAR_ROUTE_B_URL_BLOB)
        val model = decode(BuildConfig.STAR_ROUTE_B_MODEL_BLOB)
        val payload = JSONObject(payloadJson)
        val outgoing = JSONArray()

        val systemPrompt = payload.optString("systemPrompt")
        if (systemPrompt.isNotBlank()) {
            outgoing.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }

        val input = payload.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until input.length()) {
            val item = input.optJSONObject(i) ?: continue
            val role = item.optString("role")
            val mappedRole = when (role) {
                "user" -> "user"
                "star", "assistant", "model" -> "assistant"
                else -> continue
            }
            val text = item.optString("text").ifBlank { item.optString("content") }
            if (text.isNotBlank()) {
                outgoing.put(JSONObject().put("role", mappedRole).put("content", text))
            }
        }

        if (outgoing.length() == 0) throw IllegalStateException("No message content")

        val request = JSONObject()
            .put("model", model)
            .put("messages", outgoing)

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
        }

        connection.outputStream.use { stream ->
            stream.write(request.toString().toByteArray(Charsets.UTF_8))
        }

        val code = connection.responseCode
        val responseStream = if (code in 200..299) connection.inputStream else connection.errorStream
        val raw = responseStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()

        if (code !in 200..299) throw IllegalStateException("Secondary route unavailable ($code)")

        val response = JSONObject(raw)
        val choice = response.optJSONArray("choices")?.optJSONObject(0)
            ?: throw IllegalStateException("Secondary route returned no result")
        val message = choice.optJSONObject("message")
        val content = message?.optString("content").orEmpty().ifBlank { choice.optString("text") }
        if (content.isBlank()) throw IllegalStateException("Secondary route returned an empty result")
        return content
    }
}
