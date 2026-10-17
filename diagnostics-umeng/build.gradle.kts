plugins { kotlin("multiplatform"); id("com.android.library"); `maven-publish` }

kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    sourceSets {
        androidMain.dependencies {
            api(project(":diagnostics-core"))
            implementation("com.umeng.umsdk:common:10.0.1")
            implementation("com.umeng.umsdk:asms:1.8.7.2")
            implementation("com.umeng.umsdk:apm:2.1.0")
            // APM 保留 OkHttp/Okio 引用但 POM 未声明；适配组件必须可独立通过 R8。
            implementation("com.squareup.okhttp3:okhttp:5.2.1")
        }
        androidUnitTest.dependencies {
            implementation(kotlin("test-junit"))
            implementation("org.robolectric:robolectric:4.16.1")
        }
    }
}

android {
    namespace = "io.github.gycrosskit.diagnostics.umeng"
    compileSdk = 36
    defaultConfig { minSdk = 24; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets.getByName("main").resources.srcDir("src/androidMain/resources")
    testOptions.unitTests.isIncludeAndroidResources = true
}

publishing { repositories.maven { name = "staging"; url = uri(rootProject.layout.buildDirectory.dir("maven")) } }
