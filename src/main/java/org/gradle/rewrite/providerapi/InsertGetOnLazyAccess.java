package org.gradle.rewrite.providerapi;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JContainer;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.gradle.rewrite.providerapi.internal.PropertyTypes.PROVIDER_FQN;

/**
 * Rewrite {@code expr.m(args)} to {@code expr.get().m(args)} when {@code expr}'s type is
 * {@code Provider<T>} / {@code Property<T>} but {@code m} is NOT a member of {@code Provider}.
 *
 * <p>This handles the "lazy value access" pattern from the migration report: old eager getters
 * returned {@code String}, {@code JavaVersion}, {@code URI}, etc.; the new lazy getters return
 * {@code Provider<T>}. Call sites that invoke a member of {@code T} (e.g. {@code compareTo},
 * {@code endsWith}, {@code toUpperCase}) must first unwrap with {@code .get()}.
 *
 * <p>The recipe is conservative: it only rewrites when {@code m} is known to be defined on the
 * Provider's value type AND not on {@code Provider} itself. This avoids wrapping lazy operations
 * like {@code map} / {@code flatMap} / {@code get} that already work on the Provider directly.
 */
public class InsertGetOnLazyAccess extends Recipe {

    /** Methods that exist on {@code Provider<T>} — calls to these stay as-is. */
    private static final Set<String> PROVIDER_METHODS = new HashSet<>();

    static {
        PROVIDER_METHODS.add("get");
        PROVIDER_METHODS.add("getOrNull");
        PROVIDER_METHODS.add("getOrElse");
        PROVIDER_METHODS.add("isPresent");
        PROVIDER_METHODS.add("map");
        PROVIDER_METHODS.add("flatMap");
        PROVIDER_METHODS.add("filter");
        PROVIDER_METHODS.add("orElse");
        PROVIDER_METHODS.add("forUseAtConfigurationTime");
        // Property adds set/convention/value/finalizeValue etc — keep those on the lazy type too.
        PROVIDER_METHODS.add("set");
        PROVIDER_METHODS.add("convention");
        PROVIDER_METHODS.add("value");
        PROVIDER_METHODS.add("finalizeValue");
        PROVIDER_METHODS.add("finalizeValueOnRead");
        PROVIDER_METHODS.add("disallowChanges");
        PROVIDER_METHODS.add("disallowUnsafeRead");
        // HasMultipleValues adds add/addAll/empty — also lazy-native.
        PROVIDER_METHODS.add("add");
        PROVIDER_METHODS.add("addAll");
        PROVIDER_METHODS.add("put");
        PROVIDER_METHODS.add("putAll");
        PROVIDER_METHODS.add("empty");
    }

    @Override
    public String getDisplayName() {
        return "Insert `.get()` before calls on `Provider<T>` that target a member of `T`";
    }

    @Override
    public String getDescription() {
        return "When a getter that used to return `T` now returns `Provider<T>`, existing call sites " +
               "like `expr.m(args)` where `m` is a member of `T` must add `.get()` first. This recipe " +
               "detects such sites and rewrites them to `expr.get().m(args)`, skipping calls that are " +
               "already Provider members (map/flatMap/get/etc.).";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                Expression select = m.getSelect();
                if (select == null) {
                    return m;
                }
                if (PROVIDER_METHODS.contains(m.getSimpleName())) {
                    return m;
                }
                if (!isProvider(select.getType())) {
                    return m;
                }

                JavaType.FullyQualified providerFq = (JavaType.FullyQualified) select.getType();
                JavaType.Method getMethodType = findNoArgMethod(providerFq, "get");
                if (getMethodType == null) {
                    return m;
                }

                J.Identifier getName = new J.Identifier(org.openrewrite.Tree.randomId(), Space.EMPTY,
                        m.getMarkers(), Collections.emptyList(), "get", getMethodType, null);
                J.MethodInvocation getCall = new J.MethodInvocation(
                        org.openrewrite.Tree.randomId(),
                        select.getPrefix(),
                        m.getMarkers(),
                        JRightPadded.build(select.withPrefix(Space.EMPTY)),
                        null,
                        getName,
                        JContainer.build(Space.EMPTY,
                                Collections.singletonList(
                                        JRightPadded.build(new J.Empty(org.openrewrite.Tree.randomId(),
                                                Space.EMPTY, m.getMarkers()))),
                                m.getMarkers()),
                        getMethodType
                );
                return m.getPadding().withSelect(JRightPadded.build((Expression) getCall));
            }

            private boolean isProvider(JavaType type) {
                JavaType.FullyQualified fq = type instanceof JavaType.FullyQualified
                        ? (JavaType.FullyQualified) type : null;
                if (fq == null) return false;
                if (PROVIDER_FQN.equals(fq.getFullyQualifiedName())) return true;
                JavaType.FullyQualified cursor = fq.getSupertype();
                while (cursor != null && cursor != fq) {
                    if (PROVIDER_FQN.equals(cursor.getFullyQualifiedName())) return true;
                    cursor = cursor.getSupertype();
                }
                for (JavaType.FullyQualified iface : fq.getInterfaces()) {
                    if (PROVIDER_FQN.equals(iface.getFullyQualifiedName())) return true;
                    for (JavaType.FullyQualified ifaceSuper : iface.getInterfaces()) {
                        if (PROVIDER_FQN.equals(ifaceSuper.getFullyQualifiedName())) return true;
                    }
                }
                return false;
            }

            private JavaType.Method findNoArgMethod(JavaType.FullyQualified fq, String name) {
                for (JavaType.Method method : fq.getMethods()) {
                    if (method.getName().equals(name) && method.getParameterTypes().isEmpty()) {
                        return method;
                    }
                }
                JavaType.FullyQualified sup = fq.getSupertype();
                if (sup != null && sup != fq) {
                    JavaType.Method m = findNoArgMethod(sup, name);
                    if (m != null) return m;
                }
                for (JavaType.FullyQualified iface : fq.getInterfaces()) {
                    JavaType.Method m = findNoArgMethod(iface, name);
                    if (m != null) return m;
                }
                return null;
            }
        };
    }
}
