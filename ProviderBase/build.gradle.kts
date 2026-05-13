plugins {
    id("com.android.library")
    id("kotlin-android")
}

android {
    namespace = "com.baseprovider"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    compileOnly("com.lagradost:cloudstream3:pre-release")
    implementation(kotlin("stdlib"))
    implementation("com.github.Blatzar:NiceHttp:0.4.16")
    implementation("org.jsoup:jsoup:1.22.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.mozilla:rhino:1.9.0")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("me.xdrop:fuzzywuzzy:1.4.0")
}
