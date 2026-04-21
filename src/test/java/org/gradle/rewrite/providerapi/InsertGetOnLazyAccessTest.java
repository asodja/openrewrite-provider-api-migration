package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

class InsertGetOnLazyAccessTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new InsertGetOnLazyAccess())
                .parser(JavaParser.fromJavaVersion().dependsOn(GradleApiStubs.ALL))
                .typeValidationOptions(TypeValidation.builder().methodInvocations(false).build());
    }

    @Test
    void insertsGetOnCataloguedScalarProperty() {
        rewriteRun(
                java(
                        "import org.gradle.api.tasks.testing.Test;\n" +
                        "class Build {\n" +
                        "    boolean cfg(Test t) {\n" +
                        "        return t.getMaxMemory().endsWith(\"g\");\n" +
                        "    }\n" +
                        "}\n",
                        "import org.gradle.api.tasks.testing.Test;\n" +
                        "class Build {\n" +
                        "    boolean cfg(Test t) {\n" +
                        "        return t.getMaxMemory().get().endsWith(\"g\");\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }

    @Test
    void doesNotTouchProviderMembers() {
        rewriteRun(
                java(
                        "import org.gradle.api.tasks.testing.Test;\n" +
                        "import org.gradle.api.provider.Property;\n" +
                        "class Build {\n" +
                        "    Property<String> cfg(Test t) {\n" +
                        "        return t.getMaxMemory();\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }

    @Test
    void doesNotTouchUncatalogedProperty() {
        rewriteRun(
                java(
                        "import org.gradle.api.provider.Provider;\n" +
                        "class Build {\n" +
                        "    boolean cfg(Provider<String> unrelated) {\n" +
                        "        // Not cataloged; recipe should ignore.\n" +
                        "        return unrelated.toString().startsWith(\"x\");\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }
}
