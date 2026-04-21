package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.kotlin.Assertions.kotlin;

/**
 * Verifies that the setter-migration recipes (which extend JavaIsoVisitor) also apply to Kotlin source
 * files when the call site uses explicit setter-method form like {@code t.setMaxParallelForks(4)}.
 *
 * <p>The <em>idiomatic</em> Kotlin pattern — {@code t.maxParallelForks = 4} — is a {@code J.Assignment}
 * and is handled by {@link AddKotlinAssignImport} rather than these setter recipes.
 *
 * <p>Note: the recipe emits Java-style {@code getX().set(v)} rather than Kotlin property syntax
 * {@code x.set(v)}. Both are valid Kotlin. Converting to property syntax is a follow-up style pass
 * that can be layered on top of this recipe.
 */
class MigratePropertySetterKotlinTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigratePropertySetter())
                .parser(KotlinParser.builder().dependsOn(GradleApiKotlinStubs.ALL));
    }

    @Test
    void migratesExplicitSetterCallInKotlin() {
        rewriteRun(
                kotlin(
                        "import org.gradle.api.tasks.testing.Test\n" +
                        "fun cfg(t: Test) {\n" +
                        "    t.setMaxParallelForks(4)\n" +
                        "}\n",
                        "import org.gradle.api.tasks.testing.Test\n" +
                        "fun cfg(t: Test) {\n" +
                        "    t.getMaxParallelForks().set(4)\n" +
                        "}\n"
                )
        );
    }
}
