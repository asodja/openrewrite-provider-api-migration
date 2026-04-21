package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateListPropertyPlusAssignTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateListPropertyPlusAssign())
                .parser(JavaParser.fromJavaVersion().dependsOn(GradleApiStubs.ALL));
    }

    @Test
    void rewritesPlusAssignOnListProperty() {
        rewriteRun(
                java(
                        "import org.gradle.api.provider.ListProperty;\n" +
                        "class Build {\n" +
                        "    void cfg(ListProperty<String> args, String v) {\n" +
                        "        args += v;\n" +
                        "    }\n" +
                        "}\n",
                        "import org.gradle.api.provider.ListProperty;\n" +
                        "class Build {\n" +
                        "    void cfg(ListProperty<String> args, String v) {\n" +
                        "        args.add(v);\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }

    @Test
    void doesNotRewritePlusAssignOnRegularList() {
        rewriteRun(
                java(
                        "import java.util.ArrayList;\n" +
                        "import java.util.List;\n" +
                        "class Build {\n" +
                        "    void cfg() {\n" +
                        "        int n = 0;\n" +
                        "        n += 1;\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }
}
