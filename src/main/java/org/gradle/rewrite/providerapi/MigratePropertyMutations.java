package org.gradle.rewrite.providerapi;

import org.gradle.rewrite.providerapi.internal.Advisor;
import org.gradle.rewrite.providerapi.internal.GradleBuildLogic;
import org.gradle.rewrite.providerapi.internal.KotlinDslScope;
import org.gradle.rewrite.providerapi.internal.MigratedProperties;
import org.gradle.rewrite.providerapi.internal.MigratedProperties.Kind;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.kotlin.KotlinTemplate;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrite {@code remove}, {@code clear}, {@code compute}, and other mutators that don't exist on
 * the lazy {@code MapProperty} / {@code ListProperty} / {@code SetProperty} API to use Gradle's
 * internal {@code .replace(Transformer)} method on the concrete {@code DefaultXxxProperty} impl.
 *
 * <p>Kotlin (.gradle.kts / .kt) gets the mechanical rewrite:
 * <pre>
 *   environment.remove("X")
 *     → (environment as org.gradle.api.internal.provider.DefaultMapProperty&lt;*, *&gt;).replace {
 *           it.map { m -> m.toMutableMap().apply { remove("X") } }
 *       }
 * </pre>
 * <p>Java gets a {@code SearchResult}/TODO marker — the copy-mutate-set replacement is readable
 * enough on its own that an auto-generated cast-chain is noise.
 *
 * <p>The internal-API cast is fragile (Gradle can rearrange {@code AbstractProperty}'s class
 * hierarchy on a major-version boundary), but it's the only way to mutate in place without the
 * copy-mutate-set dance — many real build scripts prefer the terser form.
 */
public class MigratePropertyMutations extends Recipe {

    /** Per-{@link Kind}, the collection-mutation method names that exist on the eager type but not the lazy one. */
    private static final Map<Kind, Set<String>> UNSUPPORTED = new EnumMap<>(Kind.class);
    static {
        UNSUPPORTED.put(Kind.MAP_PROPERTY, new HashSet<>(Arrays.asList(
                "remove", "compute", "computeIfAbsent", "filterKeys", "filterValues",
                "merge", "putIfAbsent", "replaceAll", "clear")));
        UNSUPPORTED.put(Kind.LIST_PROPERTY, new HashSet<>(Arrays.asList(
                "remove", "clear", "removeAll", "removeIf", "retainAll", "replaceAll", "sort")));
        UNSUPPORTED.put(Kind.SET_PROPERTY, new HashSet<>(Arrays.asList(
                "remove", "clear", "removeAll", "removeIf", "retainAll")));
    }

    /** FQN of the internal impl type used in the Kotlin cast. */
    private static final Map<Kind, String> INTERNAL_TYPE = new EnumMap<>(Kind.class);
    static {
        INTERNAL_TYPE.put(Kind.MAP_PROPERTY,
                "org.gradle.api.internal.provider.DefaultMapProperty<*, *>");
        INTERNAL_TYPE.put(Kind.LIST_PROPERTY,
                "org.gradle.api.internal.provider.DefaultListProperty<*>");
        INTERNAL_TYPE.put(Kind.SET_PROPERTY,
                "org.gradle.api.internal.provider.DefaultSetProperty<*>");
    }

    /** Kotlin stdlib copy-and-cast name per Kind. */
    private static final Map<Kind, String> TO_MUTABLE = new EnumMap<>(Kind.class);
    static {
        TO_MUTABLE.put(Kind.MAP_PROPERTY, "toMutableMap");
        TO_MUTABLE.put(Kind.LIST_PROPERTY, "toMutableList");
        TO_MUTABLE.put(Kind.SET_PROPERTY, "toMutableSet");
    }

    /** Single-letter lambda parameter per Kind — keeps the emitted snippet readable. */
    private static final Map<Kind, String> LAMBDA_VAR = new EnumMap<>(Kind.class);
    static {
        LAMBDA_VAR.put(Kind.MAP_PROPERTY, "m");
        LAMBDA_VAR.put(Kind.LIST_PROPERTY, "l");
        LAMBDA_VAR.put(Kind.SET_PROPERTY, "s");
    }

    @Override
    public String getDisplayName() {
        return "Rewrite unsupported collection-property mutations via internal `.replace { }`";
    }

