plugins { kotlin("multiplatform"); id("com.android.library"); `maven-publish` }
kotlin {
    androidTarget { publishLibraryVariants("release") }
    jvm()
    iosArm64(); iosSimulatorArm64(); iosX64(); ohosArm64()
    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-io-core:0.9.0-1.0.0")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2-1.0.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.1-1.0.0")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        val jvmSharedMain by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmSharedMain)
        jvmMain.get().dependsOn(jvmSharedMain)
        nativeMain.get().dependencies { }
    }
}
android {
    namespace = "io.github.gycrosskit.diagnostics"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
}
publishing { repositories.maven { name = "staging"; url = uri(rootProject.layout.buildDirectory.dir("maven")) } }
