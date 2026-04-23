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
import java.util.HashSet;
import java.util.Set;

import org.gradle.rewrite.providerapi.internal.GradleBuildLogic;
/**
 * Insert {@code .get()} when a cataloged migrated property is used eagerly.
 *
 * <p>Catalog-driven — looks for {@code recv.propName.m(...)} / {@code recv.propName.fieldAccess} where
 * {@code (recv's type, propName)} is in {@link MigratedProperties} as {@link Kind#SCALAR_PROPERTY}.
 * The access shape implies the user expected the <em>old</em> eager value: after the migration the
 * expression returns a {@code Provider<T>}, so the chained access is a compile error. This recipe
 * inserts {@code .get()} between the property access and the chained member.
 *
 * <p>For {@link Kind#DIRECTORY_PROPERTY} / {@link Kind#REGULAR_FILE_PROPERTY}, use
 * {@link InsertAsFileGet} instead — {@code .get()} alone returns a {@code Directory} / {@code RegularFile}
 * rather than a {@code File}, so an {@code .asFile} step is needed too.
 */
public class InsertGetOnLazyAccess extends Recipe {

    /** Methods that exist on {@code Provider<T>} itself — chaining these is fine, no {@code .get()} needed. */
    private static final Set<String> PROVIDER_NATIVE_METHODS = new HashSet<>();

    static {
        PROVIDER_NATIVE_METHODS.add("get");
        PROVIDER_NATIVE_METHODS.add("getOrNull");
        PROVIDER_NATIVE_METHODS.add("getOrElse");
        PROVIDER_NATIVE_METHODS.add("isPresent");
        PROVIDER_NATIVE_METHODS.add("map");
        PROVIDER_NATIVE_METHODS.add("flatMap");
        PROVIDER_NATIVE_METHODS.add("filter");
        PROVIDER_NATIVE_METHODS.add("orElse");
        // Property and its subtypes:
        PROVIDER_NATIVE_METHODS.add("set");
        PROVIDER_NATIVE_METHODS.add("convention");
        PROVIDER_NATIVE_METHODS.add("value");
        PROVIDER_NATIVE_METHODS.add("finalizeValue");
        PROVIDER_NATIVE_METHODS.add("finalizeValueOnRead");
        PROVIDER_NATIVE_METHODS.add("disallowChanges");
        PROVIDER_NATIVE_METHODS.add("disallowUnsafeRead");
        // HasMultipleValues:
        PROVIDER_NATIVE_METHODS.add("add");
        PROVIDER_NATIVE_METHODS.add("addAll");
        PROVIDER_NATIVE_METHODS.add("put");
        PROVIDER_NATIVE_METHODS.add("putAll");
        PROVIDER_NATIVE_METHODS.add("empty");
    }

    @Override
    public String getDisplayName() {
        return "Insert `.get()` before eager access on cataloged lazy properties";
    }

    @Override
    public String getDescription() {
        return "When a property migrated to `Property<T>`, eager chained access like " +
               "`recv.propName.someMethod(...)` no longer compiles. This recipe consults the " +
               "`MigratedProperties` catalog and inserts `.get()` between the property and the " +
               "chained member. Kind-aware: only fires for `SCALAR_PROPERTY`.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return GradleBuildLogic.onlyBuildLogic(new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                // Don't descend into a no-arg `.get()` call — if we did, we'd re-wrap the receiver
                // of an already-inserted `.get()` on every subsequent recipe cycle.
                if (isNoArgGet(method)) {
                    return method;
                }
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if (m.getSelect() == null) {
                    return m;
                }
                if (PROVIDER_NATIVE_METHODS.contains(m.getSimpleName())) {
                    return m;
                }
                Expression newSelect = maybeWrapWithGet(m.getSelect());
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
            public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
                J.FieldAccess f = super.visitFieldAccess(fieldAccess, ctx);
                Expression newTarget = maybeWrapWithGet(f.getTarget());
                if (newTarget == f.getTarget()) {
                    return f;
                }
                return f.withTarget(newTarget);
            }

            /**
             * If {@code expr} is a {@code recv.propName} access where {@code propName} is a cataloged
             * {@code SCALAR_PROPERTY}, return {@code recv.propName.get()}. Otherwise return the input
             * unchanged.
             */
            private Expression maybeWrapWithGet(Expression expr) {
                // Peel Kotlin `!!` non-null assertion — the underlying property still gets `.get()`
                // inserted, and `!!` becomes redundant on the resulting non-null Provider value.
                Expression actual = unwrapNotNullAssertion(expr);

                String propName;
                Expression receiver;
                if (actual instanceof J.FieldAccess) {
                    J.FieldAccess fa = (J.FieldAccess) actual;
                    propName = fa.getName().getSimpleName();
                    receiver = fa.getTarget();
                } else if (actual instanceof J.Identifier) {
                    propName = ((J.Identifier) actual).getSimpleName();
                    receiver = null;
                } else if (actual instanceof J.MethodInvocation) {
                    J.MethodInvocation mi = (J.MethodInvocation) actual;
                    String name = mi.getSimpleName();
                    if (!name.startsWith("get") || name.length() <= 3
                            || !Character.isUpperCase(name.charAt(3))) {
                        return expr;
                    }
                    // Ensure no args.
                    if (!(mi.getArguments().size() == 1 && mi.getArguments().get(0) instanceof J.Empty)
                            && !mi.getArguments().isEmpty()) {
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
                    // ONLY for bare identifiers (implicit-this inside a Kotlin DSL lambda) do we
                    // fall back to scope resolution. An explicit receiver chain like `foo.bar.version`
                    // whose type is unresolved shouldn't steal the outer scope's receiver — doing so
                    // misfires on e.g. `buildParameters.jitpack.version.isPresent` inside a
                    // `publications { named<MavenPublication>("maven") { ... } }` block, where
                    // `version` is a cataloged MavenPublication property but the ACTUAL receiver
                    // is some unrelated custom extension chain.
                    String scopeSimple = KotlinDslScope.findEnclosingTypedScope(getCursor());
                    if (scopeSimple != null) {
                        kind = MigratedProperties.lookupBySimpleName(scopeSimple, propName);
                    }
                }
                if (kind != Kind.SCALAR_PROPERTY) {
                    return expr;
                }
                // From here on, the target becomes `actual` so the rebuild drops any `!!`.
                expr = actual;
                // Build expr.get() with null type (re-attributed on the next parse cycle).
                J.Identifier getName = new J.Identifier(org.openrewrite.Tree.randomId(), Space.EMPTY,
                        expr.getMarkers(), Collections.emptyList(), "get", null, null);
                return new J.MethodInvocation(
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
        });
    }
}
