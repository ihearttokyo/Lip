import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val signing = Properties().apply {
    providers.environmentVariable("LIP_SIGNING_PROPERTIES").orNull?.let { path ->
        file(path).inputStream().use { load(it) }
    }
}

android {
    namespace = "dev.lip"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.lip.android"
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "dev.lip.LipSmokeRunner"
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
        release {
            isMinifyEnabled = false
            if (signing.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

val bundleNotices = tasks.register<Copy>("bundleNotices") {
    from(rootProject.file("NOTICE.md"), rootProject.file("docs/assets/apache-2.0.txt"))
    into(layout.buildDirectory.dir("generated/notices"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/notices"))
tasks.named("preBuild") { dependsOn(bundleNotices) }

dependencies {
    implementation("com.nimbusds:nimbus-jose-jwt:10.10")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
