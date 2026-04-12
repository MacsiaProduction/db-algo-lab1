plugins {
    kotlin("jvm") version "2.3.20-RC2"
    id("me.champeau.jmh") version "0.7.3"
}

group = "dbalgo"
version = "1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(23)
}

dependencies {
    implementation("org.jetbrains:annotations:24.1.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmh("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

tasks.test {
    useJUnitPlatform()
}

// --- Profiling support (activated via ./gradlew jmh -Pprofile) ---

val profile = project.hasProperty("profile")
val apVersion = "4.0"
val apDir = layout.buildDirectory.dir("async-profiler")
val profileDir = layout.buildDirectory.dir("reports/profile")
val jmhInclude = providers.gradleProperty("jmhInclude").orNull
val jmhDataSize = providers.gradleProperty("jmhDataSize").orNull

if (profile) {
    val apLib = apDir.get().file("lib/libasyncProfiler.dylib").asFile
    val jfrconv = apDir.get().file("bin/jfrconv").asFile

    val downloadAsyncProfiler by tasks.registering {
        description = "Downloads async-profiler $apVersion for macOS"
        outputs.file(apLib)
        onlyIf { !apLib.exists() }
        doLast {
            val url = "https://github.com/async-profiler/async-profiler/releases/" +
                "download/v$apVersion/async-profiler-$apVersion-macos.zip"
            val zipFile = temporaryDir.resolve("ap.zip")
            project.exec { commandLine("curl", "-fSL", "-o", zipFile.absolutePath, url) }
            project.exec { commandLine("unzip", "-qo", zipFile.absolutePath, "-d", temporaryDir.absolutePath) }
            project.copy {
                from(temporaryDir.resolve("async-profiler-$apVersion-macos"))
                into(apDir)
            }
        }
    }

    val processProfiles by tasks.registering {
        description = "Converts JFR profiles to flame graphs, collapsed stacks, and JSON"
        group = "benchmark"
        doLast {
            val profDir = profileDir.get().asFile
            val jfrFiles = profDir.walkTopDown().filter { it.extension == "jfr" }.toList()
            if (jfrFiles.isEmpty()) {
                logger.warn("No .jfr files found in $profDir")
                return@doLast
            }
            val flamegraphDir = profDir.resolve("flamegraphs").apply { mkdirs() }
            val collapsedDir = profDir.resolve("collapsed").apply { mkdirs() }
            val jsonDir = profDir.resolve("json").apply { mkdirs() }

            jfrFiles.forEach { jfr ->
                val name = jfr.parentFile.name
                logger.lifecycle("  $name → flame graph + collapsed + JSON")
                project.exec {
                    commandLine(
                        jfrconv.absolutePath, "--cpu", "--lines",
                        "-o", "html",
                        jfr.absolutePath,
                        flamegraphDir.resolve("$name.html").absolutePath
                    )
                }
                project.exec {
                    commandLine(
                        jfrconv.absolutePath, "--cpu", "--lines",
                        "-o", "collapsed",
                        jfr.absolutePath,
                        collapsedDir.resolve("$name.txt").absolutePath
                    )
                }
                project.exec {
                    commandLine("jfr", "print", "--json", "--stack-depth", "64", jfr.absolutePath)
                    standardOutput = jsonDir.resolve("$name.json").outputStream()
                }
            }
            logger.lifecycle("Flame graphs → ${flamegraphDir.absolutePath}")
            logger.lifecycle("Collapsed stacks → ${collapsedDir.absolutePath}")
            logger.lifecycle("JSON profiles → ${jsonDir.absolutePath}")
        }
    }

    tasks.named("jmh") {
        dependsOn(downloadAsyncProfiler)
        finalizedBy(processProfiles)
    }
}

jmh {
    warmupIterations = 2
    iterations = 3
    fork = 1
    benchmarkMode = listOf("thrpt", "sample")
    resultFormat = "JSON"
    if (jmhInclude != null) {
        includes.add(jmhInclude)
    }
    if (jmhDataSize != null) {
        benchmarkParameters.put(
            "dataSize",
            objects.listProperty(String::class.java).apply { set(listOf(jmhDataSize)) }
        )
    }
    if (profile) {
        val libPath = apDir.get().file("lib/libasyncProfiler.dylib").asFile.absolutePath
        val outDir = profileDir.get().asFile.absolutePath
        profilers.add("async:libPath=$libPath;output=jfr;dir=$outDir")
    }
}
