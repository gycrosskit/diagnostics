plugins { kotlin("multiplatform"); `maven-publish` }
kotlin {
    ohosArm64()
    sourceSets {
        commonMain.dependencies { api(project(":diagnostics-core")) }
        ohosArm64Main.dependencies {
            api("com.tencent.kuikly-open:core:2.29.1-2.0.21-ohos")
            implementation("com.tencent.kuikly-open:compose:2.29.1-2.0.21-ohos")
        }
    }
}
publishing { repositories.maven { name = "staging"; url = uri(rootProject.layout.buildDirectory.dir("maven")) } }
