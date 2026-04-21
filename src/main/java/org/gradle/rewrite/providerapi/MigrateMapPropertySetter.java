package org.gradle.rewrite.providerapi;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;

import static org.gradle.rewrite.providerapi.internal.PropertyTypes.MAP_PROPERTY_FQN;

/**
 * Migrate {@code recv.setX(map)} where {@code recv.getX()} returns
 * {@code org.gradle.api.provider.MapProperty<K, V>} to {@code recv.getX().set(map)}.
 */
public class MigrateMapPropertySetter extends Recipe {

    @Override
    public String getDisplayName() {
        return "Migrate `setX(v)` to `getX().set(v)` for `MapProperty<K, V>` getters";
    }

    @Override
    public String getDescription() {
        return "Rewrites `recv.setX(v)` to `recv.getX().set(v)` when `recv.getX()` returns " +
               "`org.gradle.api.provider.MapProperty<K, V>`.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new MigratePropertySetter.SetterToPropertyVisitor(null, MAP_PROPERTY_FQN, "set");
    }
}
