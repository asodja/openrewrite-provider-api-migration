package org.gradle.rewrite.providerapi;

import org.gradle.rewrite.providerapi.internal.BooleanRenames;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.kotlin.KotlinIsoVisitor;

/**
 * Rename Kotlin boolean accessors for properties that migrated from {@code boolean isX()} to
 * {@code Property<Boolean> getX()}.
 *
 * <p>Kotlin synthesizes a property named {@code isX} for {@code boolean isX()} (keeping the {@code is}
 * prefix) but a property named {@code x} for {@code Property<Boolean> getX()}. So every Kotlin call site like
 * {@code t.isEnabled} must become {@code t.enabled} when the declaring Gradle type has migrated.
 *
 * <p>The set of renamed accessors is driven by {@link BooleanRenames}, which is indexed by declaring type.
 * Unknown types are left alone so identically-named accessors on unrelated classes aren't touched.
 */
public class RenameKotlinBooleanAccessors extends Recipe {

    @Override
    public String getDisplayName() {
        return "Rename Kotlin `isX` accessors that became `Property<Boolean>`";
    }

    @Override
    public String getDescription() {
        return "When a Gradle boolean property migrates from `boolean isX()` to `Property<Boolean> getX()`, " +
               "its Kotlin-visible accessor name changes from `isX` to `x`. This recipe rewrites each " +
               "known occurrence in `.kt` source files.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new KotlinIsoVisitor<ExecutionContext>() {
            @Override
            public J.FieldAccess visitFieldAccess(J.FieldAccess fa, ExecutionContext ctx) {
                J.FieldAccess f = super.visitFieldAccess(fa, ctx);
                String oldName = f.getName().getSimpleName();
                if (!BooleanRenames.isTrackedName(oldName)) {
                    return f;
                }
                JavaType.FullyQualified targetType = asFullyQualified(f.getTarget().getType());
                if (targetType == null) {
                    return f;
                }
                String newName = resolveRenameOnHierarchy(targetType, oldName);
                if (newName == null) {
                    return f;
                }
                return f.withName(f.getName().withSimpleName(newName));
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, ExecutionContext ctx) {
                J.Identifier id = super.visitIdentifier(identifier, ctx);
                String oldName = id.getSimpleName();
                if (!BooleanRenames.isTrackedName(oldName)) {
                    return id;
                }
                JavaType.Variable fieldType = id.getFieldType();
                if (fieldType == null) {
                    return id;
                }
                JavaType.FullyQualified owner = asFullyQualified(fieldType.getOwner());
                if (owner == null) {
                    return id;
                }
                String newName = resolveRenameOnHierarchy(owner, oldName);
                if (newName == null) {
                    return id;
                }
                return id.withSimpleName(newName);
            }
        };
    }

    private static String resolveRenameOnHierarchy(JavaType.FullyQualified type, String oldName) {
        String direct = BooleanRenames.renamedAccessor(type.getFullyQualifiedName(), oldName);
        if (direct != null) {
            return direct;
        }
        JavaType.FullyQualified cursor = type.getSupertype();
        while (cursor != null && cursor != type) {
            String n = BooleanRenames.renamedAccessor(cursor.getFullyQualifiedName(), oldName);
            if (n != null) {
                return n;
            }
            cursor = cursor.getSupertype();
        }
        for (JavaType.FullyQualified iface : type.getInterfaces()) {
            String n = BooleanRenames.renamedAccessor(iface.getFullyQualifiedName(), oldName);
            if (n != null) {
                return n;
            }
        }
        return null;
    }

    private static JavaType.FullyQualified asFullyQualified(JavaType t) {
        return t instanceof JavaType.FullyQualified ? (JavaType.FullyQualified) t : null;
    }
}
