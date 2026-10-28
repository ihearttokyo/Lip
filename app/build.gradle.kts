import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val bundleSpeechModels = providers.gradleProperty("lipBundleSpeechModels").orNull == "true"
check(!bundleSpeechModels) { "Future Lip releases must be small: download Clean or Pro inside the app; bundled speech-model APKs are retired." }
// Historical preview binaries/URLs stay unchanged; retired build flags cannot
// create another identity or revive the old bundled-model test path.
val offlinePreview = providers.gradleProperty("lipOfflinePreview").orNull == "true"
check(!offlinePreview) { "lipOfflinePreview is retired. Use :app:assembleRelease or :app:canonicalReleaseApk; models download only after explicit in-app consent." }
// LIP_RELEASE_IDENTITY_BEGIN: authoritative ledger, no copied version literals.
val lipReleaseIdentity = groovy.json.JsonSlurper().parseText(providers.exec {
    commandLine("python3", rootProject.file("tools/release_identity.py").absolutePath, "--check", "--identity")
}.standardOutput.asText.get()) as Map<*, *>
val lipReleaseVersion = lipReleaseIdentity["version"] as String
val lipReleaseVersionCode = (lipReleaseIdentity["android_version_code"] as Number).toInt()
val lipReleaseDisplayName = lipReleaseIdentity["display_name"] as String
// LIP_RELEASE_IDENTITY_END
val historyFixture = providers.gradleProperty("lipHistoryFixture").orNull == "true"
val sizingFixture = providers.gradleProperty("lipSizingFixture").orNull == "true"
val stickerFixture = providers.gradleProperty("lipStickerFixture").orNull == "true"
check(!stickerFixture || sizingFixture) { "Sticker UI fixture requires isolated sizing identity" }
check(!(historyFixture && sizingFixture)) { "Choose one isolated fixture identity" }
val lipApplicationId = when { sizingFixture -> "dev.lip.android.sizingfixture"; historyFixture -> "dev.lip.android.historyfixture"; else -> "dev.lip.android" }

val signing = Properties().apply {
    providers.environmentVariable("LIP_SIGNING_PROPERTIES").orNull?.let { path ->
        file(path).inputStream().use { load(it) }
    }
}

android {
    namespace = "dev.lip"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = lipApplicationId
        manifestPlaceholders["historyFixtureEnabled"] = historyFixture.toString()
        manifestPlaceholders["stickerFixtureEnabled"] = stickerFixture.toString()
        manifestPlaceholders["lipInstrumentationTarget"] = lipApplicationId
        manifestPlaceholders["lipInstrumentationTestTarget"] = "$lipApplicationId.test"
        manifestPlaceholders["lipApplicationLabel"] = if (sizingFixture) "Lip keyboard sizing test" else if (historyFixture) "Lip import UI test" else lipReleaseDisplayName
        minSdk = 36
        targetSdk = 36
        versionCode = lipReleaseVersionCode
        versionName = lipReleaseVersion
        testInstrumentationRunner = if (historyFixture) "dev.lip.HistoryImportUiRunner" else "dev.lip.LipSmokeRunner"
        ndk { abiFilters += listOf("arm64-v8a") }
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
    androidResources { noCompress += "gif"; if (bundleSpeechModels) noCompress += "gguf" }
    testOptions { unitTests.isReturnDefaultValues = true }
    sourceSets.getByName("test").java.srcDir("src/androidTest/java/dev/lip/uiprotocol")
    sourceSets.getByName("test").java.srcDir("src/sharedTest/java")
    sourceSets.getByName("androidTest").java.srcDir("src/sharedTest/java")
}

// Release upgrades the already-published package; namespace/JNI and debug/
// isolated fixture IDs remain unchanged. Provider authorities use applicationId.
androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    variant.applicationId.set("dev.lip.android.preview")
    variant.manifestPlaceholders.put("lipInstrumentationTarget", "dev.lip.android.preview")
    variant.manifestPlaceholders.put("lipInstrumentationTestTarget", "dev.lip.android.preview.test")
    variant.manifestPlaceholders.put("lipApplicationLabel", lipReleaseDisplayName)
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

