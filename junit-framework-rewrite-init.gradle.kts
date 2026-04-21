// Init script to wire the Provider API migration recipes into /Users/asodja/workspace/junit-framework
// without modifying the project's own build files.
//
// Usage (from the junit-framework directory):
//     ./gradlew --init-script /Users/asodja/workspace/openrewrite-provider-api-migration/junit-framework-rewrite-init.gradle.kts rewriteDryRun
//
// Requires: ./gradlew publishToMavenLocal in the recipe module first.

initscript {
    repositories {
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.openrewrite:plugin:7.31.0")
    }
}

// Apply rewrite to BOTH the primary build AND the `gradle/plugins` included build — junit-framework's
// regular .kt build-logic lives in the included build, and that's where the Kotlin assign-import
// additions need to land. Match by rootProject name so we don't apply to unrelated settings.
allprojects {
    val rootName = rootProject.name
    if (project != rootProject) {
        return@allprojects
    }
    if (rootName != "junit-framework" && rootName != "plugins") {
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
