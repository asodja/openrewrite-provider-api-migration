package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class DetectSetterOverrideTest implements RewriteTest {

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
                        "    /*\n" +
                        "     * TODO: Override of `setClasspath` on subclass of `org.gradle.api.tasks.testing.Test`.\n" +
                        "     * The Provider API migration removes this setter, so the override is orphaned.\n" +
                        "     * \n" +
                        "     * Options:\n" +
                        "     *   1. Move the logic to each call site.\n" +
                        "     *   2. Configure `getClasspath().convention(...)` in the subclass constructor\n" +
                        "     *      to preserve defaults.\n" +
                        "     *   3. Lift preprocessing into `getClasspath().finalizeValueOnRead()` plus a\n" +
                        "     *      transform provider.\n" +
                        "     * \n" +
                        "     * Remove the `setClasspath(...)` method once callers are updated.\n" +
                        "     */\n" +
                        "    @Override\n" +
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
