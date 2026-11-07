plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

val ghosttyWasm = configurations.create("ghosttyWasm") { isCanBeConsumed = false }
dependencies {
    add(ghosttyWasm.name, project(mapOf("path" to ":library", "configuration" to "ghosttyWasmBinary")))
}
val copyGhosttyWasm = tasks.register<Sync>("copyGhosttyWasm") {
    from(ghosttyWasm)
    into(layout.buildDirectory.dir("ghostty-wasm"))
}

kotlin {
    jvm("shell")
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.components.resources)
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        wasmJsMain {
            resources.srcDir(copyGhosttyWasm)
        }
        wasmJsMain.dependencies {
            implementation(project(":library-compose"))
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.xchacha20_poly1305.libghostty.demo.resources"
}

val offlineDirectory = layout.buildDirectory.dir("offline")
val packageOffline = tasks.register<Exec>("packageOffline") {
    dependsOn("wasmJsBrowserDistribution")
    val distribution = layout.buildDirectory.dir("dist/wasmJs/productionExecutable")
    inputs.dir(distribution)
    inputs.files("scripts/package-offline.py", "scripts/offline-runtime.js")
    inputs.file(layout.buildDirectory.file("compose/skiko-runtime-processed-wasmjs/skiko.wasm"))
    outputs.dir(offlineDirectory)
    workingDir(rootProject.projectDir)
    // Script and Skiko paths are explicit so this task works from any invocation directory.
    commandLine("python3", project.file("scripts/package-offline.py").absolutePath,
        distribution.get().asFile.absolutePath, offlineDirectory.get().asFile.absolutePath,
        "--skiko", layout.buildDirectory.file("compose/skiko-runtime-processed-wasmjs/skiko.wasm").get().asFile.absolutePath)
}
val offlineZip = tasks.register<Zip>("offlineZip") {
    dependsOn(packageOffline)
    from(offlineDirectory)
    archiveFileName.set(providers.gradleProperty("VERSION_NAME").map { "demo-web-$it.zip" })
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}
