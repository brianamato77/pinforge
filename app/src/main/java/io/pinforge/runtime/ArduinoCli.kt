package io.pinforge.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LibInfo(val name: String, val version: String, val sentence: String, val author: String)

/** Typed wrappers around arduino-cli commands. */
class ArduinoCli(private val rt: Runtime) {

    suspend fun updateIndex(log: (String) -> Unit): Boolean =
        rt.cli(listOf("core", "update-index"), log) == 0 &&
            rt.cli(listOf("lib", "update-index"), log) == 0

    suspend fun installCore(id: String, log: (String) -> Unit): Boolean {
        val ok = rt.cli(listOf("core", "install", id, "--skip-post-install"), log) == 0
        val n = rt.patchToolchains(log)
        log("Prepared $n toolchain binaries for Android")
        return ok
    }

    suspend fun installedCores(): Map<String, String> {
        val json = capture(listOf("core", "list", "--format", "json")) ?: return emptyMap()
        val arr: JSONArray = when (json) {
            is JSONObject -> json.optJSONArray("platforms") ?: JSONArray()
            is JSONArray -> json
            else -> JSONArray()
        }
        val out = mutableMapOf<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val v = o.optString("installed_version").ifEmpty { o.optString("installed") }
            if (id.isNotEmpty()) out[id] = v
        }
        return out
    }

    suspend fun searchLibs(query: String): List<LibInfo> {
        val json = capture(listOf("lib", "search", query, "--omit-releases-details", "--format", "json"))
            as? JSONObject ?: return emptyList()
        val arr = json.optJSONArray("libraries") ?: return emptyList()
        val out = ArrayList<LibInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val latest = o.optJSONObject("latest") ?: JSONObject()
            out += LibInfo(
                o.optString("name"),
                latest.optString("version").ifEmpty { o.optString("latest_version") },
                latest.optString("sentence"),
                latest.optString("author")
            )
        }
        return out.take(80)
    }

    suspend fun installLib(name: String, log: (String) -> Unit): Boolean =
        rt.cli(listOf("lib", "install", name), log) == 0

    suspend fun installedLibs(): Map<String, String> {
        val json = capture(listOf("lib", "list", "--format", "json")) ?: return emptyMap()
        val arr: JSONArray = when (json) {
            is JSONObject -> json.optJSONArray("installed_libraries") ?: JSONArray()
            is JSONArray -> json
            else -> JSONArray()
        }
        val out = mutableMapOf<String, String>()
        for (i in 0 until arr.length()) {
            val lib = arr.optJSONObject(i)?.optJSONObject("library") ?: continue
            out[lib.optString("name")] = lib.optString("version")
        }
        return out
    }

    suspend fun compile(sketchDir: File, fqbn: String, outDir: File, log: (String) -> Unit): Boolean {
        outDir.mkdirs()
        return rt.cli(
            listOf("compile", "--fqbn", fqbn, "--output-dir", outDir.path,
                "--build-path", File(outDir, ".work").path, sketchDir.path),
            log
        ) == 0
    }

    private suspend fun capture(args: List<String>): Any? {
        val sb = StringBuilder()
        rt.cli(args) { sb.append(it).append('\n') }
        val s = sb.toString()
        val i = s.indexOfFirst { it == '{' || it == '[' }
        if (i < 0) return null
        return try {
            if (s[i] == '{') JSONObject(s.substring(i)) else JSONArray(s.substring(i))
        } catch (_: Exception) {
            null
        }
    }
}
