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
            // Bugly 保留 SSE API 引用但 POM 漏声明；关闭 monitor 仍需完整依赖供 R8 解析。
            implementation("com.squareup.okhttp3:okhttp-sse:5.2.1")
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
    defaultConfig { minSdk = 24; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets.getByName("main").resources.srcDir("src/androidMain/resources")
    testOptions.unitTests.isIncludeAndroidResources = true
}

publishing { repositories.maven { name = "staging"; url = uri(rootProject.layout.buildDirectory.dir("maven")) } }
