package org.gradle.rewrite.providerapi.internal;

import org.openrewrite.ExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Autodiscovery for Gradle build-logic sources.
 *
 * <p>A file is considered build-logic if any of these hold:
 * <ol>
 *   <li>Its path ends in {@code .gradle} or {@code .gradle.kts} — by definition a Gradle script.</li>
 *   <li>It has at least one import from {@code org.gradle.*} — true for {@code buildSrc/},
 *       included build-logic builds, custom convention-plugin layouts, and even Gradle plugins
 *       published as production artifacts.</li>
 * </ol>
 *
 * <p>This beats hard-coding directory names (like {@code buildSrc/}) because users park build-logic
 * in arbitrary locations ({@code conventions/}, {@code build-logic/}, {@code infra/}, or anywhere
 * registered via {@code includeBuild}). The import check captures them all.
 *
 * <p>It correctly excludes production business logic and tests that don't touch Gradle API.
 */
public final class GradleBuildLogic {

    private GradleBuildLogic() {}

    /**
     * Return {@code true} if the given source file is a Gradle build-logic artifact that our
     * migration recipes should process. Returns {@code false} for production code, test code, or
     * other sources that don't interact with the Gradle API.
     */
    public static boolean isBuildLogic(SourceFile sourceFile) {
        if (sourceFile == null) {
            return false;
        }
        String path = sourceFile.getSourcePath().toString().replace('\\', '/');
        if (path.endsWith(".gradle") || path.endsWith(".gradle.kts")) {
            return true;
        }
        if (path.endsWith("settings.gradle") || path.endsWith("settings.gradle.kts")
                || path.endsWith("init.gradle") || path.endsWith("init.gradle.kts")) {
            return true;
        }
        // Walk imports. Works for Java (J.CompilationUnit), Groovy (extends J.CompilationUnit), and
        // Kotlin (K.CompilationUnit uses the same J.Import node for import declarations).
        AtomicBoolean found = new AtomicBoolean();
        new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.Import visitImport(J.Import imp, AtomicBoolean acc) {
                if (imp.getPackageName().startsWith("org.gradle.")) {
                    acc.set(true);
                }
                return imp;
            }
        }.visit(sourceFile, found);
        return found.get();
    }

    /**
     * Wrap a recipe visitor so it only processes build-logic sources. Use from
     * {@code Recipe.getVisitor()} like so:
     * <pre>
     *   return GradleBuildLogic.onlyBuildLogic(new MyActualVisitor());
     * </pre>
     * The inner visitor is delegated to for every method; only {@link TreeVisitor#isAcceptable}
     * is overridden to combine "is build-logic" with the inner's own acceptability check.
     */
    public static <V extends TreeVisitor<?, ExecutionContext>> TreeVisitor<?, ExecutionContext> onlyBuildLogic(
            V inner) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public boolean isAcceptable(SourceFile sourceFile, ExecutionContext ctx) {
                return isBuildLogic(sourceFile) && inner.isAcceptable(sourceFile, ctx);
            }

            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                return inner.visit(tree, ctx);
            }
        };
    }
}
