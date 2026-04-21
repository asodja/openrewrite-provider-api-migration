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
        // The input snippets call methods that don't exist on Provider<T> (e.g. Provider<String>.endsWith).
        // That IS the migration scenario — user's pre-migration code no longer compiles against the new
        // Provider-returning getters. We relax method-invocation type validation so the recipe runs against
        // these intentionally-unresolved calls.
        spec.recipe(new InsertGetOnLazyAccess())
                .parser(JavaParser.fromJavaVersion().dependsOn(GradleApiStubs.ALL))
                .typeValidationOptions(TypeValidation.builder().methodInvocations(false).build());
    }

    @Test
    void insertsGetOnStringMemberCall() {
        rewriteRun(
                java(
                        "import org.gradle.api.provider.Provider;\n" +
                        "class Build {\n" +
                        "    boolean cfg(Provider<String> name) {\n" +
                        "        return name.endsWith(\"-SNAPSHOT\");\n" +
                        "    }\n" +
                        "}\n",
                        "import org.gradle.api.provider.Provider;\n" +
                        "class Build {\n" +
                        "    boolean cfg(Provider<String> name) {\n" +
                        "        return name.get().endsWith(\"-SNAPSHOT\");\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }

    @Test
    void doesNotTouchProviderMembers() {
        rewriteRun(
                java(
                        "import org.gradle.api.provider.Provider;\n" +
                        "class Build {\n" +
                        "    Provider<Integer> cfg(Provider<String> name) {\n" +
                        "        return name.map(String::length);\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }

    @Test
    void doesNotTouchNonProviderReceivers() {
        rewriteRun(
                java(
                        "class Build {\n" +
                        "    boolean cfg(String name) {\n" +
                        "        return name.endsWith(\"x\");\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }
}
