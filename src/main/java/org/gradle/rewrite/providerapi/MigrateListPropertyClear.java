package org.gradle.rewrite.providerapi;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import static org.gradle.rewrite.providerapi.internal.PropertyTypes.HAS_MULTIPLE_VALUES_FQN;
import static org.gradle.rewrite.providerapi.internal.PropertyTypes.LIST_PROPERTY_FQN;
import static org.gradle.rewrite.providerapi.internal.PropertyTypes.SET_PROPERTY_FQN;

/**
 * Rewrite {@code prop.clear()} to {@code prop.empty()} when {@code prop} is a {@code ListProperty} /
 * {@code SetProperty}.
 */
public class MigrateListPropertyClear extends Recipe {

    @Override
    public String getDisplayName() {
        return "Rewrite `listProp.clear()` to `listProp.empty()` on `ListProperty`/`SetProperty`";
    }

    @Override
    public String getDescription() {
        return "`HasMultipleValues` (the common supertype of `ListProperty`/`SetProperty`) exposes " +
               "`empty()` rather than `clear()` on the lazy API. This recipe renames matching sites.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if (!"clear".equals(m.getSimpleName()) || !m.getArguments().stream().allMatch(a -> a instanceof J.Empty)) {
                    return m;
                }
                if (m.getSelect() == null || !isMultiValued(m.getSelect().getType())) {
                    return m;
                }
                JavaType.FullyQualified receiverFq = (JavaType.FullyQualified) m.getSelect().getType();
                JavaType.Method emptyMethod = findNoArgMethod(receiverFq, "empty");
                if (emptyMethod == null) {
                    return m;
                }
                return m.withName(m.getName().withSimpleName("empty").withType(emptyMethod))
                        .withMethodType(emptyMethod);
            }

            private boolean isMultiValued(JavaType type) {
                JavaType.FullyQualified fq = type instanceof JavaType.FullyQualified
                        ? (JavaType.FullyQualified) type : null;
                if (fq == null) return false;
                if (matches(fq)) return true;
                JavaType.FullyQualified cursor = fq.getSupertype();
                while (cursor != null && cursor != fq) {
                    if (matches(cursor)) return true;
                    cursor = cursor.getSupertype();
                }
                for (JavaType.FullyQualified iface : fq.getInterfaces()) {
                    if (matches(iface)) return true;
                    for (JavaType.FullyQualified ifaceSuper : iface.getInterfaces()) {
                        if (matches(ifaceSuper)) return true;
                    }
                }
                return false;
            }

            private boolean matches(JavaType.FullyQualified fq) {
                String n = fq.getFullyQualifiedName();
                return LIST_PROPERTY_FQN.equals(n) || SET_PROPERTY_FQN.equals(n) || HAS_MULTIPLE_VALUES_FQN.equals(n);
            }

            private JavaType.Method findNoArgMethod(JavaType.FullyQualified fq, String name) {
                for (JavaType.Method method : fq.getMethods()) {
                    if (method.getName().equals(name) && method.getParameterTypes().isEmpty()) {
                        return method;
                    }
                }
                JavaType.FullyQualified sup = fq.getSupertype();
                if (sup != null && sup != fq) {
                    JavaType.Method r = findNoArgMethod(sup, name);
                    if (r != null) return r;
                }
                for (JavaType.FullyQualified iface : fq.getInterfaces()) {
                    JavaType.Method r = findNoArgMethod(iface, name);
                    if (r != null) return r;
                }
                return null;
            }
        };
    }
}
