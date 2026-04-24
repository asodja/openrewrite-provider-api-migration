package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;
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

    private static final String MAP_TODO_REMOVE =
            "    /*\n" +
            "     * TODO: Uses Gradle internal API (DefaultMapProperty). Fragile —\n" +
            "     * consider the public copy-mutate-set form instead:\n" +
            "     *     val updated = environment.get().toMutableMap()\n" +
            "     *     updated.remove(\"RUNNER_TEMP\")\n" +
            "     *     environment.set(updated)\n" +
            "     * \n" +
            "     * The cast below triggers UNCHECKED_CAST and\n" +
            "     * UPPER_BOUND_VIOLATED_IN_TYPE_OPERATOR_OR_PARAMETER_BOUNDS_WARNING warnings;\n" +
            "     * to silence them, add at the top of this script:\n" +
            "     *     @file:Suppress(\"UNCHECKED_CAST\", \"UPPER_BOUND_VIOLATED_IN_TYPE_OPERATOR_OR_PARAMETER_BOUNDS_WARNING\")\n" +
            "     */\n";

    @Test
    void rewritesMapPropertyRemoveInsideTypedScope() {
        rewriteRun(
                kotlin(
                        "tasks.withType<Test> {\n" +
                        "    environment.remove(\"RUNNER_TEMP\")\n" +
                        "}\n",
                        "tasks.withType<Test> {\n" +
                        MAP_TODO_REMOVE +
                        "    (environment as org.gradle.api.internal.provider.DefaultMapProperty<Any?, Any?>).replace { it.map { m -> m.toMutableMap().apply { remove(\"RUNNER_TEMP\") } } }\n" +
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
                        "    /*\n" +
                        "     * TODO: Uses Gradle internal API (DefaultMapProperty). Fragile —\n" +
                        "     * consider the public copy-mutate-set form instead:\n" +
                        "     *     val updated = environment.get().toMutableMap()\n" +
                        "     *     updated.compute(\"K\", { _, v -> v })\n" +
                        "     *     environment.set(updated)\n" +
                        "     * \n" +
                        "     * The cast below triggers UNCHECKED_CAST and\n" +
                        "     * UPPER_BOUND_VIOLATED_IN_TYPE_OPERATOR_OR_PARAMETER_BOUNDS_WARNING warnings;\n" +
                        "     * to silence them, add at the top of this script:\n" +
                        "     *     @file:Suppress(\"UNCHECKED_CAST\", \"UPPER_BOUND_VIOLATED_IN_TYPE_OPERATOR_OR_PARAMETER_BOUNDS_WARNING\")\n" +
                        "     */\n" +
                        "    (environment as org.gradle.api.internal.provider.DefaultMapProperty<Any?, Any?>).replace { it.map { m -> m.toMutableMap().apply { compute(\"K\", { _, v -> v }) } } }\n" +
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
                        "    /*\n" +
                        "     * TODO: Uses Gradle internal API (DefaultListProperty). Fragile —\n" +
                        "     * consider the public copy-mutate-set form instead:\n" +
                        "     *     val updated = jvmArgs.get().toMutableList()\n" +
                        "     *     updated.remove(\"-Xmx1g\")\n" +
                        "     *     jvmArgs.set(updated)\n" +
                        "     * \n" +
                        "     * The cast below triggers UNCHECKED_CAST and\n" +
                        "     * UPPER_BOUND_VIOLATED_IN_TYPE_OPERATOR_OR_PARAMETER_BOUNDS_WARNING warnings;\n" +
                        "     * to silence them, add at the top of this script:\n" +
                        "     *     @file:Suppress(\"UNCHECKED_CAST\", \"UPPER_BOUND_VIOLATED_IN_TYPE_OPERATOR_OR_PARAMETER_BOUNDS_WARNING\")\n" +
                        "     */\n" +
                        "    (jvmArgs as org.gradle.api.internal.provider.DefaultListProperty<Any?>).replace { it.map { l -> l.toMutableList().apply { remove(\"-Xmx1g\") } } }\n" +
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

    @Test
    void rewritesJavaMapPropertyRemove() {
        // In Java, `.putIfAbsent(...)` / `.remove(...)` on a MapProperty are compile errors
        // post-migration. The mechanical rewrite uses the same internal `.replace(Transformer)`
        // API as Kotlin but in Java syntax, with an explicit HashMap copy in the transformer
        // body. Uses plain JavaParser (no Kotlin script mode).
        rewriteRun(
                spec -> spec.recipe(new MigratePropertyMutations())
                        .parser(JavaParser.fromJavaVersion().dependsOn(GradleApiStubs.ALL))
                        .typeValidationOptions(TypeValidation.builder()
                                .methodInvocations(false)
                                .identifiers(false)
                                .variableDeclarations(false)
                                .methodDeclarations(false)
                                .build()),
                java(
                        "import org.gradle.api.tasks.testing.Test;\n" +
                        "class P {\n" +
                        "    void cfg(Test test) {\n" +
                        "        test.getSystemProperties().remove(\"K\");\n" +
                        "    }\n" +
                        "}\n",
                        "import org.gradle.api.tasks.testing.Test;\n" +
                        "class P {\n" +
                        "    void cfg(Test test) {\n" +
                        "        /*\n" +
                        "         * TODO: Uses Gradle internal API (DefaultMapProperty). Fragile —\n" +
                        "         * consider the public copy-mutate-set form instead:\n" +
                        "         *     java.util.Map<Object, Object> updated = new java.util.HashMap<>(test.getSystemProperties().get());\n" +
                        "         *     updated.remove(\"K\");\n" +
                        "         *     test.getSystemProperties().set(updated);\n" +
                        "         * \n" +
                        "         * The raw-type cast below triggers a `rawtypes` compiler warning and the generic\n" +
                        "         * call triggers `unchecked` / `unchecked_cast` warnings. To silence them, annotate\n" +
                        "         * the enclosing method or class with:\n" +
                        "         *     @SuppressWarnings({\"rawtypes\", \"unchecked\"})\n" +
                        "         */\n" +
                        "        ((org.gradle.api.internal.provider.DefaultMapProperty) test.getSystemProperties()).replace(__provider -> __provider.map(m -> {\n" +
                        "            java.util.Map<Object, Object> __updated = new java.util.HashMap<>(m);\n" +
                        "            __updated.remove(\"K\");\n" +
                        "            return __updated;\n" +
                        "        }));\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }
}
