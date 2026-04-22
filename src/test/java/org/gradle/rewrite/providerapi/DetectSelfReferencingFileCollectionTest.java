package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class DetectSelfReferencingFileCollectionTest implements RewriteTest {

    private static final String MARKER = "Self-referencing ConfigurableFileCollection: " +
            "`classpath.setFrom(...)` reads `classpath` on the RHS. Evaluation is deferred, so " +
            "this loops back to an empty collection or deadlocks. Replace with: " +
            "`classpath.from(extra)` (additive intent, preferred), OR " +
            "`val prev = classpath.files; classpath.setFrom(); classpath.from(prev, extra)` " +
            "(capture-then-rebuild), OR " +
            "`(classpath as DefaultConfigurableFileCollection).replace { it + extra }` " +
            "(Gradle internal API, fragile).";

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
                        "        /*~~(" + MARKER + ")~~>*/classpath.setFrom(extra, classpath);\n" +
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
