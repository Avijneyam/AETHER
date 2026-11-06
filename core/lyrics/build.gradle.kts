import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Lyrics, from PODIUM: provider-independent models, LRC parsing, the LRCLIB provider, matching and a
// cache. Pure Kotlin; the app supplies the HTTP, the cache directory and the listener's consent.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
