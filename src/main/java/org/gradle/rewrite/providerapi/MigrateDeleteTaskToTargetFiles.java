package org.gradle.rewrite.providerapi;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JContainer;
import org.openrewrite.java.tree.JLeftPadded;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.kotlin.KotlinVisitor;

import java.util.Collections;

import org.gradle.rewrite.providerapi.internal.GradleBuildLogic;
/**
 * Rewrite {@code deleteTask.delete = x} to {@code deleteTask.targetFiles.setFrom(x)}.
 *
 * <p>The {@code Delete} task's {@code delete} property was renamed to {@code targetFiles} and changed to a
 * read-only {@code ConfigurableFileCollection}. This recipe rewrites Kotlin assignments that targeted the
 * old name.
 */
public class MigrateDeleteTaskToTargetFiles extends Recipe {

    private static final String DELETE_FQN = "org.gradle.api.tasks.Delete";

    @Override
    public String getDisplayName() {
        return "Rewrite `Delete.delete = x` to `Delete.targetFiles.setFrom(x)`";
    }

    @Override
    public String getDescription() {
        return "The `Delete` task's `delete` property was renamed to `targetFiles` and is now a read-only " +
               "`ConfigurableFileCollection`. This recipe rewrites Kotlin assignments against the old name.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return GradleBuildLogic.onlyBuildLogic(new KotlinVisitor<ExecutionContext>() {
            @Override
            public J visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
                J.Assignment a = (J.Assignment) super.visitAssignment(assignment, ctx);
                if (!(a.getVariable() instanceof J.FieldAccess)) {
                    return a;
                }
                J.FieldAccess lhs = (J.FieldAccess) a.getVariable();
                if (!"delete".equals(lhs.getName().getSimpleName())) {
                    return a;
                }
                Expression receiver = lhs.getTarget();
                JavaType.FullyQualified fq = receiver.getType() instanceof JavaType.FullyQualified
                        ? (JavaType.FullyQualified) receiver.getType() : null;
                if (fq == null || !DELETE_FQN.equals(fq.getFullyQualifiedName())) {
                    return a;
                }

                JavaType.Method getTargetFiles = findNoArgMethod(fq, "getTargetFiles");
                if (getTargetFiles == null) {
                    return a;
                }
                JavaType.FullyQualified cfcType = getTargetFiles.getReturnType() instanceof JavaType.FullyQualified
                        ? (JavaType.FullyQualified) getTargetFiles.getReturnType() : null;
                if (cfcType == null) {
                    return a;
                }
                JavaType.Method setFrom = findSetFrom(cfcType);
                if (setFrom == null) {
                    return a;
                }

                // receiver.targetFiles.setFrom(x)
                JavaType.Variable targetFilesVar = new JavaType.Variable(null, 1, "targetFiles",
                        fq, getTargetFiles.getReturnType(), Collections.emptyList());
                J.Identifier targetFiles = new J.Identifier(org.openrewrite.Tree.randomId(), Space.EMPTY,
                        a.getMarkers(), Collections.emptyList(), "targetFiles",
                        getTargetFiles.getReturnType(), targetFilesVar);
                J.FieldAccess targetFilesAccess = new J.FieldAccess(org.openrewrite.Tree.randomId(),
                        Space.EMPTY, a.getMarkers(),
                        receiver,
                        JLeftPadded.build(targetFiles),
                        getTargetFiles.getReturnType());

                J.Identifier setFromName = new J.Identifier(org.openrewrite.Tree.randomId(), Space.EMPTY,
                        a.getMarkers(), Collections.emptyList(), "setFrom", setFrom, null);
                return new J.MethodInvocation(
                        org.openrewrite.Tree.randomId(),
                        a.getPrefix(),
                        a.getMarkers(),
                        JRightPadded.build((Expression) targetFilesAccess),
                        null,
                        setFromName,
                        JContainer.build(Space.EMPTY,
                                Collections.singletonList(
                                        JRightPadded.build(a.getAssignment().withPrefix(Space.EMPTY))),
                                a.getMarkers()),
                        setFrom
                );
            }

            private JavaType.Method findNoArgMethod(JavaType.FullyQualified fq, String name) {
                for (JavaType.Method m : fq.getMethods()) {
                    if (m.getName().equals(name) && m.getParameterTypes().isEmpty()) {
                        return m;
                    }
                }
                JavaType.FullyQualified sup = fq.getSupertype();
                if (sup != null && sup != fq) return findNoArgMethod(sup, name);
                return null;
            }

            private JavaType.Method findSetFrom(JavaType.FullyQualified fq) {
                for (JavaType.Method m : fq.getMethods()) {
                    if (m.getName().equals("setFrom") && m.getParameterTypes().size() == 1) {
                        return m;
                    }
                }
                JavaType.FullyQualified sup = fq.getSupertype();
                if (sup != null && sup != fq) {
                    JavaType.Method m = findSetFrom(sup);
                    if (m != null) return m;
                }
                for (JavaType.FullyQualified iface : fq.getInterfaces()) {
                    JavaType.Method m = findSetFrom(iface);
                    if (m != null) return m;
                }
                return null;
            }
        });
    }
}
