package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateMapPropertySetterTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateMapPropertySetter())
                .parser(JavaParser.fromJavaVersion().dependsOn(GradleApiStubs.ALL));
    }

    @Test
    void migratesMapPropertySetter() {
        rewriteRun(
                java(
                        "import org.gradle.api.tasks.testing.Test;\n" +
                        "import java.util.Map;\n" +
                        "class Build {\n" +
                        "    void cfg(Test t, Map<String, Object> sys) {\n" +
                        "        t.setSystemProperties(sys);\n" +
                        "    }\n" +
                        "}\n",
                        "import org.gradle.api.tasks.testing.Test;\n" +
                        "import java.util.Map;\n" +
                        "class Build {\n" +
                        "    void cfg(Test t, Map<String, Object> sys) {\n" +
                        "        t.getSystemProperties().set(sys);\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }
}
