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
import org.openrewrite.java.JavaTemplate;
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

    /**
     * FQN of the internal impl type used in the Kotlin cast. Uses concrete {@code Any?} type
     * arguments rather than wildcards (star-projections). With wildcards, the result of
     * {@code m.toMutableMap().apply { remove(...) }} is {@code MutableMap<Any, Any>} and doesn't
     * match the transformer's expected {@code Map<out CapturedType, out CapturedType>} — Kotlin
     * rejects it with "Return type mismatch". {@code <Any?, Any?>} makes the whole chain concrete
     * (at the cost of one unchecked-cast warning from the outer {@code as}, which is fine —
     * generic type arguments are erased at runtime).
     */
    private static final Map<Kind, String> INTERNAL_TYPE = new EnumMap<>(Kind.class);
    static {
        INTERNAL_TYPE.put(Kind.MAP_PROPERTY,
                "org.gradle.api.internal.provider.DefaultMapProperty<Any?, Any?>");
        INTERNAL_TYPE.put(Kind.LIST_PROPERTY,
                "org.gradle.api.internal.provider.DefaultListProperty<Any?>");
        INTERNAL_TYPE.put(Kind.SET_PROPERTY,
                "org.gradle.api.internal.provider.DefaultSetProperty<Any?>");
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

    /** JDK concrete implementation type used for the copy in the Java rewrite. */
    private static final Map<Kind, String> JAVA_MUTABLE_IMPL = new EnumMap<>(Kind.class);
    static {
        JAVA_MUTABLE_IMPL.put(Kind.MAP_PROPERTY, "java.util.HashMap");
        JAVA_MUTABLE_IMPL.put(Kind.LIST_PROPERTY, "java.util.ArrayList");
        JAVA_MUTABLE_IMPL.put(Kind.SET_PROPERTY, "java.util.LinkedHashSet");
    }

    /** Collection-interface type (with {@code Object} type args) used for the local var in the Java rewrite. */
    private static final Map<Kind, String> JAVA_ERASED_INTERFACE = new EnumMap<>(Kind.class);
    static {
        JAVA_ERASED_INTERFACE.put(Kind.MAP_PROPERTY, "java.util.Map<Object, Object>");
        JAVA_ERASED_INTERFACE.put(Kind.LIST_PROPERTY, "java.util.List<Object>");
        JAVA_ERASED_INTERFACE.put(Kind.SET_PROPERTY, "java.util.Set<Object>");
    }

    /** Java raw-type cast target (no generic args) — avoids {@code unchecked} on the cast itself. */
    private static final Map<Kind, String> JAVA_INTERNAL_TYPE_RAW = new EnumMap<>(Kind.class);
    static {
        JAVA_INTERNAL_TYPE_RAW.put(Kind.MAP_PROPERTY,
                "org.gradle.api.internal.provider.DefaultMapProperty");
        JAVA_INTERNAL_TYPE_RAW.put(Kind.LIST_PROPERTY,
                "org.gradle.api.internal.provider.DefaultListProperty");
        JAVA_INTERNAL_TYPE_RAW.put(Kind.SET_PROPERTY,
                "org.gradle.api.internal.provider.DefaultSetProperty");
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
                Match match = resolveReceiverKind(select, methodName);
                if (match == null) {
                    return m;
                }
                Set<String> unsupportedForKind = UNSUPPORTED.get(match.kind);
                if (unsupportedForKind == null || !unsupportedForKind.contains(methodName)) {
                    return m;
                }
                SourceFile sourceFile = getCursor().firstEnclosing(SourceFile.class);
                if (match.confident) {
                    if (GradleBuildLogic.isKotlin(sourceFile)) {
                        return rewriteAsKotlinReplaceBlock(m, match.kind, getCursor());
                    }
                    // Java mechanical rewrite. Shape differs from Kotlin's `as X` inline cast —
                    // Java needs an explicit `((X) recv).replace(transformer)` with a
                    // copy-mutate-return lambda body instead of Kotlin's `.apply { }` trick.
                    return rewriteAsJavaReplaceBlock(m, match.kind, getCursor());
                }
                // Non-confident match — leave a TODO advisor so the user can review case-by-case.
                // The receiver might be an unrelated third-party property that collides on name
                // (e.g. Shadow plugin's `excludes: Set<String>` vs catalog's
                // `JacocoTaskExtension.excludes: ListProperty`).
                return Advisor.addTodo(m, adviceMessage(m, match, getCursor()));
            }

            /**
             * Resolve which {@link Kind} {@code select} represents, and how confidently.
             * Tiered:
             * <ol>
             *   <li><b>Confident</b> — receiver's declaring type resolves from the LST
             *       ({@code J.FieldAccess} with typed target, bean-style getter with typed
             *       select) and the catalog matches, OR bare identifier inside a typed DSL
             *       scope ({@code withType<T>} etc.) where the catalog matches on the scope's
             *       simple name.</li>
             *   <li><b>Candidate</b> — bare identifier where no enclosing scope resolves, but
             *       the property name matches a cataloged entry somewhere. Could be a real
             *       match (untyped {@code testTask.configure { environment.remove(...) }}) or
             *       a name collision with an unrelated third-party field (Shadow plugin's
             *       {@code excludes: Set<String>}). Caller should emit a TODO advisor rather
             *       than a mechanical rewrite.</li>
             * </ol>
             * Returns {@code null} when no Kind can be attributed or the method isn't
             * unsupported for that Kind.
             */
            private Match resolveReceiverKind(Expression select, String methodName) {
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
                // Tier 1: explicit receiver type + catalog match → confident.
                Kind kind = null;
                if (receiverFq != null) {
                    kind = MigratedProperties.lookup(receiverFq, propName);
                    if (kindMatchesUnsupportedMethod(kind, methodName)) {
                        return new Match(kind, true);
                    }
                }
                // Tier 2: bare identifier + enclosing typed DSL scope → confident.
                if (select instanceof J.Identifier) {
                    String scope = KotlinDslScope.findEnclosingTypedScope(getCursor());
                    if (scope != null) {
                        kind = MigratedProperties.lookupBySimpleName(scope, propName);
                        if (kindMatchesUnsupportedMethod(kind, methodName)) {
                            return new Match(kind, true);
                        }
                    }
                    // Tier 3: bare identifier + catalog name-only agreement → CANDIDATE.
                    // Might be the real thing (`environment.remove(...)` inside an untyped
                    // `testTask.configure { }`) or a name collision with a third-party field
                    // (Shadow plugin's `excludes: Set<String>`). Never auto-rewrite — the call
                    // site asks the user to review via TODO advisor.
                    kind = MigratedProperties.lookupByNameOnly(propName);
                    if (kindMatchesUnsupportedMethod(kind, methodName)) {
                        return new Match(kind, false);
                    }
                }
                return null;
            }

            private boolean kindMatchesUnsupportedMethod(Kind kind, String methodName) {
                if (kind == null) return false;
                Set<String> unsupported = UNSUPPORTED.get(kind);
                return unsupported != null && unsupported.contains(methodName);
            }
        });
    }

    /** Receiver-kind resolution result: {@link Kind} plus a confidence flag. */
    private static final class Match {
        final Kind kind;
        final boolean confident;
        Match(Kind kind, boolean confident) {
            this.kind = kind;
            this.confident = confident;
        }
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
     *
     * <p>A compact {@code TODO:} block comment is prepended so each site is easy to find later and
     * so the user gets explicit guidance on the unchecked-cast warning (Kotlin can't prove the
     * runtime-safe erasure cast {@code Foo<String, String> as Foo<Any?, Any?>} — {@code @Suppress}
     * at the expression level would confuse rewrite-kotlin's printer, so we recommend the
     * file-level {@code @file:Suppress("UNCHECKED_CAST")} opt-out in the comment instead).
     */
    private static J rewriteAsKotlinReplaceBlock(J.MethodInvocation m, Kind kind, Cursor cursor) {
        String receiver = renderReceiver(m.getSelect(), cursor);
        String call = m.getSimpleName();
        String args = renderArgs(m, cursor);
        String var = LAMBDA_VAR.get(kind);
        String toMut = TO_MUTABLE.get(kind);
        String snippet =
                "(" + receiver + " as " + INTERNAL_TYPE.get(kind) + ").replace { " +
                "it.map { " + var + " -> " + var + "." + toMut + "().apply { " +
                call + "(" + args + ") } } }";
        J rewritten = KotlinTemplate.builder(snippet)
                .build()
                .apply(cursor, m.getCoordinates().replace());
        return Advisor.addTodo(rewritten, kotlinReviewMessage(kind, receiver, call, args));
    }

    /**
     * Emit the Java equivalent:
     * <pre>
     *   ((DefaultMapProperty) environment).replace(
     *       provider -> provider.map(m -> {
     *           Map&lt;Object, Object&gt; updated = new HashMap&lt;&gt;(m);
     *           updated.remove("X");
     *           return updated;
     *       })
     *   );
     * </pre>
     * <p>Shape decisions:
     * <ul>
     *   <li>Raw-type cast ({@code (DefaultMapProperty)}) — avoids the {@code unchecked}
     *       warning the parameterized cast would produce, at the cost of a single
     *       {@code rawtypes} warning which {@code @SuppressWarnings("rawtypes")} silences
     *       file-wide. (We flag this in the TODO.)</li>
     *   <li>Fully qualified {@code java.util.HashMap} / {@code java.util.Map&lt;Object, Object&gt;}
     *       — avoids import management; the call site may already have a shadowed {@code Map}.</li>
     *   <li>{@code Object, Object} type args — we don't know {@code K, V} statically; the copy
     *       constructor on {@code HashMap<Object, Object>} accepts {@code Map<? extends K, ?
     *       extends V>}. Same for List/Set.</li>
     * </ul>
     */
    private static J rewriteAsJavaReplaceBlock(J.MethodInvocation m, Kind kind, Cursor cursor) {
        String receiver = renderReceiver(m.getSelect(), cursor);
        String call = m.getSimpleName();
        String args = renderArgs(m, cursor);
        String rawCastType = JAVA_INTERNAL_TYPE_RAW.get(kind);
        String collectionIface = JAVA_ERASED_INTERFACE.get(kind);
        String mutableImpl = JAVA_MUTABLE_IMPL.get(kind);
        String var = LAMBDA_VAR.get(kind);
        String snippet =
                "((" + rawCastType + ") " + receiver + ").replace(__provider -> __provider.map(" +
                var + " -> {\n" +
                "    " + collectionIface + " __updated = new " + mutableImpl + "<>(" + var + ");\n" +
                "    __updated." + call + "(" + args + ");\n" +
                "    return __updated;\n" +
                "}));";
        J rewritten = JavaTemplate.builder(snippet)
                .build()
                .apply(cursor, m.getCoordinates().replace());
        return Advisor.addTodo(rewritten, javaReviewMessage(kind, receiver, call, args));
    }

    /** Short TODO prepended above the Kotlin internal-API rewrite so it's easy to grep for later. */
    private static String kotlinReviewMessage(Kind kind, String receiver, String call, String args) {
        String toMut = TO_MUTABLE.get(kind);
        return "Uses Gradle internal API (" + internalShortName(kind) + "). Fragile —\n" +
               "consider the public copy-mutate-set form instead:\n" +
               "    val updated = " + receiver + ".get()." + toMut + "()\n" +
               "    updated." + call + "(" + args + ")\n" +
               "    " + receiver + ".set(updated)\n" +
               "\n" +
               "The cast below triggers UNCHECKED_CAST and\n" +
               "UPPER_BOUND_VIOLATED_IN_TYPE_OPERATOR_OR_PARAMETER_BOUNDS_WARNING warnings;\n" +
               "to silence them, add at the top of this script:\n" +
               "    @file:Suppress(\"UNCHECKED_CAST\", \"UPPER_BOUND_VIOLATED_IN_TYPE_OPERATOR_OR_PARAMETER_BOUNDS_WARNING\")";
    }

    /** Same shape as {@link #kotlinReviewMessage} but adapted to Java syntax. */
    private static String javaReviewMessage(Kind kind, String receiver, String call, String args) {
        String collectionIface = JAVA_ERASED_INTERFACE.get(kind);
        String mutableImpl = JAVA_MUTABLE_IMPL.get(kind);
        return "Uses Gradle internal API (" + internalShortName(kind) + "). Fragile —\n" +
               "consider the public copy-mutate-set form instead:\n" +
               "    " + collectionIface + " updated = new " + mutableImpl + "<>(" + receiver + ".get());\n" +
               "    updated." + call + "(" + args + ");\n" +
               "    " + receiver + ".set(updated);\n" +
               "\n" +
               "The raw-type cast below triggers a `rawtypes` compiler warning and the generic\n" +
               "call triggers `unchecked` / `unchecked_cast` warnings. To silence them, annotate\n" +
               "the enclosing method or class with:\n" +
               "    @SuppressWarnings({\"rawtypes\", \"unchecked\"})";
    }

    private static String internalShortName(Kind kind) {
        switch (kind) {
            case MAP_PROPERTY:  return "DefaultMapProperty";
            case LIST_PROPERTY: return "DefaultListProperty";
            case SET_PROPERTY:  return "DefaultSetProperty";
            default:            return "internal provider impl";
        }
    }

    private static String adviceMessage(J.MethodInvocation m, Match match, Cursor cursor) {
        String receiver = renderReceiver(m.getSelect(), cursor);
        String call = m.getSimpleName();
        String args = renderArgs(m, cursor);
        String toMut = TO_MUTABLE.get(match.kind);
        String internal = INTERNAL_TYPE.get(match.kind);
        String var = LAMBDA_VAR.get(match.kind);
        String noun;
        switch (match.kind) {
            case MAP_PROPERTY: noun = "MapProperty"; break;
            case LIST_PROPERTY: noun = "ListProperty"; break;
            case SET_PROPERTY: noun = "SetProperty"; break;
            default: noun = "Property";
        }
        StringBuilder sb = new StringBuilder();
        if (!match.confident) {
            sb.append("Possible ").append(noun).append(" mutation — receiver type could not be")
              .append(" verified from the LST. `").append(receiver).append("` matches a cataloged")
              .append(" property name, but the enclosing scope isn't typed ")
              .append("(`withType<T>` / `register<T>` etc.), so this MIGHT be an unrelated ")
              .append("third-party field with the same name (e.g. Shadow plugin's `excludes: ")
              .append("Set<String>`). Review before applying either replacement below.\n\n");
        } else {
            sb.append(noun).append(" does not support `").append(call).append("(...)`.\n\n");
        }
        sb.append("Copy-mutate-set replacement:\n")
          .append("    val updated = ").append(receiver).append(".get().").append(toMut).append("()\n")
          .append("    updated.").append(call).append("(").append(args).append(")\n")
          .append("    ").append(receiver).append(".set(updated)\n")
          .append("\n")
          .append("Internal-API alternative (fragile, not recommended):\n")
          .append("    (").append(receiver).append(" as ").append(internal).append(").replace {\n")
          .append("        it.map { ").append(var).append(" -> ").append(var).append(".")
          .append(toMut).append("().apply { ").append(call).append("(").append(args)
          .append(") } }\n")
          .append("    }");
        return sb.toString();
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
