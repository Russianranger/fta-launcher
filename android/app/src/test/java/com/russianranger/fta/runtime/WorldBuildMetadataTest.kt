package com.russianranger.fta.runtime

import org.junit.Assert.*
import org.junit.Test

class WorldBuildMetadataTest {
    private fun manifest(): String {
        val files = listOf("database-server", "database-client", "auth-server", "world-server").joinToString(",") {
            """{"path":"native/lib$it.so","role":"$it","size":128,"sha256":"${"a".repeat(64)}"}"""
        } + "," + """{"path":"data/metadata/world-updater-lock.json","role":"data","size":128,"sha256":"${"b".repeat(64)}"}"""
        return """{"formatVersion":1,"abi":"arm64-v8a","providerVersion":"12.3.2","capabilities":["database","authserver","worldserver"],"files":[$files],
            "worldBuild":{"formatVersion":1,"portProfile":"android-arm64-world-v8","sourceTreeSha256":"${"c".repeat(64)}",
            "sourceArchiveSha256":"${"d".repeat(64)}","worldserverSha256":"${"a".repeat(64)}","compiledUpdaterLockSha256":"${"b".repeat(64)}",
            "requiredModuleCount":52,"cxxStandard":20,"staticModules":true,"staticScripts":true,"luaVersion":"lua52","luaStatic":true,"configurationDirectory":"configs"}}"""
    }

    @Test fun compiledWorldMetadataBindsTheExactExecutableAndSignedPolicyLock() {
        val result = RuntimeManifest.parse(manifest())
        assertEquals("android-arm64-world-v8", result.worldBuild!!.portProfile)
        assertEquals("a".repeat(64), result.worldBuild!!.worldserverSha256)
        assertEquals("b".repeat(64), result.worldBuild!!.compiledUpdaterLockSha256)
    }

    @Test fun incompleteOrUnboundBuildMetadataCannotEnableWorldCapability() {
        for (json in listOf(manifest().replace("\"requiredModuleCount\":52", "\"requiredModuleCount\":51"),
            manifest().replace("\"cxxStandard\":20", "\"cxxStandard\":17"),
            manifest().replace("\"staticModules\":true", "\"staticModules\":false"),
            manifest().replace("\"luaVersion\":\"lua52\"", "\"luaVersion\":\"lua54\""),
            manifest().replace("\"configurationDirectory\":\"configs\"", "\"configurationDirectory\":\"/private/path\""),
            manifest().replace("\"worldserverSha256\":\"${"a".repeat(64)}\"", "\"worldserverSha256\":\"${"f".repeat(64)}\""),
            manifest().replace("\"compiledUpdaterLockSha256\":\"${"b".repeat(64)}\"", "\"compiledUpdaterLockSha256\":\"${"f".repeat(64)}\""))) {
            assertThrows(Exception::class.java) { RuntimeManifest.parse(json) }
        }
    }
}
