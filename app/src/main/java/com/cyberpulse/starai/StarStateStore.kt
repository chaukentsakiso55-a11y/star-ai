package com.cyberpulse.starai

import android.content.Context
import java.io.File

internal class StarStateStore(context: Context) {
    private val stateFile = File(context.filesDir, "star_state.json")
    private val tempFile = File(context.filesDir, "star_state.tmp")
    private var lastSaved: String? = null

    @Synchronized
    fun load(): String {
        val value = runCatching {
            if (stateFile.exists()) stateFile.readText(Charsets.UTF_8) else ""
        }.getOrDefault("")
        lastSaved = value
        return value
    }

    @Synchronized
    fun save(json: String): Boolean {
        if (json.isBlank()) return false
        if (json.length > MAX_STATE_CHARS) return false
        if (json == lastSaved) return true

        return runCatching {
            tempFile.writeText(json, Charsets.UTF_8)
            if (stateFile.exists() && !stateFile.delete()) {
                throw IllegalStateException("Unable to replace state file")
            }
            if (!tempFile.renameTo(stateFile)) {
                stateFile.writeText(json, Charsets.UTF_8)
                tempFile.delete()
            }
            lastSaved = json
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun clear() {
        runCatching { tempFile.delete() }
        runCatching { stateFile.delete() }
        lastSaved = ""
    }

    companion object {
        private const val MAX_STATE_CHARS = 8_000_000
    }
}
