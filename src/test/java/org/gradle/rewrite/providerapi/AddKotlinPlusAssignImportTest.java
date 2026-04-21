package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.kotlin.Assertions.kotlin;

class AddKotlinPlusAssignImportTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddKotlinPlusAssignImport())
                .parser(KotlinParser.builder().dependsOn(GradleApiKotlinStubs.ALL));
    }

    @Test
    void addsImportWhenPlusAssignOnListProperty() {
        rewriteRun(
                kotlin(
                        "import org.gradle.api.provider.ListProperty\n" +
                        "class Holder { val p: ListProperty<String> = TODO() }\n" +
                        "fun cfg(h: Holder) {\n" +
                        "    h.p += \"foo\"\n" +
                        "}\n",
                        "import org.gradle.api.provider.ListProperty\n" +
                        "import org.gradle.kotlin.dsl.plusAssign\n" +
                        "class Holder { val p: ListProperty<String> = TODO() }\n" +
                        "fun cfg(h: Holder) {\n" +
                        "    h.p += \"foo\"\n" +
                        "}\n"
                )
        );
    }

    @Test
    void doesNotAddImportOnUnrelatedPlusAssign() {
        rewriteRun(
                kotlin(
                        "fun cfg() {\n" +
                        "    var n: Int = 0\n" +
                        "    n += 1\n" +
                        "}\n"
                )
        );
    }
}
