package org.gradle.rewrite.providerapi;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.groovy.GroovyVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.JContainer;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.Space;

import java.util.Collections;

import org.gradle.rewrite.providerapi.internal.GradleBuildLogic;
/**
 * Rewrite {@code sourceTask.source = x} to {@code sourceTask.setSource(x)} in Groovy build scripts.
 *
 * <p>After the migration, {@code SourceTask.source} returns a read-only {@code FileCollection}, so direct
 * assignment is a compile error. The remaining {@code setSource(Object)} method preserves the old semantics.
 */
public class MigrateSourceAssignment extends Recipe {

    private static final String SOURCE_TASK_FQN = "org.gradle.api.tasks.SourceTask";

    @Override
    public String getDisplayName() {
        return "Rewrite `sourceTask.source = x` to `sourceTask.setSource(x)`";
    }

    @Override
    public String getDescription() {
        return "`SourceTask.source` became read-only after the Provider API migration. This recipe rewrites " +
               "Groovy build-script assignments like `sourceTask.source = x` to method calls " +
               "`sourceTask.setSource(x)` which retain the old semantics.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return GradleBuildLogic.onlyBuildLogic(new GroovyVisitor<ExecutionContext>() {
            @Override
            public J visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
                J.Assignment a = (J.Assignment) super.visitAssignment(assignment, ctx);
                if (!(a.getVariable() instanceof J.FieldAccess)) {
                    return a;
                }
                J.FieldAccess lhs = (J.FieldAccess) a.getVariable();
                if (!"source".equals(lhs.getName().getSimpleName())) {
                    return a;
                }
                Expression receiver = lhs.getTarget();
                if (!isSourceTask(receiver.getType())) {
                    return a;
                }
                JavaType.FullyQualified sourceTask = (JavaType.FullyQualified) receiver.getType();
                JavaType.Method setSource = findSetSource(sourceTask);
                if (setSource == null) {
                    return a;
                }

                J.Identifier name = new J.Identifier(org.openrewrite.Tree.randomId(), Space.EMPTY,
                        a.getMarkers(), Collections.emptyList(), "setSource", setSource, null);
                return new J.MethodInvocation(
                        org.openrewrite.Tree.randomId(),
                        a.getPrefix(),
                        a.getMarkers(),
                        JRightPadded.build(receiver),
                        null,
                        name,
                        JContainer.build(Space.EMPTY,
                                Collections.singletonList(
                                        JRightPadded.build(a.getAssignment().withPrefix(Space.EMPTY))),
                                a.getMarkers()),
                        setSource
                );
            }

            private boolean isSourceTask(JavaType type) {
                JavaType.FullyQualified fq = type instanceof JavaType.FullyQualified
                        ? (JavaType.FullyQualified) type : null;
                if (fq == null) return false;
                if (SOURCE_TASK_FQN.equals(fq.getFullyQualifiedName())) return true;
                JavaType.FullyQualified cursor = fq.getSupertype();
                while (cursor != null && cursor != fq) {
                    if (SOURCE_TASK_FQN.equals(cursor.getFullyQualifiedName())) return true;
                    cursor = cursor.getSupertype();
                }
                return false;
            }

            private JavaType.Method findSetSource(JavaType.FullyQualified declaring) {
                for (JavaType.Method m : declaring.getMethods()) {
                    if ("setSource".equals(m.getName()) && m.getParameterTypes().size() == 1) {
                        return m;
                    }
                }
                JavaType.FullyQualified supertype = declaring.getSupertype();
                if (supertype != null && supertype != declaring) {
                    return findSetSource(supertype);
                }
                return null;
            }
        });
    }
}
