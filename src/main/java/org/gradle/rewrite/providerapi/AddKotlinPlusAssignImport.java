package org.gradle.rewrite.providerapi;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.kotlin.KotlinIsoVisitor;

import static org.gradle.rewrite.providerapi.internal.PropertyTypes.HAS_MULTIPLE_VALUES_FQN;
import static org.gradle.rewrite.providerapi.internal.PropertyTypes.KOTLIN_PLUS_ASSIGN_FQN;
import static org.gradle.rewrite.providerapi.internal.PropertyTypes.LIST_PROPERTY_FQN;
import static org.gradle.rewrite.providerapi.internal.PropertyTypes.SET_PROPERTY_FQN;

/**
 * Add {@code import org.gradle.kotlin.dsl.plusAssign} to {@code .kt} files that use {@code +=} on a
 * {@code HasMultipleValues}-typed property ({@code ListProperty<T>} / {@code SetProperty<T>}).
 *
 * <p>Like {@link AddKotlinAssignImport}, {@code .kt} build-logic sources must explicitly import the
 * extension. Without it, {@code prop += value} fails to compile.
 */
public class AddKotlinPlusAssignImport extends Recipe {

    @Override
    public String getDisplayName() {
        return "Add `org.gradle.kotlin.dsl.plusAssign` import to `.kt` files that use `+=` on `ListProperty`/`SetProperty`";
    }

    @Override
    public String getDescription() {
        return "In Kotlin build-logic sources (`.kt`), `+=` on a `ListProperty<T>` or `SetProperty<T>` " +
               "requires the `org.gradle.kotlin.dsl.plusAssign` extension to be imported. This recipe " +
               "scans each `.kt` compilation unit for such patterns and adds the import when it is missing.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new KotlinIsoVisitor<ExecutionContext>() {
            @Override
            public J.AssignmentOperation visitAssignmentOperation(J.AssignmentOperation op, ExecutionContext ctx) {
                J.AssignmentOperation a = super.visitAssignmentOperation(op, ctx);
                if (a.getOperator() != J.AssignmentOperation.Type.Addition) {
                    return a;
                }
                if (isMultiValuedProperty(a.getVariable().getType())) {
                    maybeAddImport(KOTLIN_PLUS_ASSIGN_FQN, null, false);
                }
                return a;
            }

            private boolean isMultiValuedProperty(JavaType type) {
                JavaType.FullyQualified fq = type instanceof JavaType.FullyQualified
                        ? (JavaType.FullyQualified) type : null;
                if (fq == null) {
                    return false;
                }
                if (matchesAny(fq)) {
                    return true;
                }
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
                return HAS_MULTIPLE_VALUES_FQN.equals(n)
                        || LIST_PROPERTY_FQN.equals(n)
                        || SET_PROPERTY_FQN.equals(n);
            }
        };
    }
}
