plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.russianranger.fta"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.russianranger.fta"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-database-diagnostic"
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
    buildTypes {
        release { isMinifyEnabled = false }
    }
    lint { abortOnError = true }
}
dependencies { testImplementation("junit:junit:4.13.2") }
