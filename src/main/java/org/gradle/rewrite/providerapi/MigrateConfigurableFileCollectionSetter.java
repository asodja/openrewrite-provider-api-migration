package org.gradle.rewrite.providerapi;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;

import static org.gradle.rewrite.providerapi.internal.PropertyTypes.CONFIGURABLE_FILE_COLLECTION_FQN;

/**
 * Migrate {@code recv.setX(v)} where {@code recv.getX()} returns
 * {@code org.gradle.api.file.ConfigurableFileCollection} to {@code recv.getX().setFrom(v)}.
 */
public class MigrateConfigurableFileCollectionSetter extends Recipe {

    @Override
    public String getDisplayName() {
        return "Migrate `setX(v)` to `getX().setFrom(v)` for `ConfigurableFileCollection` getters";
    }

    @Override
    public String getDescription() {
        return "Rewrites `recv.setX(v)` to `recv.getX().setFrom(v)` when `recv.getX()` returns " +
               "`org.gradle.api.file.ConfigurableFileCollection`. Setters on `ConfigurableFileCollection` " +
               "properties have been removed; `setFrom` is the replacement.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new MigratePropertySetter.SetterToPropertyVisitor(null, CONFIGURABLE_FILE_COLLECTION_FQN, "setFrom");
    }
}
