plugins {
    kotlin("multiplatform") version "2.2.21-1.0.0"
    id("com.android.library") version "8.10.1"
}
kotlin {
    androidTarget(); jvm(); iosArm64(); iosX64()
    iosSimulatorArm64 { binaries.framework { baseName = "DiagnosticsConsumer" } }
    ohosArm64 { binaries.sharedLib { baseName = "DiagnosticsConsumer" } }
    sourceSets {
        commonMain.dependencies { implementation("com.github.gycrosskit.diagnostics:diagnostics-core:0.1.0") }
        jvmTest.dependencies { implementation(kotlin("test")) }
    }
}
android {
    namespace = "io.github.gycrosskit.diagnostics.consumer"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
}
