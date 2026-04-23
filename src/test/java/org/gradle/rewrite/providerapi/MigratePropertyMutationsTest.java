package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.kotlin.Assertions.kotlin;

/**
 * Mechanical rewrite for collection-property mutations (remove / clear / compute / etc.) that
 * don't exist on the lazy Map/List/SetProperty API. Targets Gradle's internal
 * {@code DefaultXxxProperty.replace(Transformer)} path.
 *
 * <p>Type validation is relaxed because {@link org.openrewrite.kotlin.KotlinTemplate} parses the
 * synthesized snippet against a minimal classpath — the emitted {@code DefaultMapProperty} / lambda
 * nodes come out with null types, which is fine (next parse cycle on the migrated Gradle attributes
 * them correctly) but would otherwise trip the test harness.
 */
class MigratePropertyMutationsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigratePropertyMutations())
                .parser(KotlinParser.builder()
                        .dependsOn(GradleApiKotlinStubs.ALL)
                        .isKotlinScript(true)
                        .scriptImplicitReceivers("org.gradle.api.Project")
                        .scriptDefaultImports(
                                "org.gradle.api.*",
                                "org.gradle.api.tasks.*",
                                "org.gradle.api.tasks.testing.*",
                                "org.gradle.api.tasks.javadoc.*",
                                "org.gradle.kotlin.dsl.*"))
                .typeValidationOptions(TypeValidation.builder()
                        .methodInvocations(false)
                        .identifiers(false)
                        .variableDeclarations(false)
                        .build());
    }

    @Test
    void rewritesMapPropertyRemoveInsideTypedScope() {
        rewriteRun(
                kotlin(
                        "tasks.withType<Test> {\n" +
                        "    environment.remove(\"RUNNER_TEMP\")\n" +
                        "}\n",
                        "tasks.withType<Test> {\n" +
                        "    (environment as org.gradle.api.internal.provider.DefaultMapProperty<*, *>).replace { it.map { m -> m.toMutableMap().apply { remove(\"RUNNER_TEMP\") } } }\n" +
                        "}\n",
                        spec -> spec.path("build.gradle.kts")
                )
        );
    }

    @Test
    void rewritesMapPropertyComputeInsideTypedScope() {
        rewriteRun(
                kotlin(
                        "tasks.withType<Test> {\n" +
                        "    environment.compute(\"K\") { _, v -> v }\n" +
                        "}\n",
                        "tasks.withType<Test> {\n" +
                        "    (environment as org.gradle.api.internal.provider.DefaultMapProperty<*, *>).replace { it.map { m -> m.toMutableMap().apply { compute(\"K\", { _, v -> v }) } } }\n" +
                        "}\n",
                        spec -> spec.path("build.gradle.kts")
                )
        );
    }

    @Test
    void rewritesListPropertyRemoveInsideTypedScope() {
        rewriteRun(
                kotlin(
                        "tasks.withType<Test> {\n" +
                        "    jvmArgs.remove(\"-Xmx1g\")\n" +
                        "}\n",
                        "tasks.withType<Test> {\n" +
                        "    (jvmArgs as org.gradle.api.internal.provider.DefaultListProperty<*>).replace { it.map { l -> l.toMutableList().apply { remove(\"-Xmx1g\") } } }\n" +
                        "}\n",
                        spec -> spec.path("build.gradle.kts")
                )
        );
    }

    @Test
    void doesNotRewriteWhenReceiverIsUnknown() {
        rewriteRun(
                kotlin(
                        "fun cfg() {\n" +
                        "    someRandomThing.remove(\"X\")\n" +
                        "}\n",
                        spec -> spec.path("build.gradle.kts")
                )
        );
    }

    @Test
    void doesNotRewriteSupportedMethods() {
        // `put` is a native MapProperty method — don't touch it.
        rewriteRun(
                kotlin(
                        "tasks.withType<Test> {\n" +
                        "    environment.put(\"K\", \"V\")\n" +
                        "}\n",
                        spec -> spec.path("build.gradle.kts")
                )
        );
    }
}
