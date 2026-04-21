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
                        "        /*~~(Self-referencing ConfigurableFileCollection: `classpath.setFrom(...)` reads `classpath` on the RHS. Capture to a local or use `.from(extra)` if intent was additive.)~~>*/classpath.setFrom(extra, classpath);\n" +
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
