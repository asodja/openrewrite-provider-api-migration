package org.gradle.rewrite.providerapi.internal;

/**
 * Fully-qualified type names used across the Provider API migration recipes.
 *
 * <p>Centralized so every recipe agrees on the canonical type names and so the list can be
 * grepped when Gradle renames a type.
 */
public final class PropertyTypes {

    public static final String PROPERTY_FQN = "org.gradle.api.provider.Property";
    public static final String PROVIDER_FQN = "org.gradle.api.provider.Provider";
    public static final String LIST_PROPERTY_FQN = "org.gradle.api.provider.ListProperty";
    public static final String SET_PROPERTY_FQN = "org.gradle.api.provider.SetProperty";
    public static final String MAP_PROPERTY_FQN = "org.gradle.api.provider.MapProperty";
    public static final String HAS_MULTIPLE_VALUES_FQN = "org.gradle.api.provider.HasMultipleValues";
    public static final String HAS_CONFIGURABLE_VALUE_FQN = "org.gradle.api.provider.HasConfigurableValue";

    public static final String CONFIGURABLE_FILE_COLLECTION_FQN = "org.gradle.api.file.ConfigurableFileCollection";
    public static final String FILE_COLLECTION_FQN = "org.gradle.api.file.FileCollection";
    public static final String DIRECTORY_PROPERTY_FQN = "org.gradle.api.file.DirectoryProperty";
    public static final String REGULAR_FILE_PROPERTY_FQN = "org.gradle.api.file.RegularFileProperty";
    public static final String DIRECTORY_FQN = "org.gradle.api.file.Directory";
    public static final String REGULAR_FILE_FQN = "org.gradle.api.file.RegularFile";

    public static final String FILE_FQN = "java.io.File";

    public static final String KOTLIN_ASSIGN_FQN = "org.gradle.kotlin.dsl.assign";
    public static final String KOTLIN_PLUS_ASSIGN_FQN = "org.gradle.kotlin.dsl.plusAssign";

    private PropertyTypes() {}
}
