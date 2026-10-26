package com.russianranger.fta.client

import org.junit.Assert.*
import org.junit.Test

class ClientWineOverridesTest {
    @Test fun emptyEnvironmentPrefersOriginalRootProxy() {
        assertEquals("d3d9=n,b", ClientWineOverrides.nativeProxy(""))
    }
    @Test fun mixedModuleClausesKeepOtherModulePolicy() {
        assertEquals("dxgi,d3d11=b;winemenubuilder.exe=;d3d9=n,b",
            ClientWineOverrides.nativeProxy(" d3d9,dxgi,D3D9.DLL,d3d11=b ; winemenubuilder.exe= "))
    }
    @Test fun conflictingAndRepeatedProxyClausesAreReplaced() {
        assertEquals("d3d9=n,b", ClientWineOverrides.nativeProxy("D3D9=b;d3d9.dll=;d3d9=n"))
    }
    @Test fun opaqueClausesAndOtherModuleNamesArePreserved() {
        assertEquals("opaque;d3d9helper=b;*d3d9=b;d3d9=n,b",
            ClientWineOverrides.nativeProxy("opaque;d3d9helper=b;*d3d9=b"))
    }
    @Test fun originalAsciiWhitespaceAndEmptyNamesAreHandled() {
        assertEquals("dxgi= b,n;d3d9=n,b", ClientWineOverrides.nativeProxy(";\t, d3d9, ,dxgi\r= b,n ;;;"))
    }
    @Test fun applyingPolicyTwiceIsStable() {
        val first = ClientWineOverrides.nativeProxy("dxgi,d3d9=b;dinput8=n")
        assertEquals(first, ClientWineOverrides.nativeProxy(first))
    }
    @Test fun embeddedNulFailsWithFixedReason() {
        try { ClientWineOverrides.nativeProxy("private\u0000value"); fail() }
        catch (error: IllegalArgumentException) { assertEquals("invalid-wine-overrides", error.message) }
    }
    @Test fun oversizedInputIsRejectedBeforeParsing() {
        try { ClientWineOverrides.nativeProxy("x".repeat(ClientWineOverrides.MAX_CHARACTERS + 1)); fail() }
        catch (error: IllegalArgumentException) { assertEquals("invalid-wine-overrides", error.message) }
        assertTrue(ClientWineOverrides.nativeProxy("x".repeat(ClientWineOverrides.MAX_CHARACTERS)).endsWith(";d3d9=n,b"))
    }
}
