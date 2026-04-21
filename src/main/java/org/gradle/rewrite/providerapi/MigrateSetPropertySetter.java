package org.gradle.rewrite.providerapi;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;

import static org.gradle.rewrite.providerapi.internal.PropertyTypes.SET_PROPERTY_FQN;

/**
 * Migrate {@code recv.setX(set)} where {@code recv.getX()} returns
 * {@code org.gradle.api.provider.SetProperty<T>} to {@code recv.getX().set(set)}.
 */
public class MigrateSetPropertySetter extends Recipe {

    @Override
    public String getDisplayName() {
        return "Migrate `setX(v)` to `getX().set(v)` for `SetProperty<T>` getters";
    }

    @Override
    public String getDescription() {
        return "Rewrites `recv.setX(v)` to `recv.getX().set(v)` when `recv.getX()` returns " +
               "`org.gradle.api.provider.SetProperty<T>`.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new MigratePropertySetter.SetterToPropertyVisitor(null, SET_PROPERTY_FQN, "set");
    }
}
