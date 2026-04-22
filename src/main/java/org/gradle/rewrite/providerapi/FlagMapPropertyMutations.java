package org.gradle.rewrite.providerapi;

import org.gradle.rewrite.providerapi.internal.MigratedProperties;
import org.gradle.rewrite.providerapi.internal.MigratedProperties.Kind;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.marker.SearchResult;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.gradle.rewrite.providerapi.internal.GradleBuildLogic;
/**
 * Flag call sites that mutate a cataloged {@link Kind#MAP_PROPERTY} via methods that don't exist on
 * {@code MapProperty} ({@code remove}, {@code filterKeys}, {@code computeIfAbsent}, etc.).
 *
 * <p>No mechanical rewrite — these patterns require a "copy-mutate-set" refactor the user must write
 * (see MIGRATION-ANALYSIS.md §4(g)). This recipe attaches a {@link SearchResult} marker so each site
 * shows up as a TODO in the rewrite diff.
 */
public class FlagMapPropertyMutations extends Recipe {

    private static final Set<String> UNSUPPORTED = new HashSet<>(Arrays.asList(
            "remove", "filterKeys", "filterValues", "computeIfAbsent", "compute", "merge"
    ));

    @Override
    public String getDisplayName() {
        return "Flag unsupported `MapProperty` mutations (remove/filterKeys/compute)";
    }

    @Override
    public String getDescription() {
        return "`MapProperty` does not expose `remove`, `filterKeys`, or in-place `compute*` methods. " +
               "Mutating sites that depended on these require a copy-mutate-set refactor. This recipe " +
               "marks each site with a `SearchResult` TODO — no mechanical rewrite.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return GradleBuildLogic.onlyBuildLogic(new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if (!UNSUPPORTED.contains(m.getSimpleName())) {
                    return m;
                }
                Expression select = m.getSelect();
                if (select == null) {
                    return m;
                }
                if (!isMapPropertyReceiver(select)) {
                    return m;
                }
                String receiverLabel = renderReceiver(select);
                String call = m.getSimpleName();
                String argsRender = renderArgs(m);
                String message =
                        "MapProperty does not support `" + call + "(...)`. Replace with the " +
                        "copy-mutate-set pattern: `val updated = " + receiverLabel + ".get()" +
                        ".toMutableMap(); updated." + call + "(" + argsRender + "); " +
                        receiverLabel + ".set(updated)`. Internal-API alternative (fragile): " +
                        "`(" + receiverLabel + " as DefaultMapProperty<*, *>).replace { " +
                        "it.map { m -> m.toMutableMap().apply { " + call + "(" + argsRender + ") } } }`.";
                return SearchResult.found(m, message);
            }

            private String renderReceiver(Expression select) {
                if (select instanceof J.FieldAccess) {
                    J.FieldAccess fa = (J.FieldAccess) select;
                    return fa.getTarget() + "." + fa.getName().getSimpleName();
                }
                if (select instanceof J.Identifier) {
                    return ((J.Identifier) select).getSimpleName();
                }
                return "prop";
            }

            private String renderArgs(J.MethodInvocation m) {
                java.util.List<Expression> args = m.getArguments();
                if (args.isEmpty() || args.get(0) instanceof J.Empty) {
                    return "";
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(args.get(i).toString().trim());
                }
                return sb.toString();
            }

            /**
             * True when the receiver is either (a) a known MAP_PROPERTY via the catalog, or (b) a field
             * access whose simple name is any cataloged MAP_PROPERTY name (fallback for contexts where
             * the receiver's type can't be fully resolved, such as Kotlin DSL blocks).
             */
            private boolean isMapPropertyReceiver(Expression select) {
                String propName = null;
                JavaType.FullyQualified receiverFq = null;
                if (select instanceof J.FieldAccess) {
                    J.FieldAccess fa = (J.FieldAccess) select;
                    propName = fa.getName().getSimpleName();
                    receiverFq = fa.getTarget().getType() instanceof JavaType.FullyQualified
                            ? (JavaType.FullyQualified) fa.getTarget().getType() : null;
                } else if (select instanceof J.Identifier) {
                    propName = ((J.Identifier) select).getSimpleName();
                }
                if (propName == null) {
                    return false;
                }
                if (receiverFq != null) {
                    return MigratedProperties.lookup(receiverFq, propName) == Kind.MAP_PROPERTY;
                }
                // Implicit-this form inside a Kotlin DSL block — check catalog by name alone, restricted
                // to MAP_PROPERTY entries.
                return isKnownMapPropertyName(propName);
            }

            private boolean isKnownMapPropertyName(String name) {
                // Check each cataloged entry for the property name with MAP_PROPERTY kind.
                for (String declaringFqn : new String[]{
                        "org.gradle.api.tasks.testing.Test",
                        "org.gradle.process.ExecSpec",
                        "org.gradle.api.tasks.Exec",
                        "org.gradle.api.tasks.JavaExec",
                        "org.gradle.process.JavaExecSpec"
                }) {
                    if (MigratedProperties.lookupExact(declaringFqn, name) == Kind.MAP_PROPERTY) {
                        return true;
                    }
                }
                return false;
            }
        });
    }
}
