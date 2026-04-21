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
                        "    /*~~(Override of `setClasspath` — the Provider API migration removes this setter on org.gradle.api.tasks.testing.Test. Move the logic to the call site or to a `Property.convention(...)` on the corresponding getter.)~~>*/@Override\n" +
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
