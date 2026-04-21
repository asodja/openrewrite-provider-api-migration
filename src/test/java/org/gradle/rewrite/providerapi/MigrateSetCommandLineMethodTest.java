package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateSetCommandLineMethodTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateSetCommandLineMethod())
                .parser(JavaParser.fromJavaVersion().dependsOn(GradleApiStubs.ALL));
    }

    @Test
    void renamesSetCommandLineOnExecSpec() {
        rewriteRun(
                java(
                        "import org.gradle.process.ExecSpec;\n" +
                        "import java.util.List;\n" +
                        "class Build {\n" +
                        "    void cfg(ExecSpec exec, List<String> cmd) {\n" +
                        "        exec.setCommandLine(cmd);\n" +
                        "    }\n" +
                        "}\n",
                        "import org.gradle.process.ExecSpec;\n" +
                        "import java.util.List;\n" +
                        "class Build {\n" +
                        "    void cfg(ExecSpec exec, List<String> cmd) {\n" +
                        "        exec.commandLine(cmd);\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }
}
