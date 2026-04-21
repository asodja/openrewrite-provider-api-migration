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

import static org.gradle.rewrite.providerapi.internal.PropertyTypes.MAP_PROPERTY_FQN;

/**
 * Rewrite {@code prop["k"] = v} to {@code prop.put("k", v)} when {@code prop} is a
 * {@code MapProperty<K, V>}.
 *
 * <p>Before migration, Kotlin/Groovy's indexed-assign shorthand dispatched through the old
 * {@code Map<K, V>} API. After migration the setter disappears; {@code put} is the canonical
 * insert.
 */
public class MigrateMapPropertyIndexedAssign extends Recipe {

    @Override
    public String getDisplayName() {
        return "Rewrite `prop[\"k\"] = v` to `prop.put(\"k\", v)` on `MapProperty<K, V>`";
    }

    @Override
    public String getDescription() {
        return "Kotlin/Groovy indexed-assignment on a `MapProperty<K, V>` no longer works after the " +
               "migration. This recipe rewrites each site to `prop.put(key, value)`.";
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
                if (!(a.getVariable() instanceof J.ArrayAccess)) {
                    return a;
                }
                J.ArrayAccess arr = (J.ArrayAccess) a.getVariable();
                Expression receiver = arr.getIndexed();
                JavaType.FullyQualified fq = receiver.getType() instanceof JavaType.FullyQualified
                        ? (JavaType.FullyQualified) receiver.getType() : null;
                if (!isMapProperty(fq)) {
                    return a;
                }
                Expression key = arr.getDimension().getIndex();
                if (key == null) {
                    return a;
                }
                JavaType.Method putMethod = findMethod(fq, "put", 2);
                if (putMethod == null) {
                    return a;
                }
                J.Identifier putName = new J.Identifier(org.openrewrite.Tree.randomId(), Space.EMPTY,
                        a.getMarkers(), Collections.emptyList(), "put", putMethod, null);
                return new J.MethodInvocation(
                        org.openrewrite.Tree.randomId(),
                        a.getPrefix(),
                        a.getMarkers(),
                        JRightPadded.build(receiver.withPrefix(Space.EMPTY)),
                        null,
                        putName,
                        JContainer.build(Space.EMPTY, Arrays.asList(
                                JRightPadded.build(key.withPrefix(Space.EMPTY)),
                                JRightPadded.build(a.getAssignment().withPrefix(Space.format(" ")))),
                                a.getMarkers()),
                        putMethod
                );
            }

            private boolean isMapProperty(JavaType.FullyQualified fq) {
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
