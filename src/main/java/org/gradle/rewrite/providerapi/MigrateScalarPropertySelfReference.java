package org.gradle.rewrite.providerapi;

import org.gradle.rewrite.providerapi.internal.GradleBuildLogic;
import org.gradle.rewrite.providerapi.internal.KotlinDslScope;
import org.gradle.rewrite.providerapi.internal.MigratedProperties;
import org.gradle.rewrite.providerapi.internal.MigratedProperties.Kind;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.kotlin.KotlinTemplate;

import java.util.List;

/**
 * Rewrite scalar-property self-reference assignments like {@code artifactId = artifactId.uppercase()}
 * to the lazy-API {@code prop.set(prop.get().m(...))} form.
 *
 * <p>Before migration, {@code artifactId} was a mutable {@code String} — this pattern read the
 * current value, transformed, and wrote back. After migration, {@code artifactId} is a
 * {@code Property<String>} and {@code .uppercase()} no longer exists on the receiver. Rewrite to:
 * <pre>
 *   artifactId.set(artifactId.get().uppercase())
 * </pre>
 * This uses only the public {@code Property} API and compiles for any {@code T} — the internal
 * {@code DefaultProperty.replace { it.map { v -> v.m(...) } }} variant looks tidier but only
 * compiles when the lambda's {@code v} has a concrete enough type for {@code v.m(...)} to
 * resolve. Scalar properties can have any {@code T}, and the recipe doesn't always know {@code T}
 * at rewrite time (rewrite-kotlin often can't attribute implicit-this receivers in
 * {@code .gradle.kts}) — the {@code get()} form dodges that problem entirely.
 *
 * <p>Detection is conservative: the LHS and RHS receiver must be textually identical, and the
 * property must be cataloged as {@link Kind#SCALAR_PROPERTY}. Java sources get the same
 * mechanical rewrite since {@code prop.set(prop.get().m(...))} is valid Java too.
 */
public class MigrateScalarPropertySelfReference extends Recipe {

    @Override
    public String getDisplayName() {
        return "Rewrite `x = x.m(...)` scalar-property self-reference via internal `replace { }`";
    }

