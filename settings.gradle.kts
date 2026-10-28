pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Lip"
include(":app")
// Standalone test-only observer: its instrumentation cannot force-stop Lip or the editor fixture.
include(":imeProbe")