    @Override
    public String getDescription() {
        return "`MapProperty` / `ListProperty` / `SetProperty` don't expose `remove`, `clear`, " +
               "`compute`, `filterKeys`, etc. from the eager collection API. For Kotlin build-logic " +
               "this recipe emits a mechanical rewrite that delegates to `DefaultXxxProperty.replace " +
               "{ }` (Gradle internal API). For Java sources a `SearchResult` TODO is attached.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return GradleBuildLogic.onlyBuildLogic(new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                String methodName = m.getSimpleName();
                Expression select = m.getSelect();
                if (select == null) {
                    return m;
                }
                // Early-out if the method name doesn't match any Kind's unsupported set. Cheap
                // check to avoid resolving types for every invocation in the tree.
                if (!isUnsupportedForAnyKind(methodName)) {
                    return m;
                }
                Kind kind = resolveReceiverKind(select, methodName);
                if (kind == null) {
                    return m;
                }
                Set<String> unsupportedForKind = UNSUPPORTED.get(kind);
                if (unsupportedForKind == null || !unsupportedForKind.contains(methodName)) {
                    return m;
                }
                SourceFile sourceFile = getCursor().firstEnclosing(SourceFile.class);
                if (GradleBuildLogic.isKotlin(sourceFile)) {
                    return rewriteAsReplaceBlock(m, kind, getCursor());
                }
                return Advisor.addTodo(m, adviceMessage(m, kind, getCursor()));
            }

