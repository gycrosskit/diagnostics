plugins { kotlin("multiplatform"); id("com.android.library"); `maven-publish` }

kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    sourceSets {
        androidMain.dependencies {
            api(project(":diagnostics-core"))
            implementation("com.tencent.bugly:bugly-pro:4.4.8.2") {
                exclude(group = "org.jetbrains.kotlin", module = "kotlin-android-extensions-runtime")
            }
            implementation("com.tencent.tdos-diagnose:diagnose:0.4.11")
            implementation("com.tencent.tdos-diagnose:logger-static:0.4.11")
        }
        androidUnitTest.dependencies {
            implementation(kotlin("test-junit"))
            implementation("org.robolectric:robolectric:4.16.1")
        }
    }
}

android {
    namespace = "io.github.gycrosskit.diagnostics.bugly"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions.unitTests.isIncludeAndroidResources = true
}

publishing { repositories.maven { name = "staging"; url = uri(rootProject.layout.buildDirectory.dir("maven")) } }
