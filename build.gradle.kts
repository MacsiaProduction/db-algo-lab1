import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.process.ExecOperations

plugins {
    kotlin("jvm") version "2.3.21"
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

val mainSourceSet = the<org.gradle.api.tasks.SourceSetContainer>().named("main")

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
val profileEvent = providers.gradleProperty("profileEvent").orNull ?: "cpu"
val jmhInclude = providers.gradleProperty("jmhInclude").orNull
val jmhDataSize = providers.gradleProperty("jmhDataSize").orNull
val jmhResultFile = providers.gradleProperty("jmhResultFile").orNull
val jmhPreset = providers.gradleProperty("jmhPreset").orNull ?: "default"
val jmhMaxHeap = providers.gradleProperty("jmhMaxHeap").orNull
val lookupScalingOutput = providers.gradleProperty("lookupScalingOutput").orNull
    ?: "build/results/lookup_scaling.json"
val lookupScalingDataSizes = providers.gradleProperty("lookupScalingDataSizes").orNull
val lookupScalingRamBudgetGiB = providers.gradleProperty("lookupScalingRamBudgetGiB").orNull
    ?: "10"
val lookupScalingSeries = providers.gradleProperty("lookupScalingSeries").orNull
    ?: "all"
val lookupScalingWarmupRepetitions = providers.gradleProperty("lookupScalingWarmupRepetitions").orNull
    ?: "2"
val lookupScalingMeasurementRepetitions = providers.gradleProperty("lookupScalingMeasurementRepetitions").orNull
    ?: "10"
val lookupScalingPointRepetitions = providers.gradleProperty("lookupScalingPointRepetitions").orNull
    ?: "1"
val lookupScalingMaxHeap = providers.gradleProperty("lookupScalingMaxHeap").orNull
    ?: "32g"
val java23Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(23))
}

tasks.register<JavaExec>("lookupScaling") {
    group = "benchmark"
    description = "Runs the detailed lookup scaling benchmark runner for PerfectHash and LSH"
    dependsOn(tasks.named("classes"))
    classpath = mainSourceSet.get().runtimeClasspath
    mainClass.set("dbalgo.report.LookupScalingRunnerKt")
    javaLauncher.set(java23Launcher)
    maxHeapSize = lookupScalingMaxHeap
    args(
        "--output", lookupScalingOutput,
        "--ram-budget-gib", lookupScalingRamBudgetGiB,
        "--series", lookupScalingSeries,
        "--warmup-repetitions", lookupScalingWarmupRepetitions,
        "--measurement-repetitions", lookupScalingMeasurementRepetitions,
        "--point-repetitions", lookupScalingPointRepetitions,
    )
    if (lookupScalingDataSizes != null) {
        args("--sizes", lookupScalingDataSizes)
    }
}

data class JmhPreset(
    val warmupIterations: Int,
    val measurementIterations: Int,
    val fork: Int,
)

val activeJmhPreset = when (jmhPreset) {
    "quick" -> JmhPreset(
        warmupIterations = 1,
        measurementIterations = 3,
        fork = 1,
    )
    "gate" -> JmhPreset(
        warmupIterations = 5,
        measurementIterations = 16,
        fork = 2,
    )
    "diag" -> JmhPreset(
        warmupIterations = 2,
        measurementIterations = 12,
        fork = 1,
    )
    "lshGate" -> JmhPreset(
        warmupIterations = 3,
        measurementIterations = 12,
        fork = 2,
    )
    "phLookupGate" -> JmhPreset(
        warmupIterations = 5,
        measurementIterations = 20,
        fork = 2,
    )
    "phBuildGate" -> JmhPreset(
        warmupIterations = 1,
        measurementIterations = 5,
        fork = 1,
    )
    "precise" -> JmhPreset(
        warmupIterations = 3,
        measurementIterations = 8,
        fork = 1,
    )
    else -> JmhPreset(
        warmupIterations = 2,
        measurementIterations = 3,
        fork = 1,
    )
}

if (profile) {
    val osName = System.getProperty("os.name").lowercase()
    val apPlatform = when {
        osName.contains("linux") -> "linux-x64"
        osName.contains("mac") || osName.contains("darwin") -> "macos"
        else -> error("Unsupported async-profiler platform: $osName")
    }
    val apArchiveExt = if (apPlatform == "macos") "zip" else "tar.gz"
    val apLibName = if (apPlatform == "macos") "libasyncProfiler.dylib" else "libasyncProfiler.so"
    val apLib = apDir.get().file("lib/$apLibName").asFile
    val jfrconv = apDir.get().file("bin/jfrconv").asFile

    val downloadAsyncProfiler by tasks.registering {
        description = "Downloads async-profiler $apVersion for the current OS"
        outputs.file(apLib)
        onlyIf { !apLib.exists() }
        doLast {
            val url = "https://github.com/async-profiler/async-profiler/releases/" +
                "download/v$apVersion/async-profiler-$apVersion-$apPlatform.$apArchiveExt"
            val archiveFile = temporaryDir.resolve("ap.$apArchiveExt")
            serviceOf<ExecOperations>().exec {
                commandLine("curl", "-fSL", "-o", archiveFile.absolutePath, url)
            }
            val archiveTree = if (apArchiveExt == "zip") {
                zipTree(archiveFile)
            } else {
                tarTree(resources.gzip(archiveFile))
            }
            project.copy {
                from(archiveTree.matching {
                    include("async-profiler-$apVersion-$apPlatform/**")
                }) {
                    eachFile {
                        relativePath = org.gradle.api.file.RelativePath(
                            true,
                            *relativePath.segments.drop(1).toTypedArray(),
                        )
                    }
                    includeEmptyDirs = false
                }
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
                serviceOf<ExecOperations>().exec {
                    commandLine(
                        jfrconv.absolutePath, "--cpu", "--lines",
                        "-o", "html",
                        jfr.absolutePath,
                        flamegraphDir.resolve("$name.html").absolutePath
                    )
                }
                serviceOf<ExecOperations>().exec {
                    commandLine(
                        jfrconv.absolutePath, "--cpu", "--lines",
                        "-o", "collapsed",
                        jfr.absolutePath,
                        collapsedDir.resolve("$name.txt").absolutePath
                    )
                }
                serviceOf<ExecOperations>().exec {
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
    warmupIterations = activeJmhPreset.warmupIterations
    iterations = activeJmhPreset.measurementIterations
    fork = activeJmhPreset.fork
    resultFormat = "JSON"
    if (jmhResultFile != null) {
        resultsFile.set(file(jmhResultFile))
    }
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
        val osName = System.getProperty("os.name").lowercase()
        val apLibName = if (osName.contains("mac") || osName.contains("darwin")) {
            "libasyncProfiler.dylib"
        } else {
            "libasyncProfiler.so"
        }
        val libPath = apDir.get().file("lib/$apLibName").asFile.absolutePath
        val outDir = profileDir.get().asFile.absolutePath
        profilers.add("async:libPath=$libPath;event=$profileEvent;output=jfr;dir=$outDir")
    }
    if (jmhMaxHeap != null) {
        jvmArgsAppend.add("-Xmx$jmhMaxHeap")
    }
}
