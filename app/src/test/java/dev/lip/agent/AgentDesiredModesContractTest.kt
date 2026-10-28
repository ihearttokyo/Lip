package dev.lip.agent

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Shared desired-label fixture only, never a provider-send capability test. */
class AgentDesiredModesContractTest {
    @Test fun androidDesiredModesMatchSharedCrossPlatformFixture() {
        val candidates = listOf(File("../docs/contracts/agent-handoff-modes-v1.json"), File("docs/contracts/agent-handoff-modes-v1.json"))
        val source = candidates.singleOrNull { it.isFile } ?: error("Shared agent mode fixture missing or ambiguous")
        val contract = JSONObject(source.readText(Charsets.UTF_8))
        assertEquals("lip-agent-desired-ui-modes-v1", contract.getString("schema"))
        val routes = contract.getJSONArray("routes")
        assertEquals(AgentRouteChoice.entries.map { it.name }, (0 until routes.length()).map { routes.getString(it) })
        val efforts = contract.getJSONArray("efforts")
        assertEquals(AgentEffort.entries.map { it.name to it.label }, (0 until efforts.length()).map {
            val item = efforts.getJSONObject(it); item.getString("id") to item.getString("label")
        })
        val defaults = AgentHandoffPreferences()
        assertEquals(defaults.route.name, contract.getString("defaultRoute"))
        assertEquals(defaults.defaultEffort.name, contract.getString("defaultEffort"))
        // This fixture intentionally makes no provider API mapping/admission.
        assertEquals(0, contract.getJSONArray("productionAdmittedRoutes").length())
    }
}
