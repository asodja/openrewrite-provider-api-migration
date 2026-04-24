// Discovery init-script: configuration-only. Extracts per-source-set directories + classpaths into
// a JSON manifest, then aborts before any task executes. Pairs with tools/StandaloneRunner.java to
// run OpenRewrite recipes without triggering Gradle's compile cascade.
//
// Usage (from the target project's directory):
//     ./gradlew --init-script /path/to/discovery-init.gradle.kts help 2>&1 | grep -v '^FAILURE' | grep -v '^BUILD' || true
//     cat .rewrite-manifest.json
//
// The deliberate GradleException at the end of projectsEvaluated is how we short-circuit before any
// task runs. Gradle treats it as a failed build, but the manifest is already on disk.

import java.io.File

// Collect per-project inside that project's afterEvaluate so classpath resolution has the project
// lock. Gradle 9 rejects cross-project configuration resolution from gradle.projectsEvaluated,
// which is why an earlier draft produced empty classpaths across Spring.
val collectedEntries = java.util.concurrent.CopyOnWriteArrayList<String>()
val collectedRoots = java.util.concurrent.CopyOnWriteArraySet<String>()

gradle.allprojects {
    // Only act on the primary build. Included builds get their own init-script invocations.
    if (gradle.parent != null) return@allprojects

    afterEvaluate {
        collectedRoots.add(projectDir.absolutePath)
        val ssc = extensions.findByType(org.gradle.api.tasks.SourceSetContainer::class.java)
            ?: return@afterEvaluate
        // Three-way classification:
        //
        //   production           — not a Gradle plugin at all (regular library / application).
        //                          Never migrated.
        //
        //   publishedGradlePlugin — a Gradle plugin that's intended to be CONSUMED OUTSIDE this
        //                          build (published to Maven Central / the Gradle Plugin Portal).
        //                          Examples: Kotlin's `libraries/tools/kotlin-gradle-plugin`,
        //                          Elasticsearch's `build-tools`, Shadow, Spotless.
        //                          Auto-migrating these is dangerous: they support many Gradle
        //                          versions back and changing their public API breaks users on
        //                          older Gradle. Excluded by default; opt in via
        //                          `--include-published-plugins` on the runner.
        //
        //   buildLogic           — Gradle plugin code that stays INSIDE this build (buildSrc,
        //                          pluginManagement-included builds, convention plugins). Safe
        //                          to migrate: the whole build moves forward together. Default
        //                          target of the migration.
        //
        // The "publishes?" signal is the key discriminator between the two Gradle-plugin kinds.
        // A project publishing to Maven Central / Ivy / Gradle Plugin Portal declares one of
        // these publish plugins; buildSrc / convention-plugin targets typically do not.
        val appliesJgp = pluginManager.hasPlugin("java-gradle-plugin")
        val publishes = pluginManager.hasPlugin("maven-publish")
                || pluginManager.hasPlugin("ivy-publish")
                || pluginManager.hasPlugin("com.gradle.plugin-publish")
        val kind = when {
            appliesJgp && publishes -> "publishedGradlePlugin"
            appliesJgp              -> "buildLogic"
            else                    -> "production"
        }
        ssc.forEach { ss ->
            val srcDirs = ss.allSource.srcDirs.filter { it.exists() }.map { it.absolutePath }
            if (srcDirs.isEmpty()) return@forEach
            // We deliberately do NOT resolve compile classpath per source set here. Two reasons:
            //   1. Gradle 9's project-lock rules reject some cross-project resolutions.
            //   2. On some projects (Spring's :integration-tests) eager classpath resolution
            //      trips "Cannot mutate the hierarchy of configuration" ordering errors.
            // The standalone runner gets a shared Gradle-API classpath from the distribution lib/
            // dir instead — enough for type-attribution on build-logic Java files.
            collectedEntries += buildString {
                append("{\"project\":")
                append(path.jsonEncode())
                append(",\"sourceSet\":")
                append(ss.name.jsonEncode())
                append(",\"kind\":")
                append(kind.jsonEncode())
                append(",\"srcDirs\":")
                append(srcDirs.toJsonArray())
                append(",\"classpath\":")
                append(emptyList<String>().toJsonArray())
                append("}")
            }
        }
    }
}

gradle.projectsEvaluated {
    // Only fire for the primary build.
    if (gradle.parent != null) {
        return@projectsEvaluated
    }
    val manifestFile = File(rootProject.rootDir, ".rewrite-manifest.json")
    val entries = collectedEntries.toList()
    val projectRoots = collectedRoots.toSortedSet()

    // Also record the project-root paths of included builds (and buildSrc if present) so the
    // runner can recurse into them with its own discovery.
    val auxEntries = mutableListOf<String>()
    val buildSrc = File(rootProject.rootDir, "buildSrc")
    if (buildSrc.isDirectory) {
        auxEntries += "{\"kind\":\"buildSrc\",\"path\":" + buildSrc.absolutePath.jsonEncode() + "}"
    }
    gradle.includedBuilds.forEach { inc ->
        auxEntries += "{\"kind\":\"included\",\"name\":" + inc.name.jsonEncode() +
                ",\"path\":" + inc.projectDir.absolutePath.jsonEncode() + "}"
    }

    val projectRootEntries = projectRoots.toList().map { "\"$it\"" }

    // The Gradle distribution's lib/ + lib/plugins/ jars — contain the full Gradle API. We publish
    // these as the shared classpath all source sets use. Avoids per-source-set resolution while
    // still giving the parser enough types to attribute build-logic Java files.
    val gradleLibJars = mutableListOf<String>()
    try {
        val gradleHome = gradle.gradleHomeDir
        if (gradleHome != null) {
            val lib = File(gradleHome, "lib")
            if (lib.isDirectory) {
                lib.walkTopDown().filter { it.isFile && it.name.endsWith(".jar") }
                    .forEach { gradleLibJars.add(it.absolutePath) }
            }
        }
    } catch (e: Throwable) {
        println("[rewrite-discovery] WARN: could not list Gradle distribution jars: ${e.message}")
    }

    val json = buildString {
        append("{\n  \"sourceSets\": [\n    ")
        append(entries.joinToString(",\n    "))
        append("\n  ],\n  \"projectRoots\": [\n    ")
        append(projectRootEntries.joinToString(",\n    "))
        append("\n  ],\n  \"nestedBuilds\": [\n    ")
        append(auxEntries.joinToString(",\n    "))
        append("\n  ],\n  \"gradleApi\": ")
        append(gradleLibJars.toJsonArray())
        append("\n}\n")
    }
    manifestFile.writeText(json)
    println("[rewrite-discovery] ${entries.size} source sets + ${auxEntries.size} nested builds -> $manifestFile")

    // Short-circuit: we have everything, skip execution entirely.
    throw org.gradle.api.GradleException(
        "[rewrite-discovery] manifest written; aborting to avoid compile cascade (intentional)")
}

fun String.jsonEncode(): String {
    val s = StringBuilder("\"")
    forEach { c ->
        when (c) {
            '\\' -> s.append("\\\\")
            '"' -> s.append("\\\"")
            '\n' -> s.append("\\n")
            '\r' -> s.append("\\r")
            '\t' -> s.append("\\t")
            else -> if (c.code < 0x20) s.append("\\u%04x".format(c.code)) else s.append(c)
        }
    }
    s.append('"')
    return s.toString()
}

fun List<String>.toJsonArray(): String = joinToString(",", "[", "]") { it.jsonEncode() }
