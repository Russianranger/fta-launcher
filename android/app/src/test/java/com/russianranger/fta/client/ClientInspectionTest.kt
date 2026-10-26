package com.russianranger.fta.client

import org.junit.Assert.*
import org.junit.Test

class ClientInspectionTest {
    // Synthetic metadata only; no client assets or proprietary executable bytes.
    private fun fixture(x64: Boolean = false, version: Boolean = true): ByteArray {
        val bytes = ByteArray(2048)
        fun word(at: Int, value: Int) { bytes[at] = value.toByte(); bytes[at + 1] = (value ushr 8).toByte() }
        fun dword(at: Int, value: Long) { word(at, value.toInt()); word(at + 2, (value ushr 16).toInt()) }
        word(0, 0x5a4d); dword(60, 128); dword(128, 0x4550)
        word(132, if (x64) 0x8664 else 0x14c); word(134, 1)
        val optionalSize = if (x64) 240 else 224
        word(148, optionalSize); word(150, 2)
        word(152, if (x64) 0x20b else 0x10b)
        val directories = 152 + if (x64) 112 else 96
        dword(directories - 4, 16)
        val section = 152 + optionalSize
        dword(section + 8, 1024); dword(section + 12, 0x1000)
        dword(section + 16, 1024); dword(section + 20, 512)
        if (version) {
            dword(directories + 16, 0x1000); dword(directories + 20, 1024)
            word(512 + 14, 1); dword(528, 16); dword(532, 0x80000020)
            word(544 + 14, 1); dword(560, 1); dword(564, 0x80000040)
            word(576 + 14, 1); dword(592, 1033); dword(596, 96)
            dword(608, 0x1100); dword(612, 92)
            word(768, 92); word(770, 52)
            "VS_VERSION_INFO\u0000".toByteArray(Charsets.UTF_16LE).copyInto(bytes, 774)
            dword(808, 0xfeef04bd); dword(812, 0x10000)
            dword(816, 0x30003); dword(820, 0x50000 + 12340)
        }
        return bytes
    }
    private fun inspect(bytes: ByteArray) = PeMetadata.inspect(bytes.size.toLong()) { at, size ->
        bytes.copyOfRange(at.toInt(), at.toInt() + size)
    }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected bounded rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun originalBuildMetadataMatches() {
        val info = inspect(fixture())
        assertEquals("3.3.5.12340", info.fileVersion); assertTrue(ClientInventory.matchesWow(info))
    }
    @Test fun x64IsIdentifiedButNotWow() {
        val info = inspect(fixture(true)); assertEquals("x64", info.architecture); assertFalse(ClientInventory.matchesWow(info))
    }
    @Test fun missingVersionNeverMatches() {
        val info = inspect(fixture(version = false)); assertNull(info.fileVersion); assertFalse(ClientInventory.matchesWow(info))
    }
    @Test fun wrongBuildNeverMatches() {
        val bytes = fixture(); bytes[820] = 1; assertFalse(ClientInventory.matchesWow(inspect(bytes)))
    }
    @Test fun dllNeverMatchesWow() {
        val bytes = fixture(); bytes[151] = 0x20; assertFalse(ClientInventory.matchesWow(inspect(bytes)))
    }
    @Test fun machineAndOptionalHeaderMustAgreeForX86() {
        val bytes = fixture(); bytes[132] = 0x64; bytes[133] = 0x86.toByte()
        assertEquals("other", inspect(bytes).architecture)
    }
    @Test fun truncatedHeadersRejected() { for (length in listOf(0, 63, 140, 160, 390)) rejected { inspect(fixture().copyOf(length)) } }
    @Test fun unsignedPeOffsetCannotOverflow() {
        val bytes = fixture(); (60..63).forEach { bytes[it] = 0xff.toByte() }; rejected { inspect(bytes) }
    }
    @Test fun resourceCycleRejected() {
        val bytes = fixture(); bytes[532] = 0; rejected { inspect(bytes) }
    }
    @Test fun oversizedDirectoryRejectedBeforeAllocation() {
        val bytes = fixture(); bytes[526] = 0xff.toByte(); bytes[527] = 0xff.toByte(); rejected { inspect(bytes) }
    }
    @Test fun outOfSectionResourceRejected() {
        val bytes = fixture(); bytes[610] = 1; rejected { inspect(bytes) }
    }
    @Test fun fakeEmbeddedSignatureIsNotAVersionResource() {
        val bytes = fixture(version = false)
        fixture().copyOfRange(768, 860).copyInto(bytes, 768)
        assertNull(inspect(bytes).fileVersion)
    }
    @Test fun invalidVersionKeyOrSignatureRejected() {
        for (at in listOf(774, 808, 812)) { val bytes = fixture(); bytes[at] = (bytes[at].toInt() xor 1).toByte(); rejected { inspect(bytes) } }
    }
    @Test fun shortSourceReadRejected() { rejected { PeMetadata.inspect(2048) { _, size -> ByteArray(size - 1) } } }
    @Test fun metadataReadsStaySmall() {
        val bytes = fixture(); var total = 0
        PeMetadata.inspect(bytes.size.toLong()) { offset, size -> total += size; bytes.copyOfRange(offset.toInt(), offset.toInt() + size) }
        assertTrue(total < 1024)
    }
    @Test fun inventoryCountsWithoutKeepingContents() {
        val inventory = ClientInventory()
        assertEquals("wow", inventory.add("WoW.exe", false, 100))
        inventory.add("Data", true, null); inventory.add("Data/custom.MPQ", false, 1234)
        inventory.add("WTF/Config.wtf", false, null)
        assertEquals(3, inventory.files); assertEquals(1, inventory.directories)
        assertEquals(1334L, inventory.knownBytes); assertEquals(1, inventory.unknownSizes); assertEquals(1, inventory.mpqFiles)
    }
    @Test fun caseCollisionsRejected() {
        val inventory = ClientInventory(); inventory.add("Wow.exe", false, 64)
        rejected { inventory.add("WOW.EXE", false, 64) }
    }
    @Test fun unsafeNamesAndDepthRejected() {
        for (path in listOf("../Wow.exe", "/Wow.exe", "a//b", "a\\b", "a/./b", "a\n", "a/".repeat(33) + "b"))
            rejected { ClientInventory().add(path, false, 64) }
    }
    @Test fun genericExecutableNamesAreNeverExportedAsRoles() {
        assertEquals("auxiliary-executable-1", ClientInventory().add("Private Name/asset-host.exe", false, 64))
    }
    @Test fun originalDllHostPathGetsAFixedBinaryRole() {
        val inventory = ClientInventory()
        assertEquals("asset-host", inventory.add("Utils/WarcraftXLHost.exe", false, 64))
        assertTrue(ClientInventory.isBinary("asset-host"))
        assertEquals(0, inventory.executableCandidates)
        assertEquals("asset-host", ClientInventory().add("UTILS/WARCRAFTXLHOST.EXE", false, 64))
    }
    @Test fun hostNameInOtherLocationStaysAnonymous() {
        assertEquals("auxiliary-executable-1", ClientInventory().add("WarcraftXLHost.exe", false, 64))
        assertEquals("auxiliary-executable-1", ClientInventory().add("Other/WarcraftXLHost.exe", false, 64))
    }
    @Test fun executableCountBounded() {
        val inventory = ClientInventory(); repeat(32) { inventory.add("$it.exe", false, 64) }
        rejected { inventory.add("33.exe", false, 64) }
    }
    @Test fun metadataBudgetBounded() {
        val inventory = ClientInventory()
        rejected { repeat(5000) { inventory.add("a".repeat(2000) + it, false, 64) } }
    }
    @Test fun backendRoleComesFromOriginalLauncherPath() {
        assertEquals("dxvk", ClientInventory().add("Utils/WarcraftXL/backends/dxvk/d3d9.dll", false, 64))
        assertNull(ClientInventory().add("Other/d3d9.dll", false, 64))
    }
}
