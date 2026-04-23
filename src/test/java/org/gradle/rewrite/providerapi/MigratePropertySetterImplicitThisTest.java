package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.kotlin.Assertions.kotlin;

/**
 * Pins the negative: implicit-this setter calls whose declaring type cannot be resolved from the LST
 * are NOT rewritten. A previous draft used a name-only catalog fallback, but it caused false positives
 * when a property name appeared on ONE cataloged type while the actual enclosing receiver was a DIFFERENT
 * (uncataloged) type — e.g. {@code setIncludes(listOf(...))} inside {@code tasks.register<Test> { }}
 * where {@code includes} is a LIST_PROPERTY on {@code JacocoTaskExtension} but still {@code Set<String>}
 * on {@code Test}.
 */
class MigratePropertySetterImplicitThisTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigratePropertySetter())
                .parser(KotlinParser.builder().dependsOn(GradleApiKotlinStubs.ALL))
                .typeValidationOptions(TypeValidation.builder().methodInvocations(false).build());
    }

    @Test
    void doesNotRewriteImplicitSetterWhenReceiverTypeUnknown() {
        rewriteRun(
                kotlin(
                        "fun cfg() {\n" +
                        "    val anyBlock: () -> Unit = {\n" +
                        "        setMaxMemory(\"1024m\")\n" +
                        "    }\n" +
                        "    anyBlock()\n" +
                        "}\n",
                        spec -> spec.path("build.gradle.kts")
                )
        );
    }
}
