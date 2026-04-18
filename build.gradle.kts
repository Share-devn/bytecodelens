plugins {
    id("application")
    id("java")
    id("org.openjfx.javafxplugin") version "0.1.0"
    // Packages the app + every dependency (JavaFX modules included) into one runnable jar.
    // Produces build/libs/bytecodelens-<version>-all.jar which is what ends up on the
    // Releases page and in the README's `java -jar ...` example.
    id("com.gradleup.shadow") version "8.3.5"
}

group = "dev.share"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

javafx {
    version = "21.0.4"
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.graphics")
}

// The JavaFX plugin's default behaviour resolves platform-specific artefacts only for
// the current build host. For the fat jar on the Releases page we want all three
// desktop platforms bundled, so the same BytecodeLens-1.0.0-all.jar runs anywhere
// with just Java 21+ installed. We add the missing classifiers explicitly.
val javafxVersion = "21.0.4"
val javafxModules = listOf("base", "controls", "fxml", "graphics")
val javafxPlatforms = listOf("win", "mac", "mac-aarch64", "linux")

dependencies {
    implementation("io.github.mkpaz:atlantafx-base:2.0.1")
    implementation("org.kordamp.ikonli:ikonli-javafx:12.3.1")
    implementation("org.kordamp.ikonli:ikonli-feather-pack:12.3.1")
    implementation("org.kordamp.ikonli:ikonli-materialdesign2-pack:12.3.1")
    implementation("org.controlsfx:controlsfx:11.2.1")
    implementation("org.fxmisc.richtext:richtextfx:0.11.3")

    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-tree:9.7")
    implementation("org.ow2.asm:asm-util:9.7")
    implementation("org.ow2.asm:asm-commons:9.7")

    implementation("org.benf:cfr:0.152")
    implementation("org.vineflower:vineflower:1.11.1")
    implementation("org.bitbucket.mstrobel:procyon-compilertools:0.6.0")

    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.6")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Cross-platform JavaFX natives for the fat jar. These only affect the shadow task —
    // regular `run` / `test` still use the current-host artefacts from the javafx plugin.
    javafxPlatforms.forEach { p ->
        javafxModules.forEach { m ->
            "runtimeOnly"("org.openjfx:javafx-$m:$javafxVersion:$p")
        }
    }
}

application {
    // Launcher (a plain class, not extends Application) is the fat-jar entry point —
    // JavaFX 17+ refuses to bootstrap when the declared Main-Class extends Application
    // on a classpath launch. `./gradlew run` doesn't hit this path because the JavaFX
    // plugin adds modulepath flags, but `java -jar ...` needs the indirection.
    mainClass.set("dev.share.bytecodelens.Launcher")
    applicationDefaultJvmArgs = listOf(
        "--add-opens", "javafx.graphics/com.sun.javafx.util=ALL-UNNAMED",
        "-Xmx2g",
        "-Dprism.lcdtext=true",
        "-Dprism.text=t2k",
        "-Dprism.subpixeltext=on",
        "-Dprism.forceGPU=true",
        "-Dprism.allowhidpi=true"
    )
}

tasks.test {
    useJUnitPlatform()
    // Forward -Dperf=1 / -Dperf.jar=... / -Dperf.runs=... to the test JVM for LoadJarPerfProbe.
    listOf("perf", "perf.jar", "perf.runs").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    // Perf probe on real-world jars needs more heap than the default 512m.
    if (System.getProperty("perf") == "1") {
        maxHeapSize = "2g"
    }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
    options.compilerArgs.add("-Xlint:-serial")
    options.compilerArgs.add("-Xlint:-processing")
}

// --- Fat jar -----------------------------------------------------------------
// `./gradlew shadowJar` -> build/libs/BytecodeLens-<version>-all.jar
// Runnable as `java -jar BytecodeLens-<version>-all.jar` on Windows / macOS / Linux
// (x64 and arm64) with only Java 21+ installed.
tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
    // AtlantaFX ships a META-INF/versions hierarchy that clashes on some hosts; exclude
    // signature files that would otherwise invalidate the merged jar.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    // Deduplicate LICENSE/NOTICE/module-info from every dep — the JVM chokes on duplicate
    // module-info.class entries; keeping only one copy is safe because each dep's licence
    // is acknowledged in the NOTICE file.
    exclude("module-info.class")
    exclude("META-INF/versions/*/module-info.class")
    manifest {
        attributes(
            "Main-Class" to "dev.share.bytecodelens.Launcher",
            "Implementation-Title" to "BytecodeLens",
            "Implementation-Version" to project.version
        )
    }
}

tasks.build { dependsOn(tasks.shadowJar) }

// --- jpackage input staging --------------------------------------------------
// Called by the GitHub Actions workflow after it builds the regular jar. Copies
// every runtime dep into build/jpackage-input/ so jpackage can bundle a JRE +
// dependencies into a native installer (.msi / .dmg / .deb).
tasks.register<Copy>("copyRuntimeDeps") {
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("jpackage-input"))
    // Host-specific natives only — the matrix invokes jpackage on the matching OS
    // runner, so bundling foreign classifiers just bloats the installer.
    val isWin = org.gradle.internal.os.OperatingSystem.current().isWindows
    val isMac = org.gradle.internal.os.OperatingSystem.current().isMacOsX
    val isLinux = org.gradle.internal.os.OperatingSystem.current().isLinux
    exclude { e ->
        val n = e.file.name
        (n.contains("-win") && !isWin) ||
        (n.contains("-linux") && !isLinux) ||
        (n.contains("-mac") && !isMac)
    }
}
