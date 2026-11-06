import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Asks the real service for every guest surface and checks each one parses (needs the network).
// ./gradlew :ytmusic:liveCheck            charts for the listener's own country
// ./gradlew :ytmusic:liveCheck -Pcountry=US
tasks.register<JavaExec>("liveCheck") {
    group = "verification"
    description = "Fetches every guest YouTube Music surface live and checks that it parses."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.aether.ytmusic.LiveCheckKt")
    args = listOfNotNull(providers.gradleProperty("country").orNull)
}
