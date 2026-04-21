package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateListPropertySetterTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateListPropertySetter())
                .parser(JavaParser.fromJavaVersion().dependsOn(GradleApiStubs.ALL));
    }

    @Test
    void migratesListPropertySetter() {
        rewriteRun(
                java(
                        "import org.gradle.api.tasks.compile.CompileOptions;\n" +
                        "import java.util.List;\n" +
                        "class Build {\n" +
                        "    void cfg(CompileOptions opts, List<String> args) {\n" +
                        "        opts.setCompilerArgs(args);\n" +
                        "    }\n" +
                        "}\n",
                        "import org.gradle.api.tasks.compile.CompileOptions;\n" +
                        "import java.util.List;\n" +
                        "class Build {\n" +
                        "    void cfg(CompileOptions opts, List<String> args) {\n" +
                        "        opts.getCompilerArgs().set(args);\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }

    @Test
    void doesNotMigrateScalarPropertySetter() {
        rewriteRun(
                java(
                        "import org.gradle.api.tasks.testing.Test;\n" +
                        "class Build {\n" +
                        "    void cfg(Test t) {\n" +
                        "        t.setMaxParallelForks(4);\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }
}
