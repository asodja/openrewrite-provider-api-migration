package org.gradle.rewrite.providerapi;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;

import org.gradle.rewrite.providerapi.internal.MigratedProperties.Kind;

/**
 * Migrate {@code recv.setX(v)} to {@code recv.getX().setFrom(v)} for properties cataloged as
 * {@code CONFIGURABLE_FILE_COLLECTION}.
 */
public class MigrateConfigurableFileCollectionSetter extends Recipe {

    @Override
    public String getDisplayName() {
        return "Migrate `setX(v)` to `getX().setFrom(v)` for `ConfigurableFileCollection` properties";
    }

    @Override
    public String getDescription() {
        return "Rewrites `recv.setX(v)` to `recv.getX().setFrom(v)` for properties cataloged as " +
               "migrating to `ConfigurableFileCollection`. Setters on `ConfigurableFileCollection` " +
               "properties have been removed; `setFrom` is the replacement.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new MigratePropertySetter.SetterToPropertyVisitor(null, Kind.CONFIGURABLE_FILE_COLLECTION, "setFrom");
    }
}
