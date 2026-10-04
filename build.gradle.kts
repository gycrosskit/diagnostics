plugins {
    kotlin("multiplatform") version "2.2.21-1.0.0" apply false
    id("com.android.library") version "8.10.1" apply false
}
allprojects {
    group = providers.environmentVariable("GROUP").orElse("com.github.gycrosskit.diagnostics").get()
    version = providers.environmentVariable("VERSION").orElse("0.2.0-rc.3").get()
    plugins.withId("maven-publish") {
        extensions.configure<org.gradle.api.publish.PublishingExtension> {
            publications.withType<org.gradle.api.publish.maven.MavenPublication>().configureEach {
                pom {
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    url.set("https://github.com/gycrosskit/diagnostics")
                }
            }
        }
    }
}
