package com.russianranger.fta.client

import java.io.File

/** Full public-candidate qualification with a bounded JVM heap; no native execution. */
object RuntimeStoreQualification {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 4)
        val catalog = File(args[0]); val archive = File(args[1]); val root = File(args[2])
        require(!root.exists())
        val plan = RuntimeArchivePlan.read(catalog.inputStream(), args[3])
        var maximumRequest = 0; var maximumCacheRange = 0L; var ioBytes = 0L
        val store = ClientRuntimeStore(root, plan, beforeIo = { maximumRequest = maxOf(maximumRequest, it); ioBytes += it },
            releaseCache = { _, _, length -> maximumCacheRange = maxOf(maximumCacheRange, length) })
        val ready = store.install({ archive.inputStream() })
        store.withVerified { check(it == ready) }
        check(plan.entries.size == 14092 && plan.fileBytes == 2637799241L)
        check(maximumRequest <= ClientRuntimeStore.BUFFER && maximumCacheRange <= ClientRuntimeStore.WINDOW + ClientRuntimeStore.BUFFER)
        println("{\"runtimeEntries\":${plan.entries.size},\"runtimeFileBytes\":${plan.fileBytes}," +
            "\"catalogSha256\":\"${plan.catalogSha256}\",\"maximumIoRequest\":$maximumRequest," +
            "\"maximumCacheRange\":$maximumCacheRange,\"accountedIoBytes\":$ioBytes," +
            "\"heapMaxBytes\":${Runtime.getRuntime().maxMemory()},\"publishedFullRehash\":true," +
            "\"retainedFullRehash\":true,\"androidExecutionQualified\":false}")
    }
}
