plugins {
    kotlin("multiplatform") version "2.2.21-1.0.0" apply false
    id("com.android.library") version "8.10.1" apply false
}
allprojects {
    group = providers.environmentVariable("GROUP").orElse("com.github.gycrosskit.diagnostics").get()
    version = providers.environmentVariable("VERSION").orElse("0.2.0-rc.1").get()
}
