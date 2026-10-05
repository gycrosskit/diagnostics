plugins {
    kotlin("multiplatform") version "2.2.21-1.0.0"
    id("com.android.library") version "8.10.1"
}
kotlin {
    androidTarget(); jvm(); iosArm64(); iosX64 { binaries.framework { baseName = "DiagnosticsConsumer" } }
    iosSimulatorArm64 { binaries.framework { baseName = "DiagnosticsConsumer" } }
    ohosArm64 { binaries.sharedLib { baseName = "DiagnosticsConsumer" } }
    applyDefaultHierarchyTemplate()
    sourceSets {
        if (providers.gradleProperty("diagnosticsClosure").orNull == "true") {
            commonMain.get().kotlin.srcDir("src/closureCommonMain/kotlin")
            val notificationMain by creating {
                dependsOn(commonMain.get())
                kotlin.srcDir("src/closureNotificationMain/kotlin")
                dependencies { implementation("com.github.gycrosskit.diagnostics:diagnostics-dingtalk:${providers.gradleProperty("diagnosticsVersion").orElse("0.2.0-rc.7").get()}") }
            }
            androidMain.get().dependsOn(notificationMain)
            jvmMain.get().dependsOn(notificationMain)
            iosMain.get().dependsOn(notificationMain)
            ohosArm64Main.get().dependsOn(notificationMain)
        }
        if (providers.gradleProperty("diagnosticsNetwork").orNull == "true") {
            commonMain.get().kotlin.srcDir("src/networkCommonMain/kotlin")
            commonMain.dependencies { implementation("com.github.gycrosskit.diagnostics:diagnostics-ktor:${providers.gradleProperty("diagnosticsVersion").orElse("0.2.0-rc.7").get()}") }
            val networkJvmMain by creating {
                dependsOn(commonMain.get())
                kotlin.srcDir("src/networkJvmMain/kotlin")
                dependencies { implementation("com.github.gycrosskit.diagnostics:diagnostics-okhttp:${providers.gradleProperty("diagnosticsVersion").orElse("0.2.0-rc.7").get()}") }
            }
            jvmMain.get().dependsOn(networkJvmMain)
            androidMain.get().dependsOn(networkJvmMain)
        }
        commonMain.dependencies { implementation("com.github.gycrosskit.diagnostics:diagnostics-core:${providers.gradleProperty("diagnosticsVersion").orElse("0.2.0-rc.7").get()}") }
        jvmTest.dependencies { implementation(kotlin("test")) }
        androidUnitTest.dependencies { implementation(kotlin("test")) }
    }
}
android {
    namespace = "io.github.gycrosskit.diagnostics.consumer"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
}
