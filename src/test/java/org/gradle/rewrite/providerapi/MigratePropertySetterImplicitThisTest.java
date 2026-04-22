package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.kotlin.Assertions.kotlin;

/**
 * Verifies the catalog's name-only fallback fires when the setter is called without an explicit
 * receiver — the common case in Kotlin DSL configuration blocks and {@code doLast {}} / {@code doFirst {}}.
 *
 * <p>In these contexts, type attribution often can't resolve the enclosing closure's receiver on
 * {@code .gradle.kts} scripts, so the setter call's declaring type is null. The fallback looks up
 * the property name in the catalog — if all cataloged entries agree on the kind, the rewrite proceeds.
 */
class MigratePropertySetterImplicitThisTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigratePropertySetter())
                .parser(KotlinParser.builder().dependsOn(GradleApiKotlinStubs.ALL))
                // Implicit-this calls in .gradle.kts can't fully type-attribute on test stubs; relax
                // method-invocation validation (mirrors what the real recipe hits on real Gradle scripts).
                .typeValidationOptions(TypeValidation.builder().methodInvocations(false).build());
    }

    @Test
    void migratesImplicitSetterInsideDoLast() {
        // maxMemory is SCALAR_PROPERTY on both Test and Javadoc — catalog name-only lookup is unambiguous.
        rewriteRun(
                kotlin(
                        "fun cfg() {\n" +
                        "    val anyBlock: () -> Unit = {\n" +
                        "        setMaxMemory(\"1024m\")\n" +
                        "    }\n" +
                        "    anyBlock()\n" +
                        "}\n",
                        "fun cfg() {\n" +
                        "    val anyBlock: () -> Unit = {\n" +
                        "        getMaxMemory().set(\"1024m\")\n" +
                        "    }\n" +
                        "    anyBlock()\n" +
                        "}\n",
                        spec -> spec.path("build.gradle.kts")
                )
        );
    }
}
