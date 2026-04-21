package org.gradle.rewrite.providerapi.internal;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static table of Kotlin-visible boolean accessor renames introduced by the Provider API migration.
 *
 * <p>When a Java boolean getter (e.g. {@code boolean isEnabled()}) is replaced by a lazy getter
 * {@code Property<Boolean> getEnabled()}, Kotlin's synthesized property accessor changes from {@code isEnabled}
 * to {@code enabled} — because Kotlin only prefixes {@code is} for primitive {@code boolean} getters, not for
 * {@code Property<Boolean>}.
 *
 * <p>Each entry maps a declaring type to its set of renamed boolean properties, keyed by the <em>old</em>
 * accessor name ({@code isXxx}). The new name is derived by stripping the {@code is} prefix and lowercasing
 * the next character (so {@code isFailOnNoMatchingTests} → {@code failOnNoMatchingTests}).
 *
 * <p>Grouped by declaring type so we can safely narrow matches using the receiver's attributed type — we do
 * not rename identically-named accessors on types outside this catalog.
 */
public final class BooleanRenames {

    /** Map of declaring type FQN → set of old Kotlin accessor names ({@code isXxx}). */
    private static final Map<String, Set<String>> TABLE;

    static {
        Map<String, Set<String>> t = new HashMap<>();
        put(t, "org.gradle.api.tasks.testing.Test", "isEnabled", "isFailOnNoMatchingTests", "isIgnoreFailures");
        put(t, "org.gradle.process.ExecSpec", "isIgnoreExitValue");
        put(t, "org.gradle.api.tasks.Exec", "isIgnoreExitValue");
        put(t, "org.gradle.api.tasks.JavaExec", "isIgnoreExitValue");
        put(t, "org.gradle.api.tasks.bundling.AbstractArchiveTask",
                "isPreserveFileTimestamps", "isReproducibleFileOrder", "isZip64");
        put(t, "org.gradle.api.tasks.bundling.Jar",
                "isPreserveFileTimestamps", "isReproducibleFileOrder", "isZip64");
        put(t, "org.gradle.api.tasks.bundling.Zip",
                "isPreserveFileTimestamps", "isReproducibleFileOrder", "isZip64");
        put(t, "org.gradle.api.tasks.bundling.Tar", "isPreserveFileTimestamps", "isReproducibleFileOrder");
        put(t, "org.gradle.api.tasks.compile.AbstractCompile", "isIncremental");
        put(t, "org.gradle.api.tasks.compile.JavaCompile", "isIncremental", "isFork");
        put(t, "org.gradle.api.tasks.compile.BaseForkOptions", "isFork");
        put(t, "org.gradle.api.tasks.javadoc.Javadoc", "isFailOnError");
        put(t, "org.gradle.api.Task", "isEnabled");
        put(t, "org.gradle.api.publish.maven.tasks.PublishToMavenRepository", "isPush");
        TABLE = Collections.unmodifiableMap(t);
    }

    private static void put(Map<String, Set<String>> t, String fqn, String... names) {
        t.put(fqn, new HashSet<>(Arrays.asList(names)));
    }

    /**
     * Return the renamed accessor, or {@code null} if the pair is not in the catalog. Walks the supertype /
     * interface chain of {@code declaringType} so a subtype inherits its parent's entries.
     */
    public static String renamedAccessor(String declaringType, String oldName) {
        if (declaringType == null || oldName == null) {
            return null;
        }
        if (!oldName.startsWith("is") || oldName.length() <= 2 || !Character.isUpperCase(oldName.charAt(2))) {
            return null;
        }
        Set<String> entries = TABLE.get(declaringType);
        if (entries != null && entries.contains(oldName)) {
            return stripIsPrefix(oldName);
        }
        return null;
    }

    /**
     * True if {@code oldName} is tracked as a renamed accessor on <em>any</em> type. Used as a cheap
     * pre-filter before resolving types.
     */
    public static boolean isTrackedName(String oldName) {
        if (oldName == null) return false;
        for (Set<String> entries : TABLE.values()) {
            if (entries.contains(oldName)) return true;
        }
        return false;
    }

    public static List<String> allOldNames() {
        Set<String> all = new HashSet<>();
        for (Set<String> entries : TABLE.values()) {
            all.addAll(entries);
        }
        return new java.util.ArrayList<>(all);
    }

    private static String stripIsPrefix(String name) {
        // "isFailOnNoMatchingTests" -> "failOnNoMatchingTests"
        return Character.toLowerCase(name.charAt(2)) + name.substring(3);
    }

    private BooleanRenames() {}
}
