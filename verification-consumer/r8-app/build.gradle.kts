plugins { id("com.android.application") }

val consumerVersion = providers.gradleProperty("diagnosticsVersion").get()
val provider = providers.gradleProperty("diagnosticsProvider").orElse("none").get()
require(provider.matches(Regex("[a-z][a-z0-9-]{0,63}")))

android {
    namespace = "io.github.gycrosskit.diagnostics.consumer.r8"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.gycrosskit.diagnostics.consumer.r8"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "DIAGNOSTICS_PROVIDER", "\"$provider\"")
    }
    buildFeatures { buildConfig = true }
    buildTypes.getByName("release") {
        isMinifyEnabled = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.github.gycrosskit.diagnostics:diagnostics-core:$consumerVersion")
    if (provider != "none") implementation("com.github.gycrosskit.diagnostics:diagnostics-$provider:$consumerVersion")
}

// 必须检查真实压缩 APK 中的 SPI，不能只把 Java/Kotlin 编译当作消费成功。
tasks.register<Exec>("verifyR8Release") {
    dependsOn("assembleRelease")
    commandLine("python3", rootProject.file("verify-r8.py"),
        "--provider", provider,
        "--apk", layout.buildDirectory.file("outputs/apk/release/r8-app-release-unsigned.apk").get().asFile,
        "--mapping", layout.buildDirectory.file("outputs/mapping/release/mapping.txt").get().asFile)
}
