package org.gradle.rewrite.providerapi.internal;

import org.openrewrite.Cursor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cursor-walking heuristic for resolving the implicit receiver type of a Kotlin DSL lambda when
 * rewrite-kotlin's type attribution fails to do so — the common case inside {@code .gradle.kts}
 * files (openrewrite/rewrite#6312).
 *
 * <p>rewrite-kotlin does NOT propagate reified-generic receivers into nested lambdas. For code like:
 * <pre>
 *   tasks.register&lt;Javadoc&gt;("x") {
 *       setMaxMemory("1g")        // this.setMaxMemory — but `this` has no resolved type
 *   }
 *   tasks.withType&lt;Javadoc&gt;().configureEach {
 *       destination.readText()    // destination has no resolved type
 *   }
 * </pre>
 * the inner {@code setMaxMemory}/{@code destination} access comes back with {@code methodType=null}
 * and {@code type=null} — so catalog lookups that pivot on declaring type silently miss.
 *
 * <p>This helper walks the cursor up to find the nearest enclosing DSL invocation whose type
 * argument identifies the lambda's receiver — extracting the <em>simple</em> name (e.g.
 * {@code "Javadoc"}) from the LST because rewrite-kotlin does preserve the identifier text even
 * when its type is null. Callers then feed that simple name through
 * {@link MigratedProperties#lookupBySimpleName(String, String)}, which safely falls back to "no
 * rewrite" when the name is ambiguous across multiple cataloged types.
 */
public final class KotlinDslScope {

    /**
     * Well-known built-in Gradle task accessor names to the simple name of their task type.
     * Handles {@code tasks.javadoc { ... }} / {@code tasks.test { ... }} / etc., which use the
     * kotlin-dsl's generated typed accessors. These accessors live in per-project generated
     * source (not in the gradle-api jar), so rewrite-kotlin can't resolve their return type and
     * the outer-scope heuristic falls back to this hardcoded mapping.
     *
     * <p>Scoped deliberately to the universal Gradle plugins (base / java / java-library) — these
     * are the ones whose accessor names mean the same thing in every codebase. Project-specific
     * accessors like {@code tasks.mySpecialJar} are left unresolved on purpose.
     */
    private static final Map<String, String> BUILT_IN_TASK_ACCESSORS = new HashMap<>();
    static {
        // JavaPlugin
        BUILT_IN_TASK_ACCESSORS.put("compileJava", "JavaCompile");
        BUILT_IN_TASK_ACCESSORS.put("compileTestJava", "JavaCompile");
        BUILT_IN_TASK_ACCESSORS.put("javadoc", "Javadoc");
        BUILT_IN_TASK_ACCESSORS.put("test", "Test");
        BUILT_IN_TASK_ACCESSORS.put("jar", "Jar");
        BUILT_IN_TASK_ACCESSORS.put("processResources", "Copy");
        BUILT_IN_TASK_ACCESSORS.put("processTestResources", "Copy");
        // JavaLibraryPlugin
        BUILT_IN_TASK_ACCESSORS.put("sourcesJar", "Jar");
        BUILT_IN_TASK_ACCESSORS.put("javadocJar", "Jar");
        // BasePlugin
        BUILT_IN_TASK_ACCESSORS.put("clean", "Delete");
    }

    private KotlinDslScope() {}

    /**
     * Walk {@code cursor}'s ancestors for the innermost enclosing DSL invocation whose type argument
     * identifies the lambda receiver. Supported shapes:
     * <ul>
     *   <li>{@code tasks.withType<T> { ... }} / {@code withType<T>().configureEach { ... }}</li>
     *   <li>{@code tasks.register<T>("name") { ... }} / {@code named<T>("name") { ... }}</li>
     *   <li>{@code tasks.registering(T::class) { ... }} / {@code register(T::class, ...)}</li>
     *   <li>{@code configure<T> { ... }}</li>
     *   <li>Chained forms where the typed call is the {@code select} of a {@code .configureEach}
     *       or {@code .all}: {@code withType<T>().configureEach { ... }}</li>
     * </ul>
     * Returns the simple type name (e.g. {@code "Javadoc"}) on success, or {@code null} if no
     * typed scope is found.
     */
    public static String findEnclosingTypedScope(Cursor cursor) {
        if (cursor == null) return null;
        Cursor c = cursor.getParent();
        while (c != null) {
            if (c.getValue() instanceof J.Lambda) {
                Cursor owner = findEnclosingMethodInvocation(c);
                if (owner != null) {
                    J.MethodInvocation m = (J.MethodInvocation) owner.getValue();
                    String simple = extractTypeArgumentSimpleName(m);
                    if (simple != null) return simple;
                    // Chained: `.configureEach { }` / `.all { }` off a typed call on select.
                    if (m.getSelect() instanceof J.MethodInvocation) {
                        String fromSelect = extractTypeArgumentSimpleName((J.MethodInvocation) m.getSelect());
                        if (fromSelect != null) return fromSelect;
                    }
                    c = owner; // keep walking outward in case of nested typed scopes
                    continue;
                }
            }
            c = c.getParent();
        }
        return null;
    }

    private static Cursor findEnclosingMethodInvocation(Cursor lambdaCursor) {
        Cursor c = lambdaCursor.getParent();
        while (c != null && !(c.getValue() instanceof J.MethodInvocation)) {
            c = c.getParent();
        }
        return c;
    }

    /**
     * Pull the simple name of the first type argument from a DSL invocation. Checks both the
     * explicit {@code <T>} syntax (stored in {@link J.MethodInvocation#getTypeParameters()}) and
     * the {@code (T::class, ...)} argument form (stored as a {@link J.MemberReference} in the
     * first argument slot). Returns {@code null} if the invocation doesn't carry a type we can
     * recover.
     */
    private static String extractTypeArgumentSimpleName(J.MethodInvocation m) {
        List<Expression> typeParams = m.getTypeParameters();
        if (typeParams != null && !typeParams.isEmpty()) {
            Expression tp = typeParams.get(0);
            if (tp instanceof J.Identifier) {
                return ((J.Identifier) tp).getSimpleName();
            }
        }
        // T::class argument — used by `registering(T::class)` and the non-reified `register(T::class, ...)`.
        String name = m.getSimpleName();
        if (("registering".equals(name) || "register".equals(name)) && !m.getArguments().isEmpty()) {
            Expression arg = m.getArguments().get(0);
            if (arg instanceof J.MemberReference) {
                J.MemberReference mr = (J.MemberReference) arg;
                Expression containing = mr.getContaining();
                if (containing instanceof J.Identifier) {
                    return ((J.Identifier) containing).getSimpleName();
                }
            }
        }
        // Built-in task accessor: `tasks.javadoc { }` / `tasks.test { }` etc. The accessor's
        // return-type is lost without the generated kotlin-dsl accessor jar on classpath, so we
        // resolve via a hardcoded map of well-known built-in plugin accessor names.
        if (m.getSelect() instanceof J.Identifier
                && "tasks".equals(((J.Identifier) m.getSelect()).getSimpleName())) {
            String mapped = BUILT_IN_TASK_ACCESSORS.get(name);
            if (mapped != null) return mapped;
        }
        return null;
    }
}
