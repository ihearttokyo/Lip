import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val signing = Properties().apply {
    providers.environmentVariable("LIP_SIGNING_PROPERTIES").orNull?.let { path ->
        file(path).inputStream().use { load(it) }
    }
}

// Experimental A/B arms share every setting except the standard ARM DOTPROD module.
val lipDotprod = providers.gradleProperty("lipDotprod").orElse("false").get().also {
    require(it == "true" || it == "false") { "lipDotprod must be true or false" }
}

android {
    namespace = "dev.lip"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "dev.lip.android"
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "dev.lip.LipSmokeRunner"
        externalNativeBuild { cmake {
            arguments += listOf("-DANDROID_STL=c++_shared", "-DLIP_GGML_DOTPROD=${if (lipDotprod == "true") "ON" else "OFF"}")
        } }
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (signing.isNotEmpty()) create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug {
            // Exercise production-speed CPU kernels while retaining native debug symbols.
            externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=RelWithDebInfo" } }
        }
        release {
            isMinifyEnabled = false
            if (signing.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    packaging { jniLibs.useLegacyPackaging = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    sourceSets.getByName("test").java.srcDir("src/androidTest/java/dev/lip/uiprotocol")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

val bundleNotices = tasks.register<Copy>("bundleNotices") {
    from(rootProject.file("NOTICE.md"), rootProject.file("docs/assets/apache-2.0.txt"),
        rootProject.file("docs/assets/android-ndk-notice.txt").also { check(it.isFile) { "NDK notice is missing" } },
        rootProject.file("docs/assets/android-ndk-toolchain-notice.txt").also { check(it.isFile) { "NDK toolchain notice is missing" } },
        rootProject.file("docs/assets/whisper-mit.txt").also { check(it.isFile) { "Native MIT notice is missing" } })
    into(layout.buildDirectory.dir("generated/notices"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/notices"))
tasks.named("preBuild") { dependsOn(bundleNotices) }

dependencies {
    implementation("com.nimbusds:nimbus-jose-jwt:10.10")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
