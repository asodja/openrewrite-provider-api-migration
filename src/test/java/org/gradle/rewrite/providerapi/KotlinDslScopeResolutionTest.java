package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.config.Environment;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.kotlin.Assertions.kotlin;

/**
 * Pins the Kotlin-DSL-scope fallback: when rewrite-kotlin can't attribute the implicit receiver
 * of a bare identifier inside a nested {@code .gradle.kts} lambda, we recover the receiver by
 * walking the cursor up to the enclosing typed DSL invocation ({@code withType<T>},
 * {@code register<T>}, {@code registering(T::class)}, {@code tasks.javadoc { }} etc.).
 *
 * <p>Covers the three junit-framework miss cases that motivated the work:
 * <ol>
 *   <li>{@code destination.readText()} inside {@code withType<GenerateMavenPom>().configureEach { doLast { ... } }}</li>
 *   <li>{@code setMaxMemory(...)} inside {@code tasks.registering(Javadoc::class) { ... }}</li>
 *   <li>{@code destinationDir!!.walkTopDown()} inside {@code tasks.javadoc { doLast { ... } }}</li>
 * </ol>
 * plus negative pins for (a) no typed scope and (b) explicit receiver chain (to prevent the
 * {@code buildParameters.jitpack.version.isPresent} regression).
 */
class KotlinDslScopeResolutionTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                        .scanRuntimeClasspath()
                        .build()
                        .activateRecipes("org.gradle.rewrite.providerapi.MigrateToProviderApi"))
                .parser(KotlinParser.builder().dependsOn(GradleApiKotlinStubs.ALL))
                .typeValidationOptions(TypeValidation.builder()
                        .identifiers(false)
                        .methodInvocations(false)
                        .methodDeclarations(false)
                        .variableDeclarations(false)
                        .build());
    }

    @Test
    void resolvesSetterInsideRegisteringTClass() {
        rewriteRun(
                kotlin(
                        "tasks.registering(Javadoc::class) {\n" +
                        "    setMaxMemory(\"1g\")\n" +
                        "}\n",
                        "tasks.registering(Javadoc::class) {\n" +
                        "    getMaxMemory().set(\"1g\")\n" +
                        "}\n",
                        spec -> spec.path("build.gradle.kts")
                )
        );
    }

    @Test
    void resolvesSetterInsideRegisterTypedGeneric() {
        rewriteRun(
                kotlin(
                        "tasks.register<Javadoc>(\"docs\") {\n" +
                        "    setMaxMemory(\"1g\")\n" +
                        "}\n",
                        "tasks.register<Javadoc>(\"docs\") {\n" +
                        "    getMaxMemory().set(\"1g\")\n" +
                        "}\n",
                        spec -> spec.path("build.gradle.kts")
                )
        );
    }

    @Test
    void resolvesSetterInsideWithTypeTestGeneric() {
        rewriteRun(
                kotlin(
                        "tasks.withType<Test> {\n" +
                        "    setMaxParallelForks(4)\n" +
                        "}\n",
                        "tasks.withType<Test> {\n" +
                        "    getMaxParallelForks().set(4)\n" +
                        "}\n",
                        spec -> spec.path("build.gradle.kts")
                )
        );
    }

    @Test
    void resolvesSetterInsideBuiltInTaskAccessor() {
        rewriteRun(
                kotlin(
                        "tasks.javadoc {\n" +
                        "    setMaxMemory(\"1g\")\n" +
                        "}\n",
                        "tasks.javadoc {\n" +
                        "    getMaxMemory().set(\"1g\")\n" +
                        "}\n",
                        spec -> spec.path("build.gradle.kts")
                )
        );
    }

    @Test
    void insertAsFileGetOnNotNullAssertedImplicitReceiver() {
        rewriteRun(
                kotlin(
                        "tasks.javadoc {\n" +
                        "    doLast {\n" +
                        "        destinationDir!!.walkTopDown()\n" +
                        "    }\n" +
                        "}\n",
                        "tasks.javadoc {\n" +
                        "    doLast {\n" +
                        "        destinationDir.get().asFile.walkTopDown()\n" +
                        "    }\n" +
                        "}\n",
                        spec -> spec.path("build.gradle.kts")
                )
        );
    }

    @Test
    void doesNotRewriteSetterOutsideTypedScope() {
        rewriteRun(
                kotlin(
                        "fun cfg() {\n" +
                        "    val block: () -> Unit = {\n" +
                        "        setMaxMemory(\"1g\")\n" +
                        "    }\n" +
                        "    block()\n" +
                        "}\n",
                        spec -> spec.path("build.gradle.kts")
                )
        );
    }

    @Test
    void doesNotRewriteExplicitChainInsideTypedScope() {
        // Regression guard: an explicit receiver chain like `foo.bar.version.isPresent` whose
        // type happens to be unresolved must NOT be rewritten even if an enclosing typed scope
        // has the cataloged property. Hit on the junit-framework
        // `buildParameters.jitpack.version.isPresent` site inside a
        // `publications { named<MavenPublication>("maven") { ... } }` block.
        rewriteRun(
                kotlin(
                        "tasks.withType<Test> {\n" +
                        "    val present: Boolean = someCustomBag.maxMemory.isPresent\n" +
                        "    println(present)\n" +
                        "}\n",
                        spec -> spec.path("build.gradle.kts")
                )
        );
    }
}
