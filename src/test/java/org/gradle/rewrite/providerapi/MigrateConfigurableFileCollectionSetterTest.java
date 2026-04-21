package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateConfigurableFileCollectionSetterTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateConfigurableFileCollectionSetter())
                .parser(JavaParser.fromJavaVersion().dependsOn(GradleApiStubs.ALL));
    }

    @Test
    void migratesClasspathSetter() {
        rewriteRun(
                java(
                        "import org.gradle.api.tasks.testing.Test;\n" +
                        "import org.gradle.api.file.FileCollection;\n" +
                        "class Build {\n" +
                        "    void cfg(Test t, FileCollection cp) {\n" +
                        "        t.setClasspath(cp);\n" +
                        "    }\n" +
                        "}\n",
                        "import org.gradle.api.tasks.testing.Test;\n" +
                        "import org.gradle.api.file.FileCollection;\n" +
                        "class Build {\n" +
                        "    void cfg(Test t, FileCollection cp) {\n" +
                        "        t.getClasspath().setFrom(cp);\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }

    @Test
    void migratesTestClassesDirsSetter() {
        rewriteRun(
                java(
                        "import org.gradle.api.tasks.testing.Test;\n" +
                        "import org.gradle.api.file.FileCollection;\n" +
                        "class Build {\n" +
                        "    void cfg(Test t, FileCollection dirs) {\n" +
                        "        t.setTestClassesDirs(dirs);\n" +
                        "    }\n" +
                        "}\n",
                        "import org.gradle.api.tasks.testing.Test;\n" +
                        "import org.gradle.api.file.FileCollection;\n" +
                        "class Build {\n" +
                        "    void cfg(Test t, FileCollection dirs) {\n" +
                        "        t.getTestClassesDirs().setFrom(dirs);\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }
}
