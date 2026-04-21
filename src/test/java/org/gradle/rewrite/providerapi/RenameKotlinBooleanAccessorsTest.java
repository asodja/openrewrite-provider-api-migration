package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.kotlin.Assertions.kotlin;

class RenameKotlinBooleanAccessorsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RenameKotlinBooleanAccessors())
                .parser(KotlinParser.builder().dependsOn(GradleApiKotlinStubs.ALL));
    }

    @Test
    void renamesIsEnabledOnTestTask() {
        rewriteRun(
                kotlin(
                        "import org.gradle.api.tasks.testing.Test\n" +
                        "fun cfg(t: Test) {\n" +
                        "    t.isEnabled\n" +
                        "    t.isFailOnNoMatchingTests\n" +
                        "}\n",
                        "import org.gradle.api.tasks.testing.Test\n" +
                        "fun cfg(t: Test) {\n" +
                        "    t.enabled\n" +
                        "    t.failOnNoMatchingTests\n" +
                        "}\n"
                )
        );
    }

    @Test
    void doesNotRenameOnUnrelatedTypes() {
        rewriteRun(
                kotlin(
                        "class Foo {\n" +
                        "    val isEnabled: Boolean get() = true\n" +
                        "}\n" +
                        "fun cfg(f: Foo) {\n" +
                        "    f.isEnabled\n" +
                        "}\n"
                )
        );
    }
}
