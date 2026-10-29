package com.russianranger.fta.client
import org.junit.Assert.*
import org.junit.Test

class ClientLaunchSettingsTest {
    @Test fun onlyRequiredSettingsChangeAndUnknownBytesRoundTrip() {
        val privateLine = byteArrayOf(35, 32, 233.toByte(), 13, 10)
        val original = privateLine + "SET accountName \"keep\"\r\nSET Specular \"1\"\r\nSET projectedTextures \"0\"\r\n".toByteArray()
        val changed = ClientLaunchSettings.update(original)
        assertArrayEquals(privateLine + "SET accountName \"keep\"\r\nSET specular \"0\"\r\nSET projectedTextures \"1\"\r\n".toByteArray(), changed)
        assertArrayEquals(changed, ClientLaunchSettings.update(changed))
    }
    @Test fun missingSettingsAndDuplicatesAreHandledWithoutResettingConfiguration() {
        assertEquals("SET specular \"0\"\nSET projectedTextures \"1\"\n", ClientLaunchSettings.update(byteArrayOf()).toString(Charsets.UTF_8))
        val changed = ClientLaunchSettings.update("# keep\nSET specular \"1\"\nSET specular \"2\"".toByteArray()).toString(Charsets.UTF_8)
        assertTrue(changed.startsWith("# keep\n")); assertFalse(changed.contains("\"2\"")); assertFalse(changed.contains("\"1\"\nSET specular"))
    }
    @Test fun oversizedAndNulConfigurationsAreRefused() {
        for (bytes in listOf(ByteArray(ClientLaunchSettings.LIMIT + 1), byteArrayOf(0))) {
            try { ClientLaunchSettings.update(bytes); fail() } catch (e: RuntimeInstallFailure) { assertEquals("client-config-refused", e.reason) }
        }
    }
}
