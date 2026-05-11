import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.util.Properties

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        gradlePluginPortal()
    }

    configurations.all {
        resolutionStrategy {
            // Force jadb to use stable version instead of master-SNAPSHOT
            force("com.github.vidstige:jadb:v1.2.1")
        }
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.13.2")
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
        
        // Code quality plugins
        classpath("org.jlleitschuh.gradle:ktlint-gradle:14.2.0")
        classpath("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.23.8")
    }
}

allprojects {
    configurations.all {
        resolutionStrategy {
            // Force jadb to use stable version instead of master-SNAPSHOT
            force("com.github.vidstige:jadb:v1.2.1")
        }
    }

    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        gradlePluginPortal()
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

// Load local.properties for dynamic configuration
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    cloudstream {
        // Dynamic repo from env or local.properties or default
        val repo = System.getenv("GITHUB_REPOSITORY") 
            ?: localProperties.getProperty("REPO_URL")
            ?: "https://github.com/imam2nd/cloudstream-extensions-phisher"
        setRepo(repo)
        
        // Dynamic authors from local.properties or default
        val authorsStr = localProperties.getProperty("AUTHORS", "Imam2nd")
        authors = authorsStr.split(",").map { it.trim() }
    }

    android {
        // Dynamic namespace from project name
        namespace = "com.${project.name.lowercase().replace("provider", "")}"

        defaultConfig {
            // Dynamic SDK versions from local.properties or env
            val minSdkProp = localProperties.getProperty("MIN_SDK", "21")
            val compileSdkProp = localProperties.getProperty("COMPILE_SDK", "35")
            val targetSdkProp = localProperties.getProperty("TARGET_SDK", "35")
            
            minSdk = minSdkProp.toInt()
            compileSdkVersion(compileSdkProp.toInt())
            targetSdk = targetSdkProp.toInt()
        }

        compileOptions {
            // Dynamic Java version
            val javaVersionProp = localProperties.getProperty("JAVA_VERSION", "1_8")
            val javaVersion = JavaVersion.valueOf("VERSION_$javaVersionProp")
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val implementation by configurations
        val cloudstream by configurations
        
        // Dynamic CloudStream version from local.properties or env
        val cloudstreamVersion = localProperties.getProperty("CLOUDSTREAM_VERSION", "pre-release")
        cloudstream("com.lagradost:cloudstream3:$cloudstreamVersion")

        // Other dependencies - versions from local.properties for easy updates
        val kotlinVersion = localProperties.getProperty("KOTLIN_VERSION", "stdlib")
        val niceHttpVersion = localProperties.getProperty("NICEHTTP_VERSION", "0.4.16")
        val jsoupVersion = localProperties.getProperty("JSOUP_VERSION", "1.22.1")
        val annotationVersion = localProperties.getProperty("ANNOTATION_VERSION", "1.9.1")
        val jacksonVersion = localProperties.getProperty("JACKSON_VERSION", "2.20.1")
        val coroutinesVersion = localProperties.getProperty("COROUTINES_VERSION", "1.10.2")
        val rhinoVersion = localProperties.getProperty("RHINO_VERSION", "1.9.0")
        val fuzzywuzzyVersion = localProperties.getProperty("FUZZYWUPPY_VERSION", "1.4.0")
        val gsonVersion = localProperties.getProperty("GSON_VERSION", "2.13.2")
        val serializationVersion = localProperties.getProperty("SERIALIZATION_VERSION", "1.9.0")
        val jadbVersion = localProperties.getProperty("JADB_VERSION", "v1.2.1")
        val bouncycastleVersion = localProperties.getProperty("BC_VERSION", "1.70")

        implementation(kotlin(kotlinVersion))
        implementation("com.github.Blatzar:NiceHttp:$niceHttpVersion")
        implementation("org.jsoup:jsoup:$jsoupVersion")
        implementation("androidx.annotation:annotation:$annotationVersion")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
        implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
        implementation("org.mozilla:rhino:$rhinoVersion")
        implementation("me.xdrop:fuzzywuzzy:$fuzzywuzzyVersion")
        implementation("com.google.code.gson:gson:$gsonVersion")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
        implementation("com.github.vidstige:jadb:$jadbVersion")
        implementation("org.bouncycastle:bcpkix-jdk15on:$bouncycastleVersion")
    }

    // ============================================
    // KTLINT CONFIGURATION
    // ============================================
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        android.set(true)
        outputToConsole.set(true)
        outputColorName.set("RED")
        ignoreFailures.set(false)
        enableExperimentalRules.set(false)
        
        filter {
            exclude("**/generated/**")
            exclude("**/generated_sync/**")
            exclude("**/build/**")
        }
    }

    // ============================================
    // DETEKT CONFIGURATION
    // ============================================
    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        autoCorrect = true
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        baseline = file("$rootDir/config/detekt/baseline.yml")

        parallel = true
        debug = false

        source = objects.fileCollection().from(
            "src/main/java",
            "src/main/kotlin"
        )

        reports {
            html.required.set(true)
            xml.required.set(true)
            txt.required.set(true)
            sarif.required.set(false)
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
