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

import java.util.Collections;

import static org.gradle.rewrite.providerapi.internal.PropertyTypes.HAS_MULTIPLE_VALUES_FQN;
import static org.gradle.rewrite.providerapi.internal.PropertyTypes.LIST_PROPERTY_FQN;
import static org.gradle.rewrite.providerapi.internal.PropertyTypes.SET_PROPERTY_FQN;

/**
 * Rewrite {@code prop += v} to {@code prop.add(v)} when {@code prop} is a {@code ListProperty<T>} or
 * {@code SetProperty<T>} (i.e. implements {@code HasMultipleValues}).
 *
 * <p>The {@code +=} form in Java/Kotlin relied on the removed eager setter + getter. The replacement on
 * the lazy API is {@code prop.add(v)} for a single value; sibling recipes handle {@code += [a, b]}
 * (addAll) and Groovy-specific {@code prop += x} patterns.
 */
public class MigrateListPropertyPlusAssign extends Recipe {

    @Override
    public String getDisplayName() {
        return "Rewrite `prop += v` to `prop.add(v)` on `ListProperty`/`SetProperty`";
    }

    @Override
    public String getDescription() {
        return "When `prop` is a `ListProperty<T>` or `SetProperty<T>`, `prop += v` is no longer valid " +
               "after the migration because the eager setter/getter pair was removed. This recipe " +
               "rewrites each such site to `prop.add(v)`.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitAssignmentOperation(J.AssignmentOperation op, ExecutionContext ctx) {
                J visited = super.visitAssignmentOperation(op, ctx);
                if (!(visited instanceof J.AssignmentOperation)) {
                    return visited;
                }
                J.AssignmentOperation a = (J.AssignmentOperation) visited;
                if (a.getOperator() != J.AssignmentOperation.Type.Addition) {
                    return a;
                }
                JavaType.FullyQualified fq = asFullyQualified(a.getVariable().getType());
                if (!isMultiValued(fq)) {
                    return a;
                }
                JavaType.Method addMethod = findAddMethod(fq);
                if (addMethod == null) {
                    return a;
                }
                J.Identifier addName = new J.Identifier(org.openrewrite.Tree.randomId(), Space.EMPTY,
                        a.getMarkers(), Collections.emptyList(), "add", addMethod, null);
                return new J.MethodInvocation(
                        org.openrewrite.Tree.randomId(),
                        a.getPrefix(),
                        a.getMarkers(),
                        JRightPadded.build((Expression) a.getVariable().withPrefix(Space.EMPTY)),
                        null,
                        addName,
                        JContainer.build(Space.EMPTY,
                                Collections.singletonList(
                                        JRightPadded.build(a.getAssignment().withPrefix(Space.EMPTY))),
                                a.getMarkers()),
                        addMethod
                );
            }

            private boolean isMultiValued(JavaType.FullyQualified fq) {
                if (fq == null) return false;
                if (matchesAny(fq)) return true;
                JavaType.FullyQualified cursor = fq.getSupertype();
                while (cursor != null && cursor != fq) {
                    if (matchesAny(cursor)) return true;
                    cursor = cursor.getSupertype();
                }
                for (JavaType.FullyQualified iface : fq.getInterfaces()) {
                    if (matchesAny(iface)) return true;
                    for (JavaType.FullyQualified ifaceSuper : iface.getInterfaces()) {
                        if (matchesAny(ifaceSuper)) return true;
                    }
                }
                return false;
            }

            private boolean matchesAny(JavaType.FullyQualified fq) {
                String n = fq.getFullyQualifiedName();
                return LIST_PROPERTY_FQN.equals(n)
                        || SET_PROPERTY_FQN.equals(n)
                        || HAS_MULTIPLE_VALUES_FQN.equals(n);
            }

            private JavaType.Method findAddMethod(JavaType.FullyQualified declaring) {
                for (JavaType.Method m : declaring.getMethods()) {
                    if ("add".equals(m.getName()) && m.getParameterTypes().size() == 1) {
                        return m;
                    }
                }
                JavaType.FullyQualified sup = declaring.getSupertype();
                if (sup != null && sup != declaring) {
                    JavaType.Method m = findAddMethod(sup);
                    if (m != null) return m;
                }
                for (JavaType.FullyQualified iface : declaring.getInterfaces()) {
                    JavaType.Method m = findAddMethod(iface);
                    if (m != null) return m;
                }
                return null;
            }

            private JavaType.FullyQualified asFullyQualified(JavaType t) {
                return t instanceof JavaType.FullyQualified ? (JavaType.FullyQualified) t : null;
            }
        };
    }
}
