package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Proves the setter recipes fire against a PRE-migration classpath — the Gradle API stubs only expose
 * the eager setter and eager getter, no {@code Property<T>}. This is the workflow real users will use:
 * run rewrite on their current (old) Gradle, then bump the wrapper.
 *
 * <p>Distinct from {@link MigratePropertySetterTest} which uses the hybrid-API stubs (both old setters
 * and new {@code Property<T>} getters present). That test proved the mechanical rewrite works; this
 * one proves the catalog-based trigger fires without any view of the new API.
 */
class MigratePropertySetterOldClasspathTest implements RewriteTest {

    /** Test-only stub modeling the OLD Gradle API: eager setter + eager getter only. */
    private static final String OLD_TEST_TASK =
            "package org.gradle.api.tasks.testing;\n" +
            "public abstract class Test {\n" +
            "    public abstract int getMaxParallelForks();\n" +
            "    public abstract void setMaxParallelForks(int value);\n" +
            "    public abstract boolean isFailOnNoMatchingTests();\n" +
            "    public abstract void setFailOnNoMatchingTests(boolean value);\n" +
            "}\n";

    private static final String OLD_COMPILE_OPTIONS =
            "package org.gradle.api.tasks.compile;\n" +
            "public abstract class CompileOptions {\n" +
            "    public abstract String getEncoding();\n" +
            "    public abstract void setEncoding(String value);\n" +
            "}\n";

    @Override
    public void defaults(RecipeSpec spec) {
        // Relax method-invocation validation: the generated getX()/set() calls can't be fully attributed
        // when the classpath has only eager types (getX() returns int, no .set() method on it).
        spec.recipe(new MigratePropertySetter())
                .parser(JavaParser.fromJavaVersion().dependsOn(OLD_TEST_TASK, OLD_COMPILE_OPTIONS))
                .typeValidationOptions(TypeValidation.builder().methodInvocations(false).build());
    }

    @Test
    void rewritesSetterAgainstOldClasspath() {
        rewriteRun(
                java(
                        "import org.gradle.api.tasks.testing.Test;\n" +
                        "class Build {\n" +
                        "    void cfg(Test t) {\n" +
                        "        t.setMaxParallelForks(4);\n" +
                        "    }\n" +
                        "}\n",
                        "import org.gradle.api.tasks.testing.Test;\n" +
                        "class Build {\n" +
                        "    void cfg(Test t) {\n" +
                        "        t.getMaxParallelForks().set(4);\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }

    @Test
    void rewritesStringSetterAgainstOldClasspath() {
        rewriteRun(
                java(
                        "import org.gradle.api.tasks.compile.CompileOptions;\n" +
                        "class Build {\n" +
                        "    void cfg(CompileOptions o) {\n" +
                        "        o.setEncoding(\"UTF-8\");\n" +
                        "    }\n" +
                        "}\n",
                        "import org.gradle.api.tasks.compile.CompileOptions;\n" +
                        "class Build {\n" +
                        "    void cfg(CompileOptions o) {\n" +
                        "        o.getEncoding().set(\"UTF-8\");\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }
}