    @Override
    public String getDescription() {
        return "Detects `prop = prop.transform(...)` assignments where `prop` is a cataloged " +
               "`SCALAR_PROPERTY`. On Kotlin the recipe emits a mechanical rewrite using " +
               "Gradle's internal `DefaultProperty.replace(Transformer)` API. On Java a TODO " +
               "advisor is attached.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return GradleBuildLogic.onlyBuildLogic(new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
                J.Assignment a = (J.Assignment) super.visitAssignment(assignment, ctx);
                Expression variable = a.getVariable();
                Expression value = a.getAssignment();
                if (!(value instanceof J.MethodInvocation)) {
                    return a;
                }
                J.MethodInvocation rhs = (J.MethodInvocation) value;
                Expression rhsSelect = rhs.getSelect();
                if (rhsSelect == null) {
                    return a;
                }
                // RHS receiver must be textually the same as LHS variable. Using print-text
                // equality rather than structural identity because rewrite-kotlin can synthesize
                // slightly different J nodes for the LHS vs. RHS occurrence (both refer to the
                // same underlying property, but the nodes aren't `==`).
                Cursor cursor = getCursor();
                if (!printEquals(variable, rhsSelect, cursor)) {
                    return a;
                }
                String propName = extractPropertyName(variable);
                if (propName == null) {
                    return a;
                }
                Kind kind = resolveKind(variable, propName, cursor);
                if (kind != Kind.SCALAR_PROPERTY) {
                    return a;
                }
                return rewriteAsSetGet(a, rhs, cursor);
            }
        });
    }

    /**
     * Pull the Java-bean property name off the LHS: a bare identifier contributes its simple name,
     * a field access contributes its terminal field name, and a zero-arg {@code getX()} invocation
     * contributes {@code x}. Returns {@code null} for shapes we don't recognize.
     */
    private static String extractPropertyName(Expression variable) {
        if (variable instanceof J.Identifier) {
            return ((J.Identifier) variable).getSimpleName();
        }
        if (variable instanceof J.FieldAccess) {
            return ((J.FieldAccess) variable).getName().getSimpleName();
        }
        if (variable instanceof J.MethodInvocation) {
            J.MethodInvocation mi = (J.MethodInvocation) variable;
            String n = mi.getSimpleName();
            if (n.startsWith("get") && n.length() > 3 && Character.isUpperCase(n.charAt(3))
                    && (mi.getArguments().isEmpty()
                        || (mi.getArguments().size() == 1 && mi.getArguments().get(0) instanceof J.Empty))) {
                return Character.toLowerCase(n.charAt(3)) + n.substring(4);
            }
        }
        return null;
    }

    /**
     * Resolve the catalog Kind for the LHS: tries the receiver's resolved type first (explicit
     * chain like {@code task.artifactId}), then the DSL-scope walker for bare identifiers, then
     * the catalog's name-only fallback. Only returns {@link Kind#SCALAR_PROPERTY} — other kinds
     * don't match the self-reference pattern and return {@code null}.
     */
    private static Kind resolveKind(Expression variable, String propName, Cursor cursor) {
        JavaType.FullyQualified receiverFq = null;
        if (variable instanceof J.FieldAccess) {
            JavaType t = ((J.FieldAccess) variable).getTarget().getType();
            if (t instanceof JavaType.FullyQualified) receiverFq = (JavaType.FullyQualified) t;
        } else if (variable instanceof J.MethodInvocation) {
            Expression sel = ((J.MethodInvocation) variable).getSelect();
            if (sel != null && sel.getType() instanceof JavaType.FullyQualified) {
                receiverFq = (JavaType.FullyQualified) sel.getType();
            }
        }
        Kind kind = receiverFq != null ? MigratedProperties.lookup(receiverFq, propName) : null;
        if (kind == null && variable instanceof J.Identifier) {
            String scope = KotlinDslScope.findEnclosingTypedScope(cursor);
            if (scope != null) {
                kind = MigratedProperties.lookupBySimpleName(scope, propName);
            }
            // No catalog-wide name-only fallback — same false-positive risk as
            // MigratePropertyMutations: a property name that's cataloged on a Gradle type
            // might collide with an unrelated field on a third-party DSL receiver, and the
            // emitted rewrite would blow up at runtime.
        }
        return kind == Kind.SCALAR_PROPERTY ? kind : null;
    }

    private static boolean printEquals(Expression a, Expression b, Cursor cursor) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.printTrimmed(cursor).equals(b.printTrimmed(cursor));
    }

    /**
     * Splice in {@code <recv>.set(<recv>.get().<method>(<args>))} via {@link KotlinTemplate}.
     * The {@code .get()} form compiles for any scalar T without needing to know its concrete
     * type at rewrite time — in contrast to the internal {@code DefaultProperty.replace { }}
     * variant where the lambda's value type has to be concrete enough for the method call to
     * resolve.
     */
    private static J rewriteAsSetGet(J.Assignment a, J.MethodInvocation rhs, Cursor cursor) {
        String receiver = a.getVariable().printTrimmed(cursor);
        String call = rhs.getSimpleName();
        String args = renderArgs(rhs, cursor);
        String snippet = receiver + ".set(" + receiver + ".get()." + call + "(" + args + "))";
        return KotlinTemplate.builder(snippet)
                .build()
                .apply(cursor, a.getCoordinates().replace());
    }

    private static String renderArgs(J.MethodInvocation m, Cursor cursor) {
        List<Expression> args = m.getArguments();
        if (args.isEmpty() || args.get(0) instanceof J.Empty) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(args.get(i).printTrimmed(cursor));
        }
        return sb.toString();
    }
}
