package org.gradle.rewrite.providerapi.internal;

import org.openrewrite.java.tree.JavaType;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Source of truth for "this property has migrated to the Provider API, and to which kind of lazy type."
 *
 * <p>Recipes consult this catalog <em>instead of</em> the user's current classpath when deciding whether
 * to rewrite a setter/getter call. The classpath answers the identity question ("what type is this
 * receiver?"); this catalog answers the migration question ("has that property been migrated, and to
 * what kind?"). Without this, the setter recipes would need the user to be on the migrated Gradle
 * distribution before the recipes could fire — which breaks the "run rewrite on old Gradle, then bump
 * the wrapper" workflow that every other OpenRewrite Gradle-upgrade recipe relies on.
 *
 * <p>Seeded from Section 4 of MIGRATION-ANALYSIS.md. Entries should be added when a new property is
 * confirmed to have migrated in the prototype distribution.
 */
public final class MigratedProperties {

    /**
     * What kind of lazy type a migrated property now has on its getter. Determines the shape of the
     * rewrite (e.g. {@code setX(v)} → {@code getX().set(v)} for scalar, but {@code getX().setFrom(v)}
     * for {@code ConfigurableFileCollection}).
     */
    public enum Kind {
        SCALAR_PROPERTY,                 // Property<T>
        LIST_PROPERTY,                   // ListProperty<T>
        SET_PROPERTY,                    // SetProperty<T>
        MAP_PROPERTY,                    // MapProperty<K, V>
        CONFIGURABLE_FILE_COLLECTION,    // ConfigurableFileCollection (read-only + setFrom)
        DIRECTORY_PROPERTY,              // DirectoryProperty
        REGULAR_FILE_PROPERTY            // RegularFileProperty
    }

    /** Outer key: declaring type FQN. Inner key: Java-bean property name (no get/set/is prefix). */
    private static final Map<String, Map<String, Kind>> TABLE;

    static {
        Map<String, Map<String, Kind>> t = new HashMap<>();

        // Seed from the auto-generated catalog (scans of Gradle's @ReplacesEagerProperty annotations).
        // The generator runs against the gradle10/provider-api-migration branch; regenerate via
        // tools/extract_catalog.py | tools/catalog_to_java.py.
        MigratedPropertiesCatalog.populate(new MigratedPropertiesCatalog.CatalogSink() {
            @Override public void put(String declaringType, Map<String, Kind> entries) {
                MigratedProperties.put(t, declaringType, entries);
            }
            @Override public Map<String, Kind> scalar(String... names) { return MigratedProperties.scalar(names); }
            @Override public Map<String, Kind> listLike(String... names) { return MigratedProperties.listLike(names); }
            @Override public Map<String, Kind> setLike(String... names) { return MigratedProperties.withKind(Kind.SET_PROPERTY, names); }
            @Override public Map<String, Kind> mapLike(String... names) { return MigratedProperties.mapLike(names); }
            @Override public Map<String, Kind> configurableFileCollection(String... names) { return MigratedProperties.configurableFileCollection(names); }
            @Override public Map<String, Kind> directory(String... names) { return MigratedProperties.directory(names); }
            @Override public Map<String, Kind> regularFile(String... names) { return MigratedProperties.regularFile(names); }
        });

        // -------- Hand-curated additions below. These supplement the auto-generated catalog for:
        //  1. Properties not yet annotated with @ReplacesEagerProperty in the source
        //  2. Boolean renames Kotlin-DSL-specific entries that aren't part of the auto-scan
        //  3. Aliases for types that have been refactored between releases
        // --------

        // org.gradle.api.tasks.testing.Test
        put(t, "org.gradle.api.tasks.testing.Test", scalar(
                "maxParallelForks",
                "failOnNoMatchingTests",
                "ignoreFailures",
                "maxMemory",
                "enabled",
                "forkEvery",
                "debug"
        ));
        put(t, "org.gradle.api.tasks.testing.Test", listLike(
                "jvmArgs"
        ));
        put(t, "org.gradle.api.tasks.testing.Test", mapLike(
                "systemProperties",
                "environment"   // inherited from ProcessForkOptions
        ));
        put(t, "org.gradle.api.tasks.testing.Test", configurableFileCollection(
                "classpath",
                "testClassesDirs"
        ));
        put(t, "org.gradle.api.tasks.testing.Test", directory(
                "workingDir"
        ));

        // Process execution — ExecSpec / Exec / JavaExec share ignoreExitValue etc.
        for (String receiver : Arrays.asList(
                "org.gradle.process.ExecSpec",
                "org.gradle.api.tasks.Exec",
                "org.gradle.api.tasks.JavaExec")) {
            put(t, receiver, scalar(
                    "ignoreExitValue",
                    "standardOutput",
                    "errorOutput",
                    "standardInput"
            ));
            put(t, receiver, listLike(
                    "args"
            ));
            put(t, receiver, mapLike(
                    "environment"
            ));
            put(t, receiver, directory("workingDir"));
        }

        // JavaExec-specific
        put(t, "org.gradle.api.tasks.JavaExec", scalar(
                "main",
                "mainClass",
                "maxHeapSize",
                "minHeapSize",
                "enableAssertions",
                "executable",
                "debug"
        ));
        // ExecSpec / Exec share executable + debug too
        for (String receiver : Arrays.asList(
                "org.gradle.process.ExecSpec",
                "org.gradle.api.tasks.Exec")) {
            put(t, receiver, scalar("executable"));
        }
        put(t, "org.gradle.process.JavaExecSpec", scalar(
                "main", "mainClass", "maxHeapSize", "minHeapSize", "enableAssertions",
                "executable", "debug", "standardOutput", "errorOutput", "standardInput",
                "ignoreExitValue"
        ));
        put(t, "org.gradle.process.JavaExecSpec", listLike("jvmArgs", "args"));
        put(t, "org.gradle.process.JavaExecSpec", configurableFileCollection("classpath"));
        put(t, "org.gradle.process.JavaExecSpec", mapLike("systemProperties", "environment"));
        put(t, "org.gradle.api.tasks.JavaExec", listLike(
                "jvmArgs"
        ));
        put(t, "org.gradle.api.tasks.JavaExec", configurableFileCollection(
                "classpath"
        ));
        put(t, "org.gradle.api.tasks.JavaExec", mapLike(
                "systemProperties"
        ));

        // Compile tasks
        put(t, "org.gradle.api.tasks.compile.CompileOptions", scalar(
                "encoding",
                "fork",
                "incremental",
                "deprecation",
                "verbose",
                "warnings",
                "failOnError"
        ));
        put(t, "org.gradle.api.tasks.compile.CompileOptions", listLike(
                "compilerArgs"
        ));
        put(t, "org.gradle.api.tasks.compile.JavaCompile", scalar(
                "sourceCompatibility",
                "targetCompatibility"
        ));
        put(t, "org.gradle.api.tasks.compile.AbstractCompile", scalar(
                "sourceCompatibility",
                "targetCompatibility"
        ));
        put(t, "org.gradle.api.tasks.compile.AbstractCompile", configurableFileCollection(
                "classpath"
        ));

        // Archive tasks
        for (String receiver : Arrays.asList(
                "org.gradle.api.tasks.bundling.AbstractArchiveTask",
                "org.gradle.api.tasks.bundling.Jar",
                "org.gradle.api.tasks.bundling.Zip",
                "org.gradle.api.tasks.bundling.Tar",
                "org.gradle.api.tasks.bundling.War")) {
            put(t, receiver, scalar(
                    "preserveFileTimestamps",
                    "reproducibleFileOrder",
                    "zip64",
                    "includeEmptyDirs"
            ));
        }
        put(t, "org.gradle.api.tasks.bundling.Tar", scalar("compression"));

        // Javadoc
        put(t, "org.gradle.api.tasks.javadoc.Javadoc", scalar(
                "failOnError",
                "title",
                "maxMemory"
        ));
        put(t, "org.gradle.api.tasks.javadoc.Javadoc", regularFile(
                "destinationDir"
        ));

        // SourceTask
        put(t, "org.gradle.api.tasks.SourceTask", configurableFileCollection(
                "source"
        ));

        // Delete
        put(t, "org.gradle.api.tasks.Delete", configurableFileCollection(
                "delete",                // renamed to targetFiles — handled by MigrateDeleteTaskToTargetFiles
                "targetFiles"
        ));

        // Publishing
        put(t, "org.gradle.api.publish.maven.tasks.PublishToMavenRepository", scalar(
                "push"
        ));

        // Code quality extensions
        put(t, "org.gradle.api.plugins.quality.CheckstyleExtension", scalar("toolVersion"));
        put(t, "org.gradle.api.plugins.quality.Checkstyle", scalar("toolVersion"));
        put(t, "org.gradle.api.plugins.quality.PmdExtension", scalar("toolVersion"));
        put(t, "org.gradle.api.plugins.quality.CodeNarcExtension", scalar("toolVersion"));
        put(t, "org.gradle.api.plugins.quality.JDependExtension", scalar("toolVersion"));
        put(t, "org.gradle.testing.jacoco.plugins.JacocoPluginExtension", scalar("toolVersion"));
        put(t, "org.gradle.api.plugins.quality.CodeQualityExtension", scalar("toolVersion"));

        // Freeze
        TABLE = Collections.unmodifiableMap(t);
    }

    /**
     * Look up whether {@code propertyName} on {@code declaring} (or any of its supertypes / interfaces)
     * has been migrated, and if so to which kind. Returns {@code null} for properties not in the catalog.
     */
    public static Kind lookup(JavaType.FullyQualified declaring, String propertyName) {
        if (declaring == null || propertyName == null) {
            return null;
        }
        Kind direct = lookupExact(declaring.getFullyQualifiedName(), propertyName);
        if (direct != null) {
            return direct;
        }
        JavaType.FullyQualified cursor = declaring.getSupertype();
        while (cursor != null && cursor != declaring) {
            Kind k = lookupExact(cursor.getFullyQualifiedName(), propertyName);
            if (k != null) return k;
            cursor = cursor.getSupertype();
        }
        for (JavaType.FullyQualified iface : declaring.getInterfaces()) {
            Kind k = lookupExact(iface.getFullyQualifiedName(), propertyName);
            if (k != null) return k;
            for (JavaType.FullyQualified ifaceSuper : iface.getInterfaces()) {
                Kind k2 = lookupExact(ifaceSuper.getFullyQualifiedName(), propertyName);
                if (k2 != null) return k2;
            }
        }
        return null;
    }

    /** True if {@code kind} is one of the scalar-or-file kinds that should get {@code .get()} inserted
     * before method calls on the value type. */
    public static boolean isScalarLike(Kind kind) {
        return kind == Kind.SCALAR_PROPERTY
                || kind == Kind.REGULAR_FILE_PROPERTY
                || kind == Kind.DIRECTORY_PROPERTY;
    }

    /** Exact-type lookup, no hierarchy walk. Used by callers that already walked the hierarchy. */
    public static Kind lookupExact(String declaringTypeFqn, String propertyName) {
        Map<String, Kind> entries = TABLE.get(declaringTypeFqn);
        return entries == null ? null : entries.get(propertyName);
    }

    /**
     * True if ANY cataloged type has a property with this name. Used as a fallback when the recipe
     * can't resolve the enclosing receiver's type (e.g. implicit {@code this} inside a Kotlin DSL
     * block). Over-broad on purpose — callers that use this fallback should be making
     * conservatively-safe edits (like adding an import), not reshaping code.
     */
    public static boolean isKnownPropertyName(String propertyName) {
        if (propertyName == null) return false;
        for (Map<String, Kind> entries : TABLE.values()) {
            if (entries.containsKey(propertyName)) return true;
        }
        return false;
    }

    /**
     * Look up the {@link Kind} for a property when we know the declaring type only by its
     * <em>simple</em> name — e.g. {@code "Javadoc"} extracted from a DSL scope like
     * {@code tasks.withType<Javadoc> { ... }}. Scans the catalog for every FQN whose last
     * segment matches {@code simpleName}; returns a non-null {@link Kind} only if every such
     * entry agrees on the same kind. Disagreement (two types share a simple name but one has
     * the property as {@code LIST_PROPERTY} and the other as {@code SET_PROPERTY}) returns
     * {@code null} so the caller doesn't make a wrong-kind rewrite.
     *
     * <p>Pairs with the cursor-walk heuristic in {@link KotlinDslScope} for Kotlin {@code .gradle.kts}
     * files where rewrite-kotlin can't attribute the implicit receiver type.
     */
    public static Kind lookupBySimpleName(String simpleName, String propertyName) {
        if (simpleName == null || propertyName == null) {
            return null;
        }
        Kind agreed = null;
        for (Map.Entry<String, Map<String, Kind>> entry : TABLE.entrySet()) {
            String fqn = entry.getKey();
            int idx = fqn.lastIndexOf('.');
            String simple = idx < 0 ? fqn : fqn.substring(idx + 1);
            if (!simple.equals(simpleName)) {
                continue;
            }
            Kind k = entry.getValue().get(propertyName);
            if (k == null) {
                continue;
            }
            if (agreed == null) {
                agreed = k;
            } else if (agreed != k) {
                return null; // simple-name collision with differing kinds — bail
            }
        }
        return agreed;
    }

    /**
     * Name-only fallback that returns a {@link Kind} only if <em>every</em> cataloged entry for
     * {@code propertyName} agrees on the same kind. Used when the declaring type can't be resolved
     * on the classpath — most commonly for implicit-{@code this} calls inside Kotlin DSL blocks like
     * {@code tasks.javadoc { setMaxMemory(...) }} or {@code doLast { ... }}. Ambiguous names (where
     * different cataloged types disagree on the kind) return {@code null} — the caller then plays it
     * safe and leaves the code alone.
     */
    public static Kind lookupByNameOnly(String propertyName) {
        if (propertyName == null) {
            return null;
        }
        Kind agreed = null;
        for (Map<String, Kind> entries : TABLE.values()) {
            Kind k = entries.get(propertyName);
            if (k == null) continue;
            if (agreed == null) {
                agreed = k;
            } else if (agreed != k) {
                return null; // ambiguous — different types use different kinds
            }
        }
        return agreed;
    }

    /**
     * Convert a setter method name like {@code setMaxParallelForks} to its property name
     * {@code maxParallelForks}. Returns {@code null} if the name isn't a valid setter shape.
     */
    public static String propertyNameFromSetter(String setterName) {
        if (setterName == null || !setterName.startsWith("set") || setterName.length() <= 3
                || !Character.isUpperCase(setterName.charAt(3))) {
            return null;
        }
        return Character.toLowerCase(setterName.charAt(3)) + setterName.substring(4);
    }

    private static Map<String, Kind> scalar(String... names) {
        return withKind(Kind.SCALAR_PROPERTY, names);
    }

    private static Map<String, Kind> listLike(String... names) {
        return withKind(Kind.LIST_PROPERTY, names);
    }

    private static Map<String, Kind> mapLike(String... names) {
        return withKind(Kind.MAP_PROPERTY, names);
    }

    private static Map<String, Kind> configurableFileCollection(String... names) {
        return withKind(Kind.CONFIGURABLE_FILE_COLLECTION, names);
    }

    private static Map<String, Kind> directory(String... names) {
        return withKind(Kind.DIRECTORY_PROPERTY, names);
    }

    private static Map<String, Kind> regularFile(String... names) {
        return withKind(Kind.REGULAR_FILE_PROPERTY, names);
    }

    private static Map<String, Kind> withKind(Kind kind, String... names) {
        Map<String, Kind> m = new HashMap<>(names.length);
        for (String n : names) {
            m.put(n, kind);
        }
        return m;
    }

    /** Merge entries for an existing declaring-type key rather than overwriting it. */
    private static void put(Map<String, Map<String, Kind>> t, String fqn, Map<String, Kind> entries) {
        t.computeIfAbsent(fqn, k -> new HashMap<>()).putAll(entries);
    }

    private MigratedProperties() {}
}
