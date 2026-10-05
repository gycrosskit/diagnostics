plugins { kotlin("multiplatform"); id("com.android.library"); `maven-publish` }
kotlin {
    androidTarget { publishLibraryVariants("release") }
    jvm()
    sourceSets {
        commonMain.dependencies { api(project(":diagnostics-core")) }
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies { api("com.squareup.okhttp3:okhttp:4.12.0") }
        }
        androidMain.get().dependsOn(jvmSharedMain)
        jvmMain.get().dependsOn(jvmSharedMain)
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
        }
    }
}
android { namespace = "io.github.gycrosskit.diagnostics.okhttp"; compileSdk = 36; defaultConfig { minSdk = 24 } }
publishing { repositories.maven { name = "staging"; url = uri(rootProject.layout.buildDirectory.dir("maven")) } }
