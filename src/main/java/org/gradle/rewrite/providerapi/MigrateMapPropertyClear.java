package org.gradle.rewrite.providerapi;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import static org.gradle.rewrite.providerapi.internal.PropertyTypes.MAP_PROPERTY_FQN;

/**
 * Rewrite {@code prop.clear()} to {@code prop.empty()} when {@code prop} is a {@code MapProperty}.
 *
 * <p>{@code MapProperty} does not expose a {@code clear()} method; {@code empty()} is the equivalent on
 * the lazy API (empties the value without leaking configuration-time state).
 */
public class MigrateMapPropertyClear extends Recipe {

    @Override
    public String getDisplayName() {
        return "Rewrite `mapProp.clear()` to `mapProp.empty()`";
    }

    @Override
    public String getDescription() {
        return "`MapProperty` exposes `empty()` rather than `clear()` on the lazy API. This recipe " +
               "renames matching call sites.";
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
                if (m.getSelect() == null || !isMapProperty(m.getSelect().getType())) {
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

            private boolean isMapProperty(JavaType type) {
                JavaType.FullyQualified fq = type instanceof JavaType.FullyQualified
                        ? (JavaType.FullyQualified) type : null;
                if (fq == null) return false;
                if (MAP_PROPERTY_FQN.equals(fq.getFullyQualifiedName())) return true;
                JavaType.FullyQualified cursor = fq.getSupertype();
                while (cursor != null && cursor != fq) {
                    if (MAP_PROPERTY_FQN.equals(cursor.getFullyQualifiedName())) return true;
                    cursor = cursor.getSupertype();
                }
                for (JavaType.FullyQualified iface : fq.getInterfaces()) {
                    if (MAP_PROPERTY_FQN.equals(iface.getFullyQualifiedName())) return true;
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
