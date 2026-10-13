package dev.lip.uiprotocol

import dev.lip.auth.ChatGptClient
import org.json.JSONArray
import org.json.JSONObject

/** Public-text cleanup validation only; never reads audio, dictionary or history. */
object CloudCleanupProbe {
    fun run(client: ChatGptClient, preferredModel: String, permitted: () -> Boolean): JSONObject =
        JSONObject().put("passed", false).put("status", "not_requested").put("rows", JSONArray())
}
