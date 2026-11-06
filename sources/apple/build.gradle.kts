import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// An artist's portrait, lettered name and short film, from Apple Music's public pages (after
// BitChord). Decoration for an artist's page: pure Kotlin, and the app supplies the HTTP and the
// listener's consent. Nothing here is played as music or needed for anything to work.
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
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
