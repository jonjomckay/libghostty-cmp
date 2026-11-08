import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val demoVersion = providers.gradleProperty("VERSION_NAME").get()
val signingStorePassword = providers.environmentVariable("ANDROID_DEMO_STORE_PASSWORD").orNull
val signingAlias = providers.environmentVariable("ANDROID_DEMO_KEY_ALIAS").orNull
val signingKeyPassword = providers.environmentVariable("ANDROID_DEMO_KEY_PASSWORD").orNull
val signingReady = listOf(signingStorePassword, signingAlias, signingKeyPassword).all { !it.isNullOrEmpty() }

android {
    namespace = "io.github.xchacha20_poly1305.libghostty.demo"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.xchacha20_poly1305.libghostty.demo"
        minSdk = 23
        targetSdk = 37
        versionName = demoVersion
        val parts = demoVersion.substringBefore('-').split('.').map(String::toInt)
        require(parts.size == 3 && parts.all { it >= 0 } && parts.drop(1).all { it < 1000 }) {
            "Android demo requires a major.minor.patch version with minor and patch below 1000"
        }
        val code = parts[0].toLong() * 1_000_000 + parts[1] * 1000 + parts[2]
        require(code in 1..2_100_000_000L) { "Android demo versionCode is out of range" }
        versionCode = code.toInt()
    }
    if (signingReady) {
        val releaseSigning = signingConfigs.create("release") {
            storeFile = rootProject.file(providers.environmentVariable("ANDROID_DEMO_KEYSTORE").orElse("release.keystore").get())
            storePassword = signingStorePassword
            keyAlias = signingAlias
            keyPassword = signingKeyPassword
        }
        buildTypes.getByName("release").signingConfig = releaseSigning
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// AGP compiles apps with its built-in Kotlin, older than the libraries' Kotlin; raise it to read their metadata.
// Raising it requires kotlin.compiler.runViaBuildToolsApi, which lifts the plugin/compiler version check.
configurations.matching { it.name in setOf("kotlinCompilerClasspath", "kotlinBuildToolsApiClasspath") }
    .configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") useVersion(libs.versions.kotlin.get())
        }
    }

dependencies {
    implementation(project(":demo-shared"))
    implementation(project(":library-compose"))
    implementation(project(":library-extras"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.material3)
}

// Package only a signed release APK, using AGP's artifact dependency rather than a guessed output path.
androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    tasks.register<Sync>("packageReleaseApk") {
        from(variant.artifacts.get(SingleArtifact.APK)) {
            include("*.apk")
            rename { "demo-android-$demoVersion.apk" }
        }
        into(layout.buildDirectory.dir("distributions"))
        doFirst {
            check(signingReady) { "Android demo release packaging requires all ANDROID_DEMO_* signing credentials" }
        }
    }
}
