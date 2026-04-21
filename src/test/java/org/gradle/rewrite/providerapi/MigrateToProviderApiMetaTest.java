package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.config.Environment;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/**
 * Smoke test for the declarative meta-recipe at {@code META-INF/rewrite/migrate-to-provider-api.yml}.
 * Loads the recipe list from the classpath and verifies that a representative multi-pattern input
 * is rewritten end-to-end.
 */
class MigrateToProviderApiMetaTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("org.gradle.rewrite.providerapi.MigrateToProviderApi")
                .parser(JavaParser.fromJavaVersion().dependsOn(GradleApiStubs.ALL));
    }

    @Test
    void metaRecipeExists() {
        Environment env = Environment.builder()
                .scanRuntimeClasspath("org.gradle.rewrite.providerapi")
                .build();
        org.openrewrite.Recipe recipe = env.activateRecipes("org.gradle.rewrite.providerapi.MigrateToProviderApi");
        org.junit.jupiter.api.Assertions.assertFalse(recipe.getRecipeList().isEmpty(),
                "meta-recipe should declare a non-empty recipeList");
    }

    @Test
    void rewritesMultipleSetterKinds() {
        rewriteRun(
                java(
                        "import org.gradle.api.tasks.testing.Test;\n" +
                        "import org.gradle.api.file.FileCollection;\n" +
                        "import java.util.List;\n" +
                        "class Build {\n" +
                        "    void cfg(Test t, FileCollection cp, List<String> jvmArgs) {\n" +
                        "        t.setMaxParallelForks(4);\n" +
                        "        t.setClasspath(cp);\n" +
                        "        t.setJvmArgs(jvmArgs);\n" +
                        "    }\n" +
                        "}\n",
                        "import org.gradle.api.tasks.testing.Test;\n" +
                        "import org.gradle.api.file.FileCollection;\n" +
                        "import java.util.List;\n" +
                        "class Build {\n" +
                        "    void cfg(Test t, FileCollection cp, List<String> jvmArgs) {\n" +
                        "        t.getMaxParallelForks().set(4);\n" +
                        "        t.getClasspath().setFrom(cp);\n" +
                        "        t.getJvmArgs().set(jvmArgs);\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }
}
