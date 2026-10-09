package dev.lip

import org.junit.Assert.*
import org.junit.Test

class PlatformSpeechProbeTest {
    @Test fun mainActionsCompleteBeforeTheirExactFailureReturnsToTheWorker() {
        val worker = Thread.currentThread()
        var completed = false
        var escaped: Throwable? = null
        var actionThread: Thread? = null
        val dispatch: (Runnable) -> Unit = { task ->
            completed = false
            escaped = null
            val main = Thread {
                try {
                    task.run()
                    completed = true
                } catch (error: Throwable) { escaped = error }
            }
            main.start()
            main.join(2_000)
            assertFalse("Fixture main thread must finish", main.isAlive)
        }
        var calls = 0
        PlatformSpeechProbe.dispatchOnMain(dispatch) { actionThread = Thread.currentThread(); calls++ }
        assertTrue(completed)
        assertNull(escaped)
        assertNotSame(worker, actionThread)
        assertEquals(1, calls)
        for (expected in listOf(IllegalStateException("OnDeviceRecognitionUnavailable"), AssertionError("MainThreadError"))) {
            val actual = runCatching {
                PlatformSpeechProbe.dispatchOnMain(dispatch) { actionThread = Thread.currentThread(); throw expected }
            }.exceptionOrNull()
            assertTrue("SyncRunnable must reach completion after task.run", completed)
            assertNull(escaped)
            assertNotSame(worker, actionThread)
            assertSame(expected, actual)
        }
        val destroyFailure = IllegalStateException("DestroyFailed")
        var cleanupOk = true
        val closed = mutableListOf<Int>()
        try { PlatformSpeechProbe.dispatchOnMain(dispatch) { throw destroyFailure } }
        catch (error: Exception) { assertSame(destroyFailure, error); cleanupOk = false }
        listOf(0, 1).forEach { descriptor -> closed.add(descriptor) }
        assertTrue(completed)
        assertNull(escaped)
        assertFalse(cleanupOk)
        assertEquals(listOf(0, 1), closed)
    }

    @Test fun physicalMetadataRequiresOnlyTheExactOptInAndRejectsEmulators() {
        fun route(arguments: Map<String, Any?> = emptyMap(), target: String = "dev.lip.android", api: Int = 33,
                  hardware: String = "qcom", product: String = "public_phone", fingerprint: String = "oem/phone/release",
                  model: String = "Public phone", manufacturer: String = "OEM", brand: String = "oem", device: String = "phone") =
            PlatformSpeechProbe.metadataRoute(arguments, target, api, hardware, product, fingerprint, model, manufacturer, brand, device)
        fun denied(expected: String, attempt: () -> String) {
            val failure = runCatching(attempt).exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(expected, failure?.message)
        }
        val physical = mapOf("physical_metadata_only" to "true")
        assertEquals("physical", route(physical))
        assertEquals("emulator", route(hardware = "ranchu", product = "sdk_gphone64_arm64"))
        assertEquals("emulator", route(hardware = "goldfish", product = "sdk_phone"))
        denied("EmulatorIsolationRequired") { route() }
        denied("EmulatorIsolationRequired") { route(hardware = "ranchu") }
        denied("EmulatorIsolationRequired") { route(product = "sdk_phone") }
        for (arguments in listOf(mapOf("physical_metadata_only" to "false"), mapOf("physical_metadata_only" to "TRUE"),
            mapOf("physical_metadata_only" to true), mapOf("physical_metadata_only" to null),
            mapOf("physical_metadata_only" to ""), mapOf("physical_metadata_only" to " true"),
            mapOf("physical_metadata_only" to "true", "extra" to "true"), mapOf("unknown" to "true"))) {
            denied("InvalidMetadataArguments") { route(arguments) }
            denied("InvalidMetadataArguments") { route(arguments, hardware = "ranchu", product = "sdk_phone") }
        }
        for (arguments in listOf(emptyMap(), physical)) {
            denied("WrongTargetPackage") { route(arguments, target = "dev.lip.android.test", hardware = "ranchu", product = "sdk_phone") }
            denied("Api33Required") { route(arguments, api = 32, hardware = "ranchu", product = "sdk_phone") }
        }
        denied("BuildMetadataMissing") { route(physical, hardware = "") }
        denied("BuildMetadataMissing") { route(physical, product = " ") }
        for (hardware in listOf("ranchu", "goldfish", "cuttlefish", "crosvm", "vbox86"))
            denied("PhysicalRouteRejectsEmulator") { route(physical, hardware = hardware) }
        for (product in listOf("sdk_phone", "vbox86p", "EMULATOR", "simulator"))
            denied("PhysicalRouteRejectsEmulator") { route(physical, product = product) }
        for (fingerprint in listOf("generic/phone", "unknown"))
            denied("PhysicalRouteRejectsEmulator") { route(physical, fingerprint = fingerprint) }
        for (model in listOf("Emulator", "google_sdk", "Android SDK built for x86"))
            denied("PhysicalRouteRejectsEmulator") { route(physical, model = model) }
        denied("PhysicalRouteRejectsEmulator") { route(physical, manufacturer = "Genymotion") }
        denied("PhysicalRouteRejectsEmulator") { route(physical, brand = "generic", device = "generic") }
        denied("PhysicalRouteRejectsEmulator") { route(physical, hardware = "ranchu", product = "sdk_phone") }
    }
}
