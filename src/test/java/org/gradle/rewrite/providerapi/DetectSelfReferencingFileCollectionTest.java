package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class DetectSelfReferencingFileCollectionTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new DetectSelfReferencingFileCollection())
                .parser(JavaParser.fromJavaVersion().dependsOn(GradleApiStubs.ALL));
    }

    @Test
    void flagsSelfReferencingSetFrom() {
        rewriteRun(
                java(
                        "import org.gradle.api.file.ConfigurableFileCollection;\n" +
                        "class Build {\n" +
                        "    void cfg(ConfigurableFileCollection classpath, Object extra) {\n" +
                        "        classpath.setFrom(extra, classpath);\n" +
                        "    }\n" +
                        "}\n",
                        "import org.gradle.api.file.ConfigurableFileCollection;\n" +
                        "class Build {\n" +
                        "    void cfg(ConfigurableFileCollection classpath, Object extra) {\n" +
                        "        /*\n" +
                        "         * TODO: Self-referencing ConfigurableFileCollection.\n" +
                        "         * `classpath.setFrom(...)` reads `classpath` on the RHS. Evaluation is deferred, so this loops back to an empty collection or deadlocks.\n" +
                        "         * \n" +
                        "         * Replacement options:\n" +
                        "         *   1. Additive (preferred if that's the intent):\n" +
                        "         *          classpath.from(extra)\n" +
                        "         *   2. Capture-then-rebuild:\n" +
                        "         *          val prev = classpath.files\n" +
                        "         *          classpath.setFrom()\n" +
                        "         *          classpath.from(prev, extra)\n" +
                        "         *   3. Internal-API (fragile, not recommended):\n" +
                        "         *          (classpath as DefaultConfigurableFileCollection).replace { it + extra }\n" +
                        "         */\n" +
                        "        classpath.setFrom(extra, classpath);\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }

    @Test
    void doesNotFlagNonSelfReferencingSetFrom() {
        rewriteRun(
                java(
                        "import org.gradle.api.file.ConfigurableFileCollection;\n" +
                        "class Build {\n" +
                        "    void cfg(ConfigurableFileCollection classpath, Object extra) {\n" +
                        "        classpath.setFrom(extra);\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }
}
