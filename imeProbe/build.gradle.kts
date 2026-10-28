// Owned-emulator diagnostics only; never a component of the Lip app or release.
plugins { id("com.android.application") }
android {
    namespace = "dev.lip.imeprobe"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { applicationId = "dev.lip.imeprobe"; minSdk = 33; targetSdk = 36; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
androidComponents { beforeVariants(selector().withBuildType("release")) { it.enable = false } }
