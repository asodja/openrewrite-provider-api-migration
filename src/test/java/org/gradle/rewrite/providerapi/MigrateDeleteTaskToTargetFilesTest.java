package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.kotlin.Assertions.kotlin;

class MigrateDeleteTaskToTargetFilesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateDeleteTaskToTargetFiles())
                .parser(KotlinParser.builder().dependsOn(GradleApiKotlinStubs.ALL));
    }

    @Test
    void rewritesDeleteAssignmentToTargetFilesSetFrom() {
        rewriteRun(
                kotlin(
                        "import org.gradle.api.tasks.Delete\n" +
                        "fun cfg(t: Delete, x: Any) {\n" +
                        "    t.delete = x\n" +
                        "}\n",
                        "import org.gradle.api.tasks.Delete\n" +
                        "fun cfg(t: Delete, x: Any) {\n" +
                        "    t.targetFiles.setFrom(x)\n" +
                        "}\n"
                )
        );
    }

    @Test
    void doesNotRewriteDeleteAssignmentOnOtherTypes() {
        rewriteRun(
                kotlin(
                        "class Foo { var delete: Any = Unit }\n" +
                        "fun cfg(f: Foo, x: Any) {\n" +
                        "    f.delete = x\n" +
                        "}\n"
                )
        );
    }
}
