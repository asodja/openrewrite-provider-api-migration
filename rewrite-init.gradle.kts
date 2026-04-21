// Generic init script that wires the Provider API migration recipes into any Gradle project.
//
// Usage (from the target project's directory):
//     ./gradlew --init-script /Users/asodja/workspace/openrewrite-provider-api-migration/rewrite-init.gradle.kts rewriteRun
//
// For projects with included builds containing Kotlin source that also needs migrating (e.g.
// junit-framework's gradle/plugins), run a second invocation with the included build's task prefix:
//     ./gradlew --init-script .../rewrite-init.gradle.kts :plugins:rewriteRun
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

// Apply to the rootProject of any primary build we see. Works for both "junit-framework" and "spring"
// (and any other Gradle project). We skip non-root projects because the rewrite plugin discovers
// subprojects itself from the root.
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
    }

    dependencies {
        "rewrite"("org.gradle.rewrite:gradle-provider-api-migration:0.1.0-SNAPSHOT")
    }
}
