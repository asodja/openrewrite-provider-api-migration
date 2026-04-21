package org.gradle.rewrite.providerapi;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;

import org.gradle.rewrite.providerapi.internal.MigratedProperties.Kind;

/**
 * Migrate {@code recv.setX(list)} to {@code recv.getX().set(list)} for properties cataloged as
 * {@code LIST_PROPERTY}.
 */
public class MigrateListPropertySetter extends Recipe {

    @Override
    public String getDisplayName() {
        return "Migrate `setX(v)` to `getX().set(v)` for `ListProperty<T>` properties";
    }

    @Override
    public String getDescription() {
        return "Rewrites `recv.setX(v)` to `recv.getX().set(v)` for properties cataloged as " +
               "migrating to `ListProperty<T>`.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new MigratePropertySetter.SetterToPropertyVisitor(null, Kind.LIST_PROPERTY, "set");
    }
}
