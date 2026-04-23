package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.kotlin.Assertions.kotlin;

class MigrateScalarPropertySelfReferenceTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateScalarPropertySelfReference())
                .parser(KotlinParser.builder()
                        .dependsOn(GradleApiKotlinStubs.ALL)
                        .isKotlinScript(true)
                        .scriptImplicitReceivers("org.gradle.api.Project")
                        .scriptDefaultImports(
                                "org.gradle.api.*",
                                "org.gradle.api.tasks.*",
                                "org.gradle.api.tasks.testing.*",
                                "org.gradle.kotlin.dsl.*"))
                .typeValidationOptions(TypeValidation.builder()
                        .methodInvocations(false)
                        .identifiers(false)
                        .variableDeclarations(false)
                        .build());
    }

    @Test
    void rewritesScalarSelfReferenceInsideTypedScope() {
        // `maxMemory` is SCALAR_PROPERTY on Test. Pattern: `x = x.m(args)`.
        rewriteRun(
                kotlin(
                        "tasks.withType<Test> {\n" +
                        "    maxMemory = maxMemory.uppercase()\n" +
                        "}\n",
                        "tasks.withType<Test> {\n" +
                        "    (maxMemory as org.gradle.api.internal.provider.DefaultProperty<*>).replace { it.map { v -> v.uppercase() } }\n" +
                        "}\n",
                        spec -> spec.path("build.gradle.kts")
                )
        );
    }

    @Test
    void rewritesScalarSelfReferenceWithArguments() {
        rewriteRun(
                kotlin(
                        "tasks.withType<Test> {\n" +
                        "    maxMemory = maxMemory.replace(\"-\", \"_\")\n" +
                        "}\n",
                        "tasks.withType<Test> {\n" +
                        "    (maxMemory as org.gradle.api.internal.provider.DefaultProperty<*>).replace { it.map { v -> v.replace(\"-\", \"_\") } }\n" +
                        "}\n",
                        spec -> spec.path("build.gradle.kts")
                )
        );
    }

    @Test
    void doesNotRewriteNonSelfReference() {
        // LHS and RHS receiver don't match — must not fire.
        rewriteRun(
                kotlin(
                        "tasks.withType<Test> {\n" +
                        "    maxMemory = someOther.uppercase()\n" +
                        "}\n",
                        spec -> spec.path("build.gradle.kts")
                )
        );
    }

    @Test
    void doesNotRewriteNonCatalogedProperty() {
        // `customThing` isn't in the catalog — skip.
        rewriteRun(
                kotlin(
                        "tasks.withType<Test> {\n" +
                        "    customThing = customThing.uppercase()\n" +
                        "}\n",
                        spec -> spec.path("build.gradle.kts")
                )
        );
    }
}
