plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val fullSeedAssetDirectory = layout.buildDirectory.dir("generated/fullSeedAssets")
val prepareFullSeedMetadata by tasks.registering(Copy::class) {
    from(rootProject.file("../manifests/full-seed-lock.json"))
    from(rootProject.file("../manifests/game-data-policy.properties"))
    from(rootProject.file("../manifests/world-session.json"))
    from(rootProject.file("qualification/client-runtime-lock.json"))
    providers.gradleProperty("ftaClientRuntimeCatalog").orNull?.let { catalog ->
        from(file(catalog)) { rename { "client-runtime-catalog.tsv" } }
    }
    from(rootProject.file("../LICENSE")) { rename { "launcher-LICENSE.txt" } }
    into(fullSeedAssetDirectory.map { it.dir("metadata") })
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareFullSeedMetadata) }
val ftaSigningStore = providers.gradleProperty("ftaSigningStore").orNull
android {
    namespace = "com.russianranger.fta"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.russianranger.fta"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("ftaVersionCode").orNull?.toInt()?.also {
            require(it in 1..2_100_000_000) { "ftaVersionCode is outside the Android version range" }
        } ?: 47
        versionName = providers.gradleProperty("ftaVersionName").orNull?.also {
            require(it.matches(Regex("[A-Za-z0-9._+-]{1,100}"))) { "Invalid ftaVersionName" }
        } ?: "0.16.1-view-budget"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("../../native/process-supervisor/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    providers.gradleProperty("ftaProviderDir").orNull?.let { providerDirectory ->
        sourceSets.getByName("main") {
            jniLibs.srcDir(file("$providerDirectory/jniLibs"))
            assets.srcDir(file("$providerDirectory/assets"))
        }
    }
    sourceSets.getByName("main").assets.srcDir(fullSeedAssetDirectory)
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Runtime manifest hashes identify the exact provider ELF bytes.
            keepDebugSymbols += "**/*.so"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (ftaSigningStore != null) create("ftaDevelopment") {
            storeFile = file(ftaSigningStore)
            storePassword = providers.environmentVariable("FTA_SIGNING_STORE_PASSWORD").orNull
                ?: error("FTA_SIGNING_STORE_PASSWORD is required with ftaSigningStore")
            keyAlias = providers.gradleProperty("ftaSigningAlias").orElse("ftadevelopment").get()
            keyPassword = providers.environmentVariable("FTA_SIGNING_KEY_PASSWORD").orNull
                ?: error("FTA_SIGNING_KEY_PASSWORD is required with ftaSigningStore")
        }
    }
    buildTypes {
        debug {
            if (ftaSigningStore != null) signingConfig = signingConfigs.getByName("ftaDevelopment")
        }
        release {
            isMinifyEnabled = false
            if (ftaSigningStore != null) signingConfig = signingConfigs.getByName("ftaDevelopment")
        }
    }
    lint { abortOnError = true }
}
dependencies { testImplementation("junit:junit:4.13.2") }
