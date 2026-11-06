import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.aether"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.aether"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // Not debuggable by default, as in PODIUM: ART compiles it, so shaders and scrolling run
            // as they will for listeners. For a debugger: -Paether.debuggable=true
            isDebuggable = providers.gradleProperty("aether.debuggable").orNull == "true"
        }
        release {
            // Shrunk and optimised by R8, with the libraries' baseline profiles installed on the phone
            // (profileinstaller), so the first time anything runs it's already compiled. Signed with
            // this machine's debug key for installing directly; a store key stays out of the repo.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":core:lyrics"))
    implementation(project(":player"))
    implementation(project(":sources:youtube"))
    implementation(project(":sources:local"))
    implementation(project(":sources:addon"))
    implementation(project(":sources:apple"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.coil.compose)
    // The welcome's water: a short looping clip played muted into a TextureView.
    implementation(libs.media3.exoplayer)
    // An artist's short film: HLS, video only, kept in a cache of its own.
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource)
    implementation(libs.media3.database)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.profileinstaller)
}
