package org.gradle.rewrite.providerapi;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import static org.gradle.rewrite.providerapi.internal.PropertyTypes.LIST_PROPERTY_FQN;
import static org.gradle.rewrite.providerapi.internal.PropertyTypes.SET_PROPERTY_FQN;

/**
 * Rewrite {@code prop.asList()} / {@code prop.asSet()} to {@code prop.get()} when {@code prop} is a
 * {@code ListProperty} / {@code SetProperty}.
 *
 * <p>The convenience {@code asList()} method does not exist on the lazy API — {@code get()} returns
 * a {@code List<T>} directly for {@code ListProperty<T>} (and {@code Set<T>} for {@code SetProperty<T>}).
 */
public class MigrateAsListToGet extends Recipe {

    @Override
    public String getDisplayName() {
        return "Rewrite `prop.asList()` to `prop.get()` on `ListProperty`/`SetProperty`";
    }

    @Override
    public String getDescription() {
        return "`asList()` / `asSet()` do not exist on the lazy API. `get()` returns the underlying " +
               "collection type directly.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                String name = m.getSimpleName();
                if (!("asList".equals(name) || "asSet".equals(name))) {
                    return m;
                }
                if (!m.getArguments().stream().allMatch(a -> a instanceof J.Empty)) {
                    return m;
                }
                if (m.getSelect() == null || !isMultiValued(m.getSelect().getType())) {
                    return m;
                }
                JavaType.FullyQualified receiverFq = (JavaType.FullyQualified) m.getSelect().getType();
                JavaType.Method getMethod = findNoArgMethod(receiverFq, "get");
                if (getMethod == null) {
                    return m;
                }
                return m.withName(m.getName().withSimpleName("get").withType(getMethod))
                        .withMethodType(getMethod);
            }

            private boolean isMultiValued(JavaType type) {
                JavaType.FullyQualified fq = type instanceof JavaType.FullyQualified
                        ? (JavaType.FullyQualified) type : null;
                if (fq == null) return false;
                String n = fq.getFullyQualifiedName();
                if (LIST_PROPERTY_FQN.equals(n) || SET_PROPERTY_FQN.equals(n)) return true;
                JavaType.FullyQualified cursor = fq.getSupertype();
                while (cursor != null && cursor != fq) {
                    String cn = cursor.getFullyQualifiedName();
                    if (LIST_PROPERTY_FQN.equals(cn) || SET_PROPERTY_FQN.equals(cn)) return true;
                    cursor = cursor.getSupertype();
                }
                for (JavaType.FullyQualified iface : fq.getInterfaces()) {
                    String in = iface.getFullyQualifiedName();
                    if (LIST_PROPERTY_FQN.equals(in) || SET_PROPERTY_FQN.equals(in)) return true;
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
