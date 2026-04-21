package org.gradle.rewrite.providerapi;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;

import org.gradle.rewrite.providerapi.internal.MigratedProperties.Kind;

/**
 * Migrate {@code recv.setX(map)} to {@code recv.getX().set(map)} for properties cataloged as
 * {@code MAP_PROPERTY}.
 */
public class MigrateMapPropertySetter extends Recipe {

    @Override
    public String getDisplayName() {
        return "Migrate `setX(v)` to `getX().set(v)` for `MapProperty<K, V>` properties";
    }

    @Override
    public String getDescription() {
        return "Rewrites `recv.setX(v)` to `recv.getX().set(v)` for properties cataloged as " +
               "migrating to `MapProperty<K, V>`.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new MigratePropertySetter.SetterToPropertyVisitor(null, Kind.MAP_PROPERTY, "set");
    }
}
