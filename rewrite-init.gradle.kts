// Generic init script that wires the Provider API migration recipes into any Gradle project.
//
// Usage (from the target project's directory):
//     ./gradlew --init-script /Users/asodja/workspace/openrewrite-provider-api-migration/rewrite-init.gradle.kts providerApiMigrate
//
// The `providerApiMigrate` aggregator task runs `rewriteRun` in the primary build AND in every
// included build, so one command migrates the whole project tree.
//
// Requires ./gradlew publishToMavenLocal in the recipe module first.

initscript {
    repositories {
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.openrewrite:plugin:7.31.0")
    }
}

// Apply rewrite to the rootProject of every build in scope (primary build + included builds).
// The rewrite-gradle-plugin then discovers subprojects itself from each root.
allprojects {
    if (project != rootProject) {
        return@allprojects
    }

    apply<org.openrewrite.gradle.RewritePlugin>()

    repositories {
        mavenLocal()
        mavenCentral()
    }

    configure<org.openrewrite.gradle.RewriteExtension> {
        activeRecipe("org.gradle.rewrite.providerapi.MigrateToProviderApi")
        // Skip generated / tooling dirs — they're never user source. These save parse time up-front
        // (the rewrite plugin doesn't even parse excluded paths), complementing the runtime
        // GradleBuildLogic filter that skips production code.
        exclusion("**/build/**")
        exclusion("**/.gradle/**")
        exclusion("**/.idea/**")
        exclusion("**/.vscode/**")
        exclusion("**/out/**")
        exclusion("**/bin/**")
        exclusion("**/target/**")
        exclusion("**/node_modules/**")
    }

    dependencies {
        "rewrite"("org.gradle.rewrite:gradle-provider-api-migration:0.1.0-SNAPSHOT")
    }
}

// Register a top-level aggregator so users don't have to enumerate included builds manually. Runs
// after configuration of all builds, so includedBuilds resolves correctly.
gradle.projectsEvaluated {
    gradle.rootProject.tasks.register("providerApiMigrate") {
        group = "rewrite"
        description = "Run the Gradle Provider API migration across the primary build and every included build."
        dependsOn(gradle.rootProject.tasks.named("rewriteRun"))
        gradle.includedBuilds.forEach { inc ->
            dependsOn(inc.task(":rewriteRun"))
        }
    }
    gradle.rootProject.tasks.register("providerApiMigrateDryRun") {
        group = "rewrite"
        description = "Dry-run of providerApiMigrate: show the diff without applying changes."
        dependsOn(gradle.rootProject.tasks.named("rewriteDryRun"))
        gradle.includedBuilds.forEach { inc ->
            dependsOn(inc.task(":rewriteDryRun"))
        }
    }
}
