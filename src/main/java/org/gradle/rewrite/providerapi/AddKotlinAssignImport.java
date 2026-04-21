package org.gradle.rewrite.providerapi;

import org.gradle.rewrite.providerapi.internal.MigratedProperties;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.kotlin.KotlinIsoVisitor;

import static org.gradle.rewrite.providerapi.internal.PropertyTypes.KOTLIN_ASSIGN_FQN;
import static org.gradle.rewrite.providerapi.internal.PropertyTypes.PROPERTY_FQN;

/**
 * Add {@code import org.gradle.kotlin.dsl.assign} to {@code .kt} files that use {@code =} to assign to a
 * {@code Property<T>}.
 *
 * <p>Gradle's Kotlin DSL provides a top-level {@code assign} infix extension that makes
 * {@code prop = value} shorthand for {@code prop.set(value)} — but only when the extension is imported.
 * {@code .gradle.kts} scripts auto-import it; plain {@code .kt} build-logic sources do not. This recipe
 * detects Property-typed assignments in {@code .kt} files and adds the missing import.
 *
 * <p><b>Default behavior — enabled by default.</b> The assumption is that build-logic using property
 * assignment syntax already has the Kotlin assignment compiler plugin applied (via {@code kotlin-dsl}
 * or explicitly). Users whose build-logic does NOT have the assignment plugin should skip this recipe
 * and instead rely on the sibling rewrite that emits {@code getX().set(v)} — since the import alone
 * isn't enough to make {@code prop = value} compile without the plugin.
 */
public class AddKotlinAssignImport extends Recipe {

    @Override
    public String getDisplayName() {
        return "Add `org.gradle.kotlin.dsl.assign` import to `.kt` files that assign to `Property<T>`";
    }

    @Override
    public String getDescription() {
        return "In Kotlin build-logic sources (`.kt`), assigning to a `Property<T>` with `=` requires the " +
               "`org.gradle.kotlin.dsl.assign` extension to be imported. This recipe scans each `.kt` " +
               "compilation unit for such assignments and adds the import when it is missing.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new KotlinIsoVisitor<ExecutionContext>() {
            @Override
            public J.Assignment visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
                J.Assignment a = super.visitAssignment(assignment, ctx);
                if (shouldAddImport(a.getVariable())) {
                    maybeAddImport(KOTLIN_ASSIGN_FQN, null, false);
                }
                return a;
            }

            /**
             * Two paths to trigger the import:
             * <ol>
             *   <li><b>Type-driven</b> — the LHS already resolves to a {@code Property<T>} subtype
             *       (works on the new / hybrid Gradle classpath).</li>
             *   <li><b>Catalog-driven</b> — the LHS name matches a cataloged migrated property and,
             *       for field-access form, the receiver's type is a cataloged declaring type. Works on
             *       the OLD Gradle classpath, which is what users run the recipe against.</li>
             * </ol>
             * The import is harmless when added unnecessarily, so we err on the side of adding it.
             */
            private boolean shouldAddImport(Expression lhs) {
                if (isPropertyType(lhs.getType())) {
                    return true;
                }
                if (lhs instanceof J.FieldAccess) {
                    J.FieldAccess fa = (J.FieldAccess) lhs;
                    String propName = fa.getName().getSimpleName();
                    JavaType receiverType = fa.getTarget().getType();
                    if (receiverType instanceof JavaType.FullyQualified
                            && MigratedProperties.lookup((JavaType.FullyQualified) receiverType, propName) != null) {
                        return true;
                    }
                    // Fall through to name-only check below.
                    return MigratedProperties.isKnownPropertyName(propName);
                }
                if (lhs instanceof J.Identifier) {
                    // Implicit-`this` form inside a Kotlin DSL block, e.g. `isIgnoreExitValue = true`
                    // where the enclosing javaexec { ... } has ExecSpec as receiver. We can't resolve
                    // the receiver from the identifier alone, so fall back to name-only catalog check.
                    return MigratedProperties.isKnownPropertyName(((J.Identifier) lhs).getSimpleName());
                }
                return false;
            }

            private boolean isPropertyType(JavaType type) {
                JavaType.FullyQualified fq = type instanceof JavaType.FullyQualified
                        ? (JavaType.FullyQualified) type : null;
                if (fq == null) {
                    return false;
                }
                if (PROPERTY_FQN.equals(fq.getFullyQualifiedName())) {
                    return true;
                }
                JavaType.FullyQualified cursor = fq.getSupertype();
                while (cursor != null && cursor != fq) {
                    if (PROPERTY_FQN.equals(cursor.getFullyQualifiedName())) {
                        return true;
                    }
                    cursor = cursor.getSupertype();
                }
                for (JavaType.FullyQualified iface : fq.getInterfaces()) {
                    if (PROPERTY_FQN.equals(iface.getFullyQualifiedName())) {
                        return true;
                    }
                    for (JavaType.FullyQualified ifaceSuper : iface.getInterfaces()) {
                        if (PROPERTY_FQN.equals(ifaceSuper.getFullyQualifiedName())) {
                            return true;
                        }
                    }
                }
                return false;
            }
        };
    }
}
