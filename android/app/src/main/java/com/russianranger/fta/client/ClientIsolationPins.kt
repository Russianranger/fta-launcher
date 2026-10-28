package com.russianranger.fta.client

/** Measured NDK r28c/API26/16 KiB payloads. The original runtime bridge is retained. */
object ClientIsolationPins {
    val payloads = mapOf(
        "libproot_ro.so" to "d7d606c2a6e59687054448ac6711282ee1f542ec8ff317dd9ae86cb1da9a6603",
        "libproot-loader.so" to "614591192bb34c98cc6cef528fb74ecfc1c97d0fabf4e352df1f16873c71d447",
        "libfta_storage_probe.so" to "78400783fc04669c55447a51e2b38b77018c493201ef0708033bb229a4fd92e8"
    )
}
