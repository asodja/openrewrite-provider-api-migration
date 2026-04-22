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

gradle.projectsEvaluated {
    // Only fire for the primary build. Included / settings-included builds get their own projectsEvaluated
    // callback but we want one manifest at the outermost root.
    if (gradle.parent != null) {
        return@projectsEvaluated
    }
    val manifestFile = File(rootProject.rootDir, ".rewrite-manifest.json")
    val entries = mutableListOf<String>()
    // Collect project roots too, so we can pick up *.gradle[.kts] files that live there
    // (outside any source set).
    val projectRoots = mutableSetOf<String>()

    rootProject.allprojects.forEach { project ->
        projectRoots.add(project.projectDir.absolutePath)
        val ssc = project.extensions.findByType(org.gradle.api.tasks.SourceSetContainer::class.java)
            ?: return@forEach
        ssc.forEach { ss ->
            val srcDirs = ss.allSource.srcDirs.filter { it.exists() }.map { it.absolutePath }
            if (srcDirs.isEmpty()) return@forEach
            // `.files` on a Configuration triggers dependency resolution but not compile cascade
            // for jar-file entries. Project-dependency entries resolve to their output directories
            // which WOULD trigger compile on execution — but because we abort before execution,
            // the cascade doesn't run. We collect the declared files here for later use.
            val classpath = try {
                ss.compileClasspath.files.map { it.absolutePath }
            } catch (e: Throwable) {
                // Some classpaths can't be resolved at configuration time (especially across broken
                // included builds). Skip gracefully — the parse can still run, just with less type
                // attribution.
                emptyList()
            }
            entries += buildString {
                append("{\"project\":")
                append(project.path.jsonEncode())
                append(",\"sourceSet\":")
                append(ss.name.jsonEncode())
                append(",\"srcDirs\":")
                append(srcDirs.toJsonArray())
                append(",\"classpath\":")
                append(classpath.toJsonArray())
                append("}")
            }
        }
    }

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

    val projectRootEntries = projectRoots.sorted().map { "\"$it\"" }
    val json = buildString {
        append("{\n  \"sourceSets\": [\n    ")
        append(entries.joinToString(",\n    "))
        append("\n  ],\n  \"projectRoots\": [\n    ")
        append(projectRootEntries.joinToString(",\n    "))
        append("\n  ],\n  \"nestedBuilds\": [\n    ")
        append(auxEntries.joinToString(",\n    "))
        append("\n  ]\n}\n")
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
