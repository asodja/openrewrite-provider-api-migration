package org.gradle.rewrite.providerapi;

import org.gradle.rewrite.providerapi.internal.MigratedProperties;
import org.gradle.rewrite.providerapi.internal.MigratedProperties.Kind;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JContainer;
import org.openrewrite.java.tree.JLeftPadded;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;

import java.util.Collections;

/**
 * When a cataloged property became {@link Kind#DIRECTORY_PROPERTY} / {@link Kind#REGULAR_FILE_PROPERTY}
 * and the user chains a {@code File}-shaped call ({@code walkTopDown}, {@code absolutePath},
 * {@code readText}, {@code listFiles}), insert {@code .get().asFile} between the property and the chain.
 */
public class InsertAsFileGet extends Recipe {

    @Override
    public String getDisplayName() {
        return "Insert `.get().asFile` on cataloged `DirectoryProperty`/`RegularFileProperty` access";
    }

    @Override
    public String getDescription() {
        return "For properties cataloged as DIRECTORY_PROPERTY or REGULAR_FILE_PROPERTY, chained access " +
               "that expects a `File` (e.g. `destinationDir.walkTopDown()`) is rewritten to " +
               "`destinationDir.get().asFile.walkTopDown()`.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if (m.getSelect() == null) {
                    return m;
                }
                Expression newSelect = maybeWrap(m.getSelect());
                if (newSelect == m.getSelect()) {
                    return m;
                }
                return m.withSelect(newSelect);
            }

            @Override
            public J.FieldAccess visitFieldAccess(J.FieldAccess fa, ExecutionContext ctx) {
                J.FieldAccess f = super.visitFieldAccess(fa, ctx);
                Expression newTarget = maybeWrap(f.getTarget());
                if (newTarget == f.getTarget()) {
                    return f;
                }
                return f.withTarget(newTarget);
            }

            private Expression maybeWrap(Expression expr) {
                String propName;
                Expression receiver;
                if (expr instanceof J.FieldAccess) {
                    J.FieldAccess fa = (J.FieldAccess) expr;
                    propName = fa.getName().getSimpleName();
                    receiver = fa.getTarget();
                } else if (expr instanceof J.MethodInvocation) {
                    J.MethodInvocation mi = (J.MethodInvocation) expr;
                    String name = mi.getSimpleName();
                    if (!name.startsWith("get") || name.length() <= 3
                            || !Character.isUpperCase(name.charAt(3))) {
                        return expr;
                    }
                    if (!mi.getArguments().isEmpty()
                            && !(mi.getArguments().size() == 1 && mi.getArguments().get(0) instanceof J.Empty)) {
                        return expr;
                    }
                    propName = Character.toLowerCase(name.charAt(3)) + name.substring(4);
                    receiver = mi.getSelect();
                } else {
                    return expr;
                }
                if (receiver == null) {
                    return expr;
                }
                JavaType.FullyQualified receiverFq = receiver.getType() instanceof JavaType.FullyQualified
                        ? (JavaType.FullyQualified) receiver.getType() : null;
                Kind kind = MigratedProperties.lookup(receiverFq, propName);
                if (kind != Kind.DIRECTORY_PROPERTY && kind != Kind.REGULAR_FILE_PROPERTY) {
                    return expr;
                }
                return wrapWithGetAsFile(expr);
            }

            /** Build {@code expr.get().asFile}. */
            private Expression wrapWithGetAsFile(Expression expr) {
                J.Identifier getName = new J.Identifier(org.openrewrite.Tree.randomId(), Space.EMPTY,
                        expr.getMarkers(), Collections.emptyList(), "get", null, null);
                J.MethodInvocation getCall = new J.MethodInvocation(
                        org.openrewrite.Tree.randomId(),
                        Space.EMPTY,
                        expr.getMarkers(),
                        JRightPadded.build(expr.withPrefix(Space.EMPTY)),
                        null,
                        getName,
                        JContainer.build(Space.EMPTY,
                                Collections.singletonList(
                                        JRightPadded.build(new J.Empty(org.openrewrite.Tree.randomId(),
                                                Space.EMPTY, expr.getMarkers()))),
                                expr.getMarkers()),
                        null
                );
                J.Identifier asFileName = new J.Identifier(org.openrewrite.Tree.randomId(), Space.EMPTY,
                        expr.getMarkers(), Collections.emptyList(), "asFile", null, null);
                return new J.FieldAccess(
                        org.openrewrite.Tree.randomId(),
                        Space.EMPTY,
                        expr.getMarkers(),
                        getCall,
                        JLeftPadded.build(asFileName),
                        null
                );
            }
        };
    }
}
