package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class DetectSetterOverrideTest implements RewriteTest {

    private static final String MARKER = "Override of `setClasspath` on subclass of " +
            "`org.gradle.api.tasks.testing.Test` — the Provider API migration removes this setter, " +
            "so the override is orphaned. Options: " +
            "(1) move the logic to each call site, or " +
            "(2) configure `getClasspath().convention(...)` in the subclass constructor to preserve defaults, or " +
            "(3) lift preprocessing into `getClasspath().finalizeValueOnRead()` plus a transform provider. " +
            "Remove the `setClasspath(...)` method once callers are updated.";

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new DetectSetterOverride())
                .parser(JavaParser.fromJavaVersion().dependsOn(GradleApiStubs.ALL));
    }

    @Test
    void flagsOverrideOfRemovedSetter() {
        rewriteRun(
                java(
                        "import org.gradle.api.tasks.testing.Test;\n" +
                        "import org.gradle.api.file.FileCollection;\n" +
                        "public class MyTest extends Test {\n" +
                        "    @Override\n" +
                        "    public void setClasspath(FileCollection cp) {\n" +
                        "    }\n" +
                        "}\n",
                        "import org.gradle.api.tasks.testing.Test;\n" +
                        "import org.gradle.api.file.FileCollection;\n" +
                        "public class MyTest extends Test {\n" +
                        "    /*~~(" + MARKER + ")~~>*/@Override\n" +
                        "    public void setClasspath(FileCollection cp) {\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }

    @Test
    void doesNotFlagUnrelatedSetters() {
        rewriteRun(
                java(
                        "import org.gradle.api.tasks.testing.Test;\n" +
                        "public class MyTest extends Test {\n" +
                        "    private String name;\n" +
                        "    public void setName(String n) { this.name = n; }\n" +
                        "}\n"
                )
        );
    }
}
