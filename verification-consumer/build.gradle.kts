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
        val consumerVersion = providers.gradleProperty("diagnosticsVersion").orElse("0.2.0-rc.11").get()
        if (consumerVersion != "0.1.0" && consumerVersion !in (1..11).map { "0.2.0-rc.$it" }) {
            commonMain.get().kotlin.srcDir("src/snapshotCommonMain/kotlin")
            commonTest.get().kotlin.srcDir("src/snapshotCommonTest/kotlin")
        }
        // CI 同时回归旧公开坐标；native history 入口从 rc.9 起提供。
        if (consumerVersion !in (1..8).map { "0.2.0-rc.$it" } && consumerVersion != "0.1.0") {
            androidMain.get().kotlin.srcDir("src/nativeHistoryAndroidMain/kotlin")
        }
        if (providers.gradleProperty("diagnosticsClosure").orNull == "true") {
            commonMain.get().kotlin.srcDir("src/closureCommonMain/kotlin")
            val notificationMain by creating {
                dependsOn(commonMain.get())
                kotlin.srcDir("src/closureNotificationMain/kotlin")
                dependencies { implementation("com.github.gycrosskit.diagnostics:diagnostics-dingtalk:${providers.gradleProperty("diagnosticsVersion").orElse("0.2.0-rc.11").get()}") }
            }
            androidMain.get().dependsOn(notificationMain)
            jvmMain.get().dependsOn(notificationMain)
            iosMain.get().dependsOn(notificationMain)
            ohosArm64Main.get().dependsOn(notificationMain)
        }
        if (providers.gradleProperty("diagnosticsNetwork").orNull == "true") {
            commonMain.get().kotlin.srcDir("src/networkCommonMain/kotlin")
            if (consumerVersion != "0.1.0" && consumerVersion !in (1..10).map { "0.2.0-rc.$it" }) {
                commonMain.get().kotlin.srcDir("src/fileChannelCommonMain/kotlin")
                commonTest.get().kotlin.srcDir("src/fileChannelCommonTest/kotlin")
            }
            val version = providers.gradleProperty("diagnosticsVersion").orElse("0.2.0-rc.11").get()
            // 旧版本远程回归仍可运行；新 Header API 从 rc.8 起提供。
            if (version !in (1..7).map { "0.2.0-rc.$it" } && version != "0.1.0") {
                commonMain.get().kotlin.srcDir("src/headerPolicyCommonMain/kotlin")
            }
            commonMain.dependencies { implementation("com.github.gycrosskit.diagnostics:diagnostics-ktor:${providers.gradleProperty("diagnosticsVersion").orElse("0.2.0-rc.11").get()}") }
            val networkJvmMain by creating {
                dependsOn(commonMain.get())
                kotlin.srcDir("src/networkJvmMain/kotlin")
                dependencies { implementation("com.github.gycrosskit.diagnostics:diagnostics-okhttp:${providers.gradleProperty("diagnosticsVersion").orElse("0.2.0-rc.11").get()}") }
            }
            jvmMain.get().dependsOn(networkJvmMain)
            androidMain.get().dependsOn(networkJvmMain)
        }
        if (providers.gradleProperty("diagnosticsProviders").orNull == "true" && consumerVersion != "0.1.0" && consumerVersion !in (1..12).map { "0.2.0-rc.$it" }) {
            commonMain.get().kotlin.srcDir("src/sdkCommonMain/kotlin")
            androidMain.get().kotlin.srcDir("src/sdkAndroidMain/kotlin")
            val provider = providers.gradleProperty("diagnosticsProvider").orElse("none").get()
            require(provider.matches(Regex("[a-z][a-z0-9-]{0,63}")))
            if (provider != "none") androidMain.dependencies {
                implementation("com.github.gycrosskit.diagnostics:diagnostics-$provider:$consumerVersion")
            }
            androidUnitTest.get().kotlin.srcDir("src/sdkAndroidUnitTest/kotlin")
            ohosArm64Main.dependencies { implementation("com.github.gycrosskit.diagnostics:diagnostics-kuikly:$consumerVersion") }
        }
        commonMain.dependencies { implementation("com.github.gycrosskit.diagnostics:diagnostics-core:${providers.gradleProperty("diagnosticsVersion").orElse("0.2.0-rc.11").get()}") }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies { implementation(kotlin("test")) }
        androidUnitTest.dependencies { implementation(kotlin("test")) }
    }
}
android {
    namespace = "io.github.gycrosskit.diagnostics.consumer"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
}

// 每次正式消费分别验证 0/1 个 SPI 实现，不启动任何 SDK。
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("diagnostics.provider", providers.gradleProperty("diagnosticsProvider").orElse("none").get())
}
