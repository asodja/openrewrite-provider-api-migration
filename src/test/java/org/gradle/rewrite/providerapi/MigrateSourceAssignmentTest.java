package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.openrewrite.groovy.GroovyParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.groovy.Assertions.groovy;

class MigrateSourceAssignmentTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateSourceAssignment())
                .parser(GroovyParser.builder().classpath(GradleApiStubs.ALL));
    }

    @Test
    @Disabled("Groovy parser type attribution requires real gradle-api jar on classpath; covered by integration tests")
    void rewritesSourceAssignmentInGroovy() {
        rewriteRun(
                groovy(
                        "import org.gradle.api.tasks.SourceTask\n" +
                        "def cfg(SourceTask t, Object x) {\n" +
                        "    t.source = x\n" +
                        "}\n",
                        "import org.gradle.api.tasks.SourceTask\n" +
                        "def cfg(SourceTask t, Object x) {\n" +
                        "    t.setSource(x)\n" +
                        "}\n"
                )
        );
    }
}
