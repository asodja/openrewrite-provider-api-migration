package org.gradle.rewrite.providerapi;

import org.gradle.rewrite.providerapi.internal.KotlinDslScope;
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

import org.gradle.rewrite.providerapi.internal.GradleBuildLogic;
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
        return GradleBuildLogic.onlyBuildLogic(new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                // Don't descend into the `.get()` invocation we ourselves inserted on a previous
                // cycle — otherwise we'd re-wrap `destinationDir` inside `destinationDir.get()`
                // and end up with `destinationDir.get().asFile.get().asFile.walkTopDown()`.
                if (isNoArgGet(method)) {
                    return method;
                }
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

            private boolean isNoArgGet(J.MethodInvocation m) {
                if (!"get".equals(m.getSimpleName())) return false;
                return m.getArguments().isEmpty()
                        || (m.getArguments().size() == 1 && m.getArguments().get(0) instanceof J.Empty);
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
                // Peel Kotlin `!!` non-null assertion first — `destinationDir!!.walkTopDown()`
                // should rewrite as if it were `destinationDir.walkTopDown()` (the `.get()`
                // return value is non-null, so `!!` is redundant after rewrite).
                Expression actual = unwrapNotNullAssertion(expr);

                String propName;
                Expression receiver;
                if (actual instanceof J.FieldAccess) {
                    J.FieldAccess fa = (J.FieldAccess) actual;
                    propName = fa.getName().getSimpleName();
                    receiver = fa.getTarget();
                } else if (actual instanceof J.Identifier) {
                    // Bare identifier — implicit-this access inside a Kotlin DSL lambda.
                    propName = ((J.Identifier) actual).getSimpleName();
                    receiver = null;
                } else if (actual instanceof J.MethodInvocation) {
                    J.MethodInvocation mi = (J.MethodInvocation) actual;
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
                Kind kind = null;
                if (receiver != null) {
                    JavaType.FullyQualified receiverFq = receiver.getType() instanceof JavaType.FullyQualified
                            ? (JavaType.FullyQualified) receiver.getType() : null;
                    kind = MigratedProperties.lookup(receiverFq, propName);
                } else {
                    // Scope fallback only for bare identifiers — see InsertGetOnLazyAccess for why
                    // an explicit chain with a null type should NOT steal the outer scope's receiver.
                    String scopeSimple = KotlinDslScope.findEnclosingTypedScope(getCursor());
                    if (scopeSimple != null) {
                        kind = MigratedProperties.lookupBySimpleName(scopeSimple, propName);
                    }
                }
                if (kind != Kind.DIRECTORY_PROPERTY && kind != Kind.REGULAR_FILE_PROPERTY) {
                    return expr;
                }
                // Wrap the UN-peeled expression — drops the `!!` along the way.
                return wrapWithGetAsFile(actual);
            }

            /** Peel one level of Kotlin {@code !!} non-null assertion, if present. */
            private Expression unwrapNotNullAssertion(Expression expr) {
                if (expr instanceof org.openrewrite.kotlin.tree.K.Unary) {
                    org.openrewrite.kotlin.tree.K.Unary u = (org.openrewrite.kotlin.tree.K.Unary) expr;
                    if (u.getOperator() == org.openrewrite.kotlin.tree.K.Unary.Type.NotNull) {
                        return u.getExpression();
                    }
                }
                return expr;
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
        });
    }
}
