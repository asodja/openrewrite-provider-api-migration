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

// Raw per-project data collected during afterEvaluate. We defer classification to
// projectsEvaluated so we can cross-reference buildscript classpaths (authoritative source
// of "which projects provide plugins to which").
data class ProjectRow(
    val path: String,
    val dir: String,
    val appliesJavaGradlePlugin: Boolean,
    val sourceSets: List<Pair<String, List<String>>>,
)
val projectRows = java.util.concurrent.CopyOnWriteArrayList<ProjectRow>()

gradle.allprojects {
    // Only act on the primary build. Included builds get their own init-script invocations.
    if (gradle.parent != null) return@allprojects

    afterEvaluate {
        collectedRoots.add(projectDir.absolutePath)
        val ssc = extensions.findByType(org.gradle.api.tasks.SourceSetContainer::class.java)
            ?: return@afterEvaluate
        // Collect per-project facts. Classification is deferred to projectsEvaluated where we
        // can cross-reference buildscript classpaths (the authoritative signal).
        val appliesJgp = pluginManager.hasPlugin("java-gradle-plugin")
        val sourceSetInfo = ssc.mapNotNull { ss ->
            val dirs = ss.allSource.srcDirs.filter { it.exists() }.map { it.absolutePath }
            if (dirs.isEmpty()) null else ss.name to dirs
        }
        projectRows.add(ProjectRow(
            path = path,
            dir = projectDir.absolutePath,
            appliesJavaGradlePlugin = appliesJgp,
            sourceSets = sourceSetInfo,
        ))
    }
}

gradle.projectsEvaluated {
    // Only fire for the primary build.
    if (gradle.parent != null) {
        return@projectsEvaluated
    }

    // --- Structural classification via buildscript classpath ---------------------------------
    //
    // Principle: a project is build-logic iff its compiled output is explicitly declared on some
    // script's buildscript classpath inside this build. That's the authoritative Gradle-model
    // signal — build-logic is the code that Gradle loads at configuration time to resolve plugin
    // classes and buildscript blocks.
    //
    // Implementation: walk every project's `buildscript.configurations.classpath` (the dependency
    // set on the buildscript-block classpath). Any `ProjectDependency` there points at a project
    // that provides plugins via the classic `buildscript { dependencies { classpath project(":foo")
    // } }` pattern — that project IS build-logic.
    //
    // We deliberately do NOT take the transitive closure over `implementation`/`api` etc. of those
    // projects. A production library (e.g. `:kotlin-stdlib`) that a build-logic project happens to
    // use as a compile dependency is still a production library, not plugin code. Conflating the
    // two would run recipes against non-plugin sources and break unsuspecting library code. The
    // known cost is that multi-project build-logic split inside one build via `buildscript
    // { classpath project(":a") }` + `:a` depends on `:b` via `implementation` will classify only
    // `:a` — not `:b`. Builds using that pattern should migrate to `pluginManagement
    // { includeBuild }` or add each helper to the buildscript classpath explicitly.
    //
    // Nested builds (`buildSrc`, `includeBuild(...)`, `pluginManagement.includeBuild(...)`) are
    // enumerated below into `nestedBuilds` and the runner walks each whole-dir — a convention that
    // assumes those directories contain exclusively build-logic (as is standard). This classifier
    // only needs to partition THIS build's own subprojects.
    val buildLogicPaths = mutableSetOf<String>()
    rootProject.allprojects.forEach { p ->
        val cp = p.buildscript.configurations.findByName("classpath") ?: return@forEach
        cp.dependencies.forEach { dep ->
            if (dep is org.gradle.api.artifacts.ProjectDependency) {
                try { buildLogicPaths.add(dep.path) } catch (_: Throwable) {}
            }
        }
    }

    // --- Emit manifest entries with final classification -------------------------------------
    val manifestFile = File(rootProject.rootDir, ".rewrite-manifest.json")
    val projectRoots = collectedRoots.toSortedSet()
    projectRows.forEach { row ->
        val kind = when {
            // DEFINITIVE — project's output is declared directly on some script's
            // buildscript classpath (via `buildscript { dependencies { classpath project(...) } }`).
            // That means Gradle loads its classes at configuration time to resolve plugins /
            // buildscript blocks. This IS build-logic.
            row.path in buildLogicPaths   -> "buildLogic"
            // Applies `java-gradle-plugin` but is NOT consumed by any buildscript classpath in
            // this build — treated as a plugin the user ships to external consumers. Migrate
            // these only when explicitly opted in (runtime breakage risk for downstream users).
            row.appliesJavaGradlePlugin   -> "publishedGradlePlugin"
            // Neither build-logic nor a Gradle plugin → production code. Out of scope here.
            else                          -> "production"
        }
        row.sourceSets.forEach { (ssName, dirs) ->
            collectedEntries += buildString {
                append("{\"project\":"); append(row.path.jsonEncode())
                append(",\"sourceSet\":"); append(ssName.jsonEncode())
                append(",\"kind\":"); append(kind.jsonEncode())
                append(",\"srcDirs\":"); append(dirs.toJsonArray())
                append(",\"classpath\":"); append(emptyList<String>().toJsonArray())
                append("}")
            }
        }
    }
    val entries = collectedEntries.toList()

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
