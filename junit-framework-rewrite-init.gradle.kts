// Init script to wire the Provider API migration recipes into /Users/asodja/workspace/junit-framework
// without modifying the project's own build files.
//
// Usage (from the junit-framework directory):
//     ./gradlew --init-script /Users/asodja/workspace/openrewrite-provider-api-migration/junit-framework-rewrite-init.gradle.kts rewriteDryRun
//
// Publishes the recipe module as org.gradle.rewrite:gradle-provider-api-migration:0.1.0-SNAPSHOT
// in ~/.m2/repository (run ./gradlew publishToMavenLocal in the recipe module first).

initscript {
    repositories {
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.openrewrite:plugin:7.31.0")
    }
}

// Only apply to the primary build, NOT to junit-framework's gradle/plugins included build
// (that build has its own settings.gradle.kts and can't accept RewritePlugin wholesale).
allprojects {
    if (rootProject.name != "junit-framework") {
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
