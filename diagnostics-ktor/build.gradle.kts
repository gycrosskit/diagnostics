plugins { kotlin("multiplatform"); id("com.android.library"); `maven-publish` }
kotlin {
    androidTarget { publishLibraryVariants("release") }
    jvm { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }; iosArm64(); iosSimulatorArm64(); iosX64(); ohosArm64()
    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            api(project(":diagnostics-core"))
            api("io.ktor:ktor-client-core:3.3.3-1.1.0-04")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("io.ktor:ktor-client-mock:3.3.3-1.1.0-04")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2-1.0.0")
            implementation("io.ktor:ktor-client-content-negotiation:3.3.3-1.1.0-04")
        }
    }
}
android { namespace = "io.github.gycrosskit.diagnostics.ktor"; compileSdk = 36; defaultConfig { minSdk = 24 } }
publishing { repositories.maven { name = "staging"; url = uri(rootProject.layout.buildDirectory.dir("maven")) } }
