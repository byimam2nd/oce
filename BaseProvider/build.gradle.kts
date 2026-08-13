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

    sourceSets {
        getByName("main") {
            resources.srcDirs("src/main/kotlin/com/baseprovider/config")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

val cloudstream3Jar: String? = System.getenv("CLOUDSTREAM3_JAR")?.takeIf { it.isNotBlank() }

val cloudstream3Jar: String? = System.getenv("CLOUDSTREAM3_JAR")?.takeIf { it.isNotBlank() }

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.github.Blatzar:NiceHttp:0.4.16")
    implementation("org.jsoup:jsoup:1.22.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.mozilla:rhino:1.9.0")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("me.xdrop:fuzzywuzzy:1.4.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20231013")

    // CloudStream3 classes (TvType, Log, WebViewResolver, ...) untuk unit test.
    // Bukan artifact maven — di-resolve plugin sebagai JAR hasil download
    // (https://github.com/recloudstream/cloudstream/releases/download/<release>/classes.jar).
    // Hanya dipakai saat CLOUDSTREAM3_JAR diset (langkah unit test di CI);
    // build provider normal tidak terpengaruh.
    if (cloudstream3Jar != null) {
        compileOnly(files(cloudstream3Jar))
        testImplementation(files(cloudstream3Jar))
    }
}
