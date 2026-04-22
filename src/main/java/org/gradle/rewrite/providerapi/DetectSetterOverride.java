package org.gradle.rewrite.providerapi;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.marker.SearchResult;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.gradle.rewrite.providerapi.internal.GradleBuildLogic;
/**
 * Flag custom task subclasses that override a setter which the Provider API migration is removing.
 *
 * <p>After the migration, setters like {@code setClasspath(FileCollection)} no longer exist on the
 * parent Gradle type, so overriding them in a subclass is both a compile error and a logic-loss — any
 * reordering / preprocessing the override used to do must now move to the call site or to a
 * {@code Property} convention. Because this is a semantic-preserving move the recipe catalog treats it
 * as advisor-only (no mechanical rewrite).
 *
 * <p>This recipe attaches a {@link SearchResult} marker to any {@code set*} method in a class that
 * extends a known Gradle base whose same-named setter was removed.
 */
public class DetectSetterOverride extends Recipe {

    /** Hardcoded list of setters that the migration removes on core Gradle types. */
    private static final Set<String> REMOVED_SETTERS = new HashSet<>(Arrays.asList(
            "setClasspath",
            "setTestClassesDirs",
            "setMaxParallelForks",
            "setCompilerArgs",
            "setJvmArgs",
            "setSystemProperties",
            "setCommandLine",
            "setStandardOutput",
            "setErrorOutput",
            "setIgnoreExitValue",
            "setEnabled",
            "setWorkingDir",
            "setMaxMemory",
            "setSource",
            "setFailOnNoMatchingTests"
    ));

    /** Gradle base types whose subclasses should trip this recipe. */
    private static final Set<String> WATCHED_BASES = new HashSet<>(Arrays.asList(
            "org.gradle.api.tasks.testing.Test",
            "org.gradle.api.tasks.compile.JavaCompile",
            "org.gradle.api.tasks.compile.AbstractCompile",
            "org.gradle.api.tasks.Exec",
            "org.gradle.api.tasks.JavaExec",
            "org.gradle.api.tasks.SourceTask",
            "org.gradle.api.tasks.Delete",
            "org.gradle.process.ExecSpec"
    ));

    @Override
    public String getDisplayName() {
        return "Flag subclasses that override setters removed by the Provider API migration";
    }

    @Override
    public String getDescription() {
        return "Attaches a `SearchResult` marker to each `set*` method defined in a subclass of a core " +
               "Gradle type whose same-named setter was removed. Such overrides need to be refactored: " +
               "move the logic to the call site or to a `Property.convention(...)` / `finalizeValue()`.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return GradleBuildLogic.onlyBuildLogic(new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration md, ExecutionContext ctx) {
                J.MethodDeclaration m = super.visitMethodDeclaration(md, ctx);
                String name = m.getSimpleName();
                if (!REMOVED_SETTERS.contains(name)) {
                    return m;
                }
                J.ClassDeclaration parent = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (parent == null || parent.getExtends() == null) {
                    return m;
                }
                JavaType extendsType = parent.getExtends().getType();
                JavaType.FullyQualified fq = extendsType instanceof JavaType.FullyQualified
                        ? (JavaType.FullyQualified) extendsType : null;
                if (fq == null) {
                    return m;
                }
                if (!extendsWatched(fq)) {
                    return m;
                }
                String getterName = "get" + name.substring(3);
                String parentFqn = fq.getFullyQualifiedName();
                String message =
                        "Override of `" + name + "` on subclass of `" + parentFqn + "` — the Provider API " +
                        "migration removes this setter, so the override is orphaned. Options: " +
                        "(1) move the logic to each call site, or " +
                        "(2) configure `" + getterName + "().convention(...)` in the subclass constructor to preserve defaults, or " +
                        "(3) lift preprocessing into `" + getterName + "().finalizeValueOnRead()` plus a " +
                        "transform provider. Remove the `" + name + "(...)` method once callers are updated.";
                return SearchResult.found(m, message);
            }

            private boolean extendsWatched(JavaType.FullyQualified fq) {
                if (WATCHED_BASES.contains(fq.getFullyQualifiedName())) return true;
                JavaType.FullyQualified cursor = fq.getSupertype();
                while (cursor != null && cursor != fq) {
                    if (WATCHED_BASES.contains(cursor.getFullyQualifiedName())) return true;
                    cursor = cursor.getSupertype();
                }
                return false;
            }
        });
    }
}
