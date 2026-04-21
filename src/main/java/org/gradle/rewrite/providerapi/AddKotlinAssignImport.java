package org.gradle.rewrite.providerapi;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
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
                if (isPropertyType(a.getVariable().getType())) {
                    maybeAddImport(KOTLIN_ASSIGN_FQN, null, false);
                }
                return a;
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