val bundleNotices = tasks.register<Copy>("bundleNotices") {
    from(rootProject.file("NOTICE.md"), rootProject.file("docs/assets/apache-2.0.txt"), rootProject.file("docs/assets/bark-NOTICE.txt"),
        rootProject.file("docs/assets/starling-MIT.txt"), rootProject.file("docs/assets/bark-CC-BY-4.0.txt"),
        rootProject.file("third_party/starling/third_party/ggml/LICENSE"),
        rootProject.file("docs/assets/whisper-mit.txt").also { check(it.isFile) { "Native MIT notice is missing" } })
    if (bundleSpeechModels) from(rootProject.file("docs/assets/bark-offline-models-NOTICE.txt").also {
        check(it.isFile) { "Offline bundled-model attribution is missing" }
    })
    from(rootProject.file("third_party/swipe/MIT.txt")) { rename { "swipe-engine-MIT.txt" } }
    from(rootProject.file("third_party/swipe/NOTICE.md")) { rename { "swipe-engine-NOTICE.md" } }
    from(rootProject.file("third_party/swipe/data-licenses")) { into("swipe-licenses") }
    from(rootProject.file("third_party/swipe/english-data-manifest.json")) { into("swipe-licenses") }
    from(rootProject.file("third_party/nextword")) {
        include("NOTICE.md", "CC-BY-4.0.txt", "CC-BY-2.0-FR.txt", "upstream-LICENSE", "source.json", "english-manifest.json")
        into("nextword-licenses")
    }
    from(rootProject.file("third_party/emoji")) { include("NOTICE.md", "source-manifest.json", "english-manifest.json", "source/*LICENSE.txt"); into("emoji-licenses") }
    from(rootProject.file("third_party/gif/NOTICE.md")) { rename { "gif-NOTICE.md" } }
    from(rootProject.file("third_party/gif/CC-BY-4.0.txt")) { rename { "gif-CC-BY-4.0.txt" } }
    from(rootProject.file("third_party/gif/manifest.json")) { rename { "gif-manifest.json" } }
    into(layout.buildDirectory.dir("generated/notices"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/notices"))
tasks.named("preBuild") { dependsOn(bundleNotices) }

dependencies {
    implementation("com.nimbusds:nimbus-jose-jwt:10.10")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

// Fixture builds can never produce a release variant. Default build identity unchanged.
androidComponents { beforeVariants(selector().withBuildType("release")) { if (historyFixture || sizingFixture) it.enable = false } }


// Explicit offline bundle only. Resident public weights stay out of source assets
// and Git; generated verified HARDLINKS avoid another 728 MB source copy. The APK
// and AGP intermediates still need their own storage. No download/copy fallback.
if (bundleSpeechModels) {
    val generatedSpeechAssets = layout.buildDirectory.dir("generated/lip-bundled-speech")
    val pinSource = file("src/main/java/dev/lip/speech/BarkModel.kt")
    val cleanSource = rootProject.file("validation/model-selection-20261026/underdog-bark-3.0-clean-q4pw.gguf")
    val proSource = rootProject.file("validation/bark-local-20261026/underdog-bark-3.0-pro-q4pw.gguf")
    val bundleSpeech = tasks.register("bundleSpeechModels") {
        inputs.files(pinSource, cleanSource, proSource)
        outputs.dir(generatedSpeechAssets)
        doLast {
            val pins = pinSource.readText()
            fun exact(pattern: Regex): String = pattern.findAll(pins).toList().singleOrNull()?.groupValues?.get(1)
                ?: error("Bark source pins changed or are ambiguous; refusing the offline bundle")
            val expectedBytes = exact(Regex("""const val BYTES = ([0-9_]+)L""")).replace("_", "").toLong()
            check(expectedBytes == 364_020_736L) { "Unreviewed model size; offline bundle refused" }
            val cleanHash = exact(Regex("""CLEAN\(\s*"clean"[\s\S]*?"([0-9a-f]{64})"\s*,\s*0\s*\)"""))
            val proHash = exact(Regex("""const val SHA256 = ["']([0-9a-f]{64})["']"""))
            check(Regex("""PRO\(\s*"pro"[\s\S]*?BarkModel\.SHA256\s*,\s*4000\s*\)""").containsMatchIn(pins)) {
                "Pro profile no longer uses the verified BarkModel pin"
            }
            check(pins.contains("val fileName get() = \"underdog-bark-3.0-\$id-q4pw.gguf\"")) {
                "Bark profile filename contract changed"
            }
            fun sha256(file: File): String {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val count = input.read(buffer); if (count < 0) break; if (count > 0) digest.update(buffer, 0, count) }
                }
                return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            val models = listOf(Triple("clean", cleanSource, cleanHash), Triple("pro", proSource, proHash))
            // Verify BOTH before exposing even one generated asset.
            for ((id, source, hash) in models) {
                check(source.isFile && source.length() == expectedBytes && sha256(source) == hash) {
                    "Resident $id model does not match BarkProfile size/SHA; no retrieval or fallback"
                }
            }
            val output = generatedSpeechAssets.get().asFile.resolve("speech").apply { check(isDirectory || mkdirs()) }
            val stageRoot = layout.buildDirectory.dir("intermediates/lip-speech-links").get().asFile.apply { check(isDirectory || mkdirs()) }
            for ((id, source, hash) in models) {
                val target = output.resolve("underdog-bark-3.0-$id-q4pw.gguf")
                if (target.isFile && Files.isSameFile(source.toPath(), target.toPath())) continue
                val staging = Files.createTempDirectory(stageRoot.toPath(), "verified-")
                val link = staging.resolve(target.name)
                try {
                    Files.createLink(link, source.toPath())
                    check(Files.size(link) == expectedBytes && sha256(link.toFile()) == hash) { "Generated model link changed during verification" }
                    Files.move(link, target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(link) // exact task-created link only, never source weights
                    Files.deleteIfExists(staging)
                }
            }
        }
    }
    android.sourceSets.getByName("main").assets.srcDir(generatedSpeechAssets)
    tasks.named("preBuild") { dependsOn(bundleSpeech) }
}

// Exact canonical installer name, derived from the same ledger as the manifest.
// The original published build50003 is never renamed or overwritten by this task.
tasks.register<Copy>("canonicalReleaseApk") {
    dependsOn("assembleRelease")
    doFirst {
        val output = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val metadata = groovy.json.JsonSlurper().parse(output.resolve("output-metadata.json")) as Map<*, *>
        val elements = metadata["elements"] as List<*>
        val artifact = elements.single() as Map<*, *>
        check(artifact["outputFile"] == "app-release.apk") { "Canonical installer requires an explicitly signed release APK; no stale/unsigned fallback." }
        val apk = output.resolve("app-release.apk")
        check(apk.isFile)
        val aapt = android.sdkComponents.sdkDirectory.get().asFile.resolve("build-tools/${android.buildToolsVersion}/aapt")
        val badging = providers.exec { commandLine(aapt, "dump", "badging", apk) }.standardOutput.asText.get()
        check(badging.lineSequence().first().contains("name='dev.lip.android.preview' versionCode='$lipReleaseVersionCode' versionName='$lipReleaseVersion'")) {
            "Built APK identity mismatch; no canonical copy was made"
        }
    }
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(layout.buildDirectory.dir("outputs/canonical-release"))
    rename { lipReleaseIdentity["android_apk"] as String }
}
