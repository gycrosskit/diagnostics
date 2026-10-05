plugins { kotlin("multiplatform"); id("com.android.library"); `maven-publish` }
kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    jvm { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }; iosArm64(); iosSimulatorArm64(); iosX64(); ohosArm64()
    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            api("io.ktor:ktor-client-core:3.3.3-1.1.0-04")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.1-1.0.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("io.ktor:ktor-client-mock:3.3.3-1.1.0-04")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2-1.0.0")
        }
        val jvmSharedMain by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmSharedMain)
        jvmMain.get().dependsOn(jvmSharedMain)
        androidMain.dependencies { implementation("io.ktor:ktor-client-android:3.3.3-1.1.0-04") }
        jvmMain.dependencies { implementation("io.ktor:ktor-client-cio:3.3.3-1.1.0-04") }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.3.3-1.1.0-04") }
        ohosArm64Main.dependencies {
            implementation("io.ktor:ktor-client-curl:3.3.3-1.1.0-04")
            implementation(project(":diagnostics-core"))
        }
    }
}
android { namespace = "io.github.gycrosskit.diagnostics.dingtalk"; compileSdk = 36; compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }; defaultConfig { minSdk = 24 } }
publishing { repositories.maven { name = "staging"; url = uri(rootProject.layout.buildDirectory.dir("maven")) } }
