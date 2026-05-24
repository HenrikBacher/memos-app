plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    id("com.github.ben-manes.versions") version "0.54.0"
}

tasks.named<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask>("dependencyUpdates") {
    rejectVersionIf {
        val candidate = candidate.version.lowercase()
        listOf("alpha", "beta", "rc", "-m", "snapshot", "dev", "preview")
            .any { candidate.contains(it) }
    }
}
