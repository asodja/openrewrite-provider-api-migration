package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.kotlin.Assertions.kotlin;

class AddKotlinAssignImportTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddKotlinAssignImport())
                .parser(KotlinParser.builder().dependsOn(GradleApiKotlinStubs.ALL));
    }

    @Test
    void addsImportWhenAssigningToProperty() {
        rewriteRun(
                kotlin(
                        "import org.gradle.api.provider.Property\n" +
                        "class Holder { val p: Property<String> = TODO() }\n" +
                        "fun cfg(h: Holder) {\n" +
                        "    h.p = \"foo\"\n" +
                        "}\n",
                        "import org.gradle.api.provider.Property\n" +
                        "import org.gradle.kotlin.dsl.assign\n" +
                        "class Holder { val p: Property<String> = TODO() }\n" +
                        "fun cfg(h: Holder) {\n" +
                        "    h.p = \"foo\"\n" +
                        "}\n"
                )
        );
    }

    @Test
    void doesNotAddImportWhenAlreadyPresent() {
        rewriteRun(
                kotlin(
                        "import org.gradle.api.provider.Property\n" +
                        "import org.gradle.kotlin.dsl.assign\n" +
                        "class Holder { val p: Property<String> = TODO() }\n" +
                        "fun cfg(h: Holder) {\n" +
                        "    h.p = \"foo\"\n" +
                        "}\n"
                )
        );
    }

    @Test
    void doesNotAddImportInGradleScriptFiles() {
        // .gradle.kts scripts auto-import the Kotlin DSL bundle (including `assign`). No need to add it.
        rewriteRun(
                s -> s.recipe(new AddKotlinAssignImport())
                        .parser(org.openrewrite.kotlin.KotlinParser.builder().dependsOn(GradleApiKotlinStubs.ALL)),
                org.openrewrite.kotlin.Assertions.kotlin(
                        "import org.gradle.api.provider.Property\n" +
                        "class Holder { val p: Property<String> = TODO() }\n" +
                        "fun cfg(h: Holder) {\n" +
                        "    h.p = \"foo\"\n" +
                        "}\n",
                        spec -> spec.path("build.gradle.kts")
                )
        );
    }

    @Test
    void doesNotAddImportWhenNoPropertyAssignment() {
        rewriteRun(
                kotlin(
                        "fun cfg() {\n" +
                        "    var s: String = \"foo\"\n" +
                        "    s = \"bar\"\n" +
                        "}\n"
                )
        );
    }
}
