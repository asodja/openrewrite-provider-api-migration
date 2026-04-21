package org.gradle.rewrite.providerapi;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JContainer;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Rewrite {@code exec.commandLine = list} to {@code exec.commandLine(list)} when {@code exec} is an
 * {@code ExecSpec} / {@code Exec} / {@code JavaExec}.
 *
 * <p>After the migration, {@code commandLine} is a read-only {@code ListProperty<String>} getter — the
 * eager setter-form assignment is a compile error. The existing {@code commandLine(Iterable<?>)} method
 * overwrites the value with the old semantics.
 *
 * <p>This recipe visits Kotlin/Groovy source (via {@link JavaVisitor} since both languages share the
 * underlying J LST for assignments).
 */
public class MigrateReadOnlyCommandLineAssignment extends Recipe {

    private static final List<String> RECEIVER_FQNS = Arrays.asList(
            "org.gradle.process.ExecSpec",
            "org.gradle.api.tasks.Exec",
            "org.gradle.api.tasks.JavaExec"
    );

    @Override
    public String getDisplayName() {
        return "Rewrite `exec.commandLine = list` to `exec.commandLine(list)`";
    }

    @Override
    public String getDescription() {
        return "`commandLine` became a read-only `ListProperty<String>` getter. Direct assignment no " +
               "longer compiles; the `commandLine(Iterable<?>)` method preserves the old overwrite " +
               "semantics.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
                J visited = super.visitAssignment(assignment, ctx);
                if (!(visited instanceof J.Assignment)) {
                    return visited;
                }
                J.Assignment a = (J.Assignment) visited;
                if (!(a.getVariable() instanceof J.FieldAccess)) {
                    return a;
                }
                J.FieldAccess lhs = (J.FieldAccess) a.getVariable();
                if (!"commandLine".equals(lhs.getName().getSimpleName())) {
                    return a;
                }
                Expression receiver = lhs.getTarget();
                JavaType.FullyQualified fq = receiver.getType() instanceof JavaType.FullyQualified
                        ? (JavaType.FullyQualified) receiver.getType() : null;
                if (!isExecReceiver(fq)) {
                    return a;
                }
                JavaType.Method cmdLineMethod = findMethod(fq, "commandLine", 1);
                if (cmdLineMethod == null) {
                    return a;
                }
                J.Identifier cmdLineName = new J.Identifier(org.openrewrite.Tree.randomId(), Space.EMPTY,
                        a.getMarkers(), Collections.emptyList(), "commandLine", cmdLineMethod, null);
                return new J.MethodInvocation(
                        org.openrewrite.Tree.randomId(),
                        a.getPrefix(),
                        a.getMarkers(),
                        JRightPadded.build(receiver.withPrefix(Space.EMPTY)),
                        null,
                        cmdLineName,
                        JContainer.build(Space.EMPTY,
                                Collections.singletonList(
                                        JRightPadded.build(a.getAssignment().withPrefix(Space.EMPTY))),
                                a.getMarkers()),
                        cmdLineMethod
                );
            }

            private boolean isExecReceiver(JavaType.FullyQualified fq) {
                if (fq == null) return false;
                if (RECEIVER_FQNS.contains(fq.getFullyQualifiedName())) return true;
                JavaType.FullyQualified cursor = fq.getSupertype();
                while (cursor != null && cursor != fq) {
                    if (RECEIVER_FQNS.contains(cursor.getFullyQualifiedName())) return true;
                    cursor = cursor.getSupertype();
                }
                for (JavaType.FullyQualified iface : fq.getInterfaces()) {
                    if (RECEIVER_FQNS.contains(iface.getFullyQualifiedName())) return true;
                }
                return false;
            }

            private JavaType.Method findMethod(JavaType.FullyQualified fq, String name, int arity) {
                for (JavaType.Method m : fq.getMethods()) {
                    if (m.getName().equals(name) && m.getParameterTypes().size() == arity) {
                        return m;
                    }
                }
                JavaType.FullyQualified sup = fq.getSupertype();
                if (sup != null && sup != fq) {
                    JavaType.Method m = findMethod(sup, name, arity);
                    if (m != null) return m;
                }
                for (JavaType.FullyQualified iface : fq.getInterfaces()) {
                    JavaType.Method m = findMethod(iface, name, arity);
                    if (m != null) return m;
                }
                return null;
            }
        };
    }
}
