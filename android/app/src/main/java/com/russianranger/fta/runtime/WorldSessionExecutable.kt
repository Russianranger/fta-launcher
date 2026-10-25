package com.russianranger.fta.runtime

import java.io.File
import java.nio.file.Files

/** Additive runtime repair. The immutable baseline still identifies realms and backups. */
internal object WorldSessionExecutable {
    const val NAME = "libfta_world_session.so"
    const val SHA256 = "a7f7d3f2018703f931da68031972d1468a0c120d8f815608fcaf0e153322b20e"
    const val BYTES = 62665968L
    const val PROFILE = "android-arm64-world-v14"
    const val BASE_MANIFEST = "dda46e9555ad3b58acdbb0125fccdc89850de78a56159ee2bfcbfbde8f529c8b"
    const val BASE_WORLD = "97e578f7e3013715c2d9254dc29dd87d4a33598583093110eaad24157ee67766"
    const val BASE_TREE = "847f2e60c06cdb1b1154f9a1ed5c31b483550ed5c5ec925910c4347edbd0e064"
    const val UPDATER = "466c1c6673d7f939b14c4b6f4dc787efa58ac6c8fa91e982c76151a3f1f8c02f"

    fun requireBaseline(manifestHash: String, manifest: RuntimeManifest) {
        require(manifestHash == BASE_MANIFEST) { "World session requires the exact retained runtime" }
        require(manifest.worldBuild == WorldBuildMetadata("android-arm64-world-v13", BASE_TREE,
            "907a0a0bfde4f86b0471f798d93d2a74fdc50ece398cbb31c0ba22ddbc8e81bb", BASE_WORLD, UPDATER))
        require(manifest.files.single { it.role == "world-server" }.let {
            it.path == "native/libfta_worldserver.so" && it.sha256 == BASE_WORLD
        })
        require(manifest.files.single { it.path == WorldUpdaterMetadata.PATH }.sha256 == UPDATER)
    }
    fun prepare(runtime: PackagedRuntime): File {
        requireBaseline(runtime.manifestSha256, runtime.manifest)
        val root = runtime.nativeDirectory.canonicalFile
        val executable = File(root, NAME)
        require(!Files.isSymbolicLink(executable.toPath()) && executable.canonicalFile.parentFile == root)
        RuntimeManifest.verifyFile(executable, RuntimeFile("native/$NAME", SHA256, BYTES, "world-server"))
        return executable
    }
    fun select(role: String, baseline: Map<String, File>, session: Boolean, verified: File?): File {
        require(role in setOf("database-server", "database-client", "auth-server", "world-server"))
        if (!session || role != "world-server") return baseline.getValue(role)
        return requireNotNull(verified).also { require(it.isAbsolute && it.name == NAME) }
    }
    fun receiptMatches(hash: String, profile: String) = hash == SHA256 && profile == PROFILE
}