            /**
             * Resolve which {@link Kind} {@code select} represents, if any. Tries, in order:
             * <ol>
             *   <li>{@code J.FieldAccess} (explicit {@code task.env}) — uses target's type + field name</li>
             *   <li>{@code J.MethodInvocation} that's a bean-style getter ({@code task.getEnv()}) —
             *       uses select's type + bean property name</li>
             *   <li>{@code J.Identifier} (bare {@code env}) — falls back to the enclosing DSL scope
             *       via {@link KotlinDslScope}, then catalog lookup by simple name</li>
             * </ol>
             * Restricted to Kinds that have {@code methodName} in their unsupported set, so we
             * don't cross-contaminate between (say) List and Map calls that share an identifier
             * name like {@code remove}.
             */
            private Kind resolveReceiverKind(Expression select, String methodName) {
                String propName = null;
                JavaType.FullyQualified receiverFq = null;
                if (select instanceof J.FieldAccess) {
                    J.FieldAccess fa = (J.FieldAccess) select;
                    propName = fa.getName().getSimpleName();
                    receiverFq = fa.getTarget().getType() instanceof JavaType.FullyQualified
                            ? (JavaType.FullyQualified) fa.getTarget().getType() : null;
                } else if (select instanceof J.MethodInvocation) {
                    J.MethodInvocation mi = (J.MethodInvocation) select;
                    String name = mi.getSimpleName();
                    if (name.startsWith("get") && name.length() > 3
                            && Character.isUpperCase(name.charAt(3))
                            && (mi.getArguments().isEmpty()
                                || (mi.getArguments().size() == 1 && mi.getArguments().get(0) instanceof J.Empty))) {
                        propName = Character.toLowerCase(name.charAt(3)) + name.substring(4);
                        Expression inner = mi.getSelect();
                        receiverFq = inner != null && inner.getType() instanceof JavaType.FullyQualified
                                ? (JavaType.FullyQualified) inner.getType() : null;
                    }
                } else if (select instanceof J.Identifier) {
                    propName = ((J.Identifier) select).getSimpleName();
                }
                if (propName == null) {
                    return null;
                }
                Kind kind = null;
                if (receiverFq != null) {
                    kind = MigratedProperties.lookup(receiverFq, propName);
                }
                if (kind == null && select instanceof J.Identifier) {
                    // Bare implicit-this inside a typed DSL lambda — consult the enclosing scope.
                    // Same gating as the setter recipes: only for bare identifiers, never for
                    // explicit receiver chains whose type is merely unresolved.
                    String scope = KotlinDslScope.findEnclosingTypedScope(getCursor());
                    if (scope != null) {
                        kind = MigratedProperties.lookupBySimpleName(scope, propName);
                    }
                    if (kind == null) {
                        // No typed scope (or the property isn't on that type). Last-chance:
                        // consult the catalog by name alone — safer here than for setters because
                        // {@link MigratedProperties#lookupByNameOnly} only returns a Kind when
                        // every cataloged entry with that property name agrees. `environment` is
                        // MAP_PROPERTY on Test/Exec/JavaExec/ExecSpec/JavaExecSpec/ProcessForkOptions
                        // — all agree, so `environment.remove(...)` inside an untyped lambda like
                        // `testTask.configure { }` is still safely recognized. The collection-
                        // mutation method filter (remove/clear/compute/etc.) narrows the match
                        // further, so a non-Property class with an unrelated `remove(String)`
                        // method won't be hit unless its property name also collides with one in
                        // the catalog.
                        kind = MigratedProperties.lookupByNameOnly(propName);
                    }
                }
                // Only return the Kind if the method is actually unsupported for it. This
                // prevents a false positive like `listOfStringsReceiver.remove("x")` wrongly
                // being treated as a MAP_PROPERTY site because a catalog simple-name collision
                // would put it in both Kind.MAP_PROPERTY and Kind.LIST_PROPERTY.
                if (kind != null) {
                    Set<String> unsupportedForKind = UNSUPPORTED.get(kind);
                    if (unsupportedForKind == null || !unsupportedForKind.contains(methodName)) {
                        return null;
                    }
                }
                return kind;
            }
        });
    }

    private static boolean isUnsupportedForAnyKind(String methodName) {
        for (Set<String> set : UNSUPPORTED.values()) {
            if (set.contains(methodName)) return true;
        }
        return false;
    }

    /**
     * Build the Kotlin rewrite snippet and splice it in via {@link KotlinTemplate}. Parses the
     * snippet against Kotlin's grammar and produces a real {@code J} subtree (no {@code J.Unknown}),
     * at the cost of dropping type attribution on the synthesized identifiers — a parse cycle on
     * the migrated Gradle will re-attribute them.
     */
    private static J rewriteAsReplaceBlock(J.MethodInvocation m, Kind kind, Cursor cursor) {
        String receiver = renderReceiver(m.getSelect(), cursor);
        String call = m.getSimpleName();
        String args = renderArgs(m, cursor);
        String var = LAMBDA_VAR.get(kind);
        String toMut = TO_MUTABLE.get(kind);
        String snippet =
                "(" + receiver + " as " + INTERNAL_TYPE.get(kind) + ").replace { " +
                "it.map { " + var + " -> " + var + "." + toMut + "().apply { " +
                call + "(" + args + ") } } }";
        return KotlinTemplate.builder(snippet)
                .build()
                .apply(cursor, m.getCoordinates().replace());
    }

    private static String adviceMessage(J.MethodInvocation m, Kind kind, Cursor cursor) {
        String receiver = renderReceiver(m.getSelect(), cursor);
        String call = m.getSimpleName();
        String args = renderArgs(m, cursor);
        String toMut = TO_MUTABLE.get(kind);
        String internal = INTERNAL_TYPE.get(kind);
        String var = LAMBDA_VAR.get(kind);
        String noun;
        switch (kind) {
            case MAP_PROPERTY: noun = "MapProperty"; break;
            case LIST_PROPERTY: noun = "ListProperty"; break;
            case SET_PROPERTY: noun = "SetProperty"; break;
            default: noun = "Property";
        }
        return noun + " does not support `" + call + "(...)`.\n" +
               "\n" +
               "Copy-mutate-set replacement:\n" +
               "    val updated = " + receiver + ".get()." + toMut + "()\n" +
               "    updated." + call + "(" + args + ")\n" +
               "    " + receiver + ".set(updated)\n" +
               "\n" +
               "Internal-API alternative (fragile, not recommended):\n" +
               "    (" + receiver + " as " + internal + ").replace {\n" +
               "        it.map { " + var + " -> " + var + "." + toMut + "().apply { " +
               call + "(" + args + ") } }\n" +
               "    }";
    }

    private static String renderReceiver(Expression select, Cursor cursor) {
        if (select == null) return "prop";
        return select.printTrimmed(cursor);
    }

    private static String renderArgs(J.MethodInvocation m, Cursor cursor) {
        List<Expression> args = m.getArguments();
        if (args.isEmpty() || args.get(0) instanceof J.Empty) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(args.get(i).printTrimmed(cursor));
        }
        return sb.toString();
    }
}
