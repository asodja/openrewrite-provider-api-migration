package org.gradle.rewrite.providerapi.internal;

import org.openrewrite.ExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Autodiscovery for Gradle build-logic sources — used by recipes as a fallback when the
 * manifest-driven classification from {@code discovery-init.gradle.kts} isn't available
 * (e.g. when a user runs these recipes via the rewrite-gradle-plugin directly).
 *
 * <p>A file is considered build-logic if any of these hold:
 * <ol>
 *   <li>Its path ends in {@code .gradle} or {@code .gradle.kts} — by definition a Gradle script.</li>
 *   <li>It has at least one import from {@code org.gradle.*} — true for {@code buildSrc/},
 *       included build-logic builds, custom convention-plugin layouts, and Gradle plugins
 *       published as production artifacts.</li>
 * </ol>
 *
 * <p>Heuristic caveat: this check fires on any file that imports {@code org.gradle.*}, which
 * also matches Gradle's own source tree (gradle/gradle) and Tooling-API consumer libraries —
 * projects whose source we do <em>not</em> want to migrate. The standalone-runner path sidesteps
 * this by using the Gradle model (project applies {@code java-gradle-plugin}) to classify
 * build-logic at manifest time. Prefer the runner when operating on codebases that mix Gradle
 * production source with build-logic (gradle/gradle, plugin SDKs, etc.).
 */
public final class GradleBuildLogic {

    private GradleBuildLogic() {}

    /** Whether the source file is Kotlin ({@code .kt}, {@code .kts}, or {@code .gradle.kts}). */
    public static boolean isKotlin(SourceFile sourceFile) {
        if (sourceFile == null) return false;
        String path = sourceFile.getSourcePath().toString().replace('\\', '/');
        return path.endsWith(".kt") || path.endsWith(".kts");
    }

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
        // Check imports directly on the compilation unit. Works for Java (J.CompilationUnit), Groovy
        // (G.CompilationUnit extending J.CompilationUnit), and Kotlin (K.CompilationUnit exposes its
        // imports via the same getImports() accessor).
        if (sourceFile instanceof J.CompilationUnit) {
            for (J.Import imp : ((J.CompilationUnit) sourceFile).getImports()) {
                if (imp.getPackageName().startsWith("org.gradle.")) {
                    return true;
                }
            }
        }
        // K.CompilationUnit has a separate import list. Try reflectively to avoid a hard dep on K.
        try {
            java.lang.reflect.Method getImports = sourceFile.getClass().getMethod("getImports");
            Object result = getImports.invoke(sourceFile);
            if (result instanceof Iterable) {
                for (Object entry : (Iterable<?>) result) {
                    if (entry instanceof J.Import) {
                        if (((J.Import) entry).getPackageName().startsWith("org.gradle.")) {
                            return true;
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Not a compilation unit type we know about — fall through.
        }
        return false;
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
