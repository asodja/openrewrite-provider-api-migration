package org.gradle.rewrite.providerapi;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.marker.SearchResult;

import static org.gradle.rewrite.providerapi.internal.PropertyTypes.CONFIGURABLE_FILE_COLLECTION_FQN;
import static org.gradle.rewrite.providerapi.internal.PropertyTypes.FILE_COLLECTION_FQN;

/**
 * Flag {@code x.setFrom(..., x, ...)} / {@code x.from(..., x, ...)} patterns where a
 * {@code ConfigurableFileCollection} is reset to a value that reads from itself.
 *
 * <p>These patterns used to work with the old eager {@code setX(files(x, extra))} form but are unsafe
 * on {@code ConfigurableFileCollection} because evaluation is deferred — the read-then-write cycle can
 * deadlock or produce empty collections. The migration report flags these as requiring human review
 * (Tier 3, advisor-only): either switch to append-safe {@code .from(extra)} if intent was additive, or
 * capture the current value into a local before re-assigning.
 *
 * <p>This recipe attaches a {@link SearchResult} marker to each offending call site. Users see the
 * markers as inline TODO comments in the diff and decide case-by-case.
 */
public class DetectSelfReferencingFileCollection extends Recipe {

    @Override
    public String getDisplayName() {
        return "Flag self-referencing `ConfigurableFileCollection` assignments";
    }

    @Override
    public String getDescription() {
        return "Attaches a `SearchResult` marker to each `x.setFrom(...)` / `x.from(...)` call where the " +
               "receiver `x` also appears in the arguments. Such patterns became runtime-unsafe on " +
               "`ConfigurableFileCollection` under the Provider API and need human refactoring.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if (m.getSelect() == null) {
                    return m;
                }
                String name = m.getSimpleName();
                if (!"setFrom".equals(name) && !"from".equals(name)) {
                    return m;
                }
                if (!isFileCollectionReceiver(m.getSelect().getType())) {
                    return m;
                }
                String receiverKey = simpleName(m.getSelect());
                if (receiverKey == null) {
                    return m;
                }
                for (Expression arg : m.getArguments()) {
                    if (referencesName(arg, receiverKey)) {
                        return SearchResult.found(m,
                                "Self-referencing ConfigurableFileCollection: `" + receiverKey + "." + name +
                                "(...)` reads `" + receiverKey + "` on the RHS. Capture to a local or use " +
                                "`.from(extra)` if intent was additive.");
                    }
                }
                return m;
            }

            private boolean isFileCollectionReceiver(JavaType type) {
                JavaType.FullyQualified fq = type instanceof JavaType.FullyQualified
                        ? (JavaType.FullyQualified) type : null;
                if (fq == null) return false;
                if (CONFIGURABLE_FILE_COLLECTION_FQN.equals(fq.getFullyQualifiedName())) return true;
                if (FILE_COLLECTION_FQN.equals(fq.getFullyQualifiedName())) return true;
                JavaType.FullyQualified cursor = fq.getSupertype();
                while (cursor != null && cursor != fq) {
                    if (CONFIGURABLE_FILE_COLLECTION_FQN.equals(cursor.getFullyQualifiedName())) return true;
                    cursor = cursor.getSupertype();
                }
                for (JavaType.FullyQualified iface : fq.getInterfaces()) {
                    if (CONFIGURABLE_FILE_COLLECTION_FQN.equals(iface.getFullyQualifiedName())) return true;
                }
                return false;
            }

            private String simpleName(Expression e) {
                if (e instanceof J.Identifier) {
                    return ((J.Identifier) e).getSimpleName();
                }
                if (e instanceof J.FieldAccess) {
                    return ((J.FieldAccess) e).getName().getSimpleName();
                }
                if (e instanceof J.MethodInvocation) {
                    J.MethodInvocation mi = (J.MethodInvocation) e;
                    String name = mi.getSimpleName();
                    // Normalize getX() to property-style x so "getClasspath()" matches reads of
                    // "getClasspath()" on the other side.
                    if (name.startsWith("get") && name.length() > 3 && Character.isUpperCase(name.charAt(3))) {
                        return Character.toLowerCase(name.charAt(3)) + name.substring(4);
                    }
                    return name;
                }
                return null;
            }

            private boolean referencesName(Expression expr, String name) {
                if (expr == null) return false;
                class Finder extends JavaIsoVisitor<java.util.concurrent.atomic.AtomicBoolean> {
                    @Override
                    public J.Identifier visitIdentifier(J.Identifier id, java.util.concurrent.atomic.AtomicBoolean found) {
                        if (id.getSimpleName().equals(name)) {
                            found.set(true);
                        }
                        return id;
                    }

                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, java.util.concurrent.atomic.AtomicBoolean found) {
                        String mName = mi.getSimpleName();
                        if (mName.startsWith("get") && mName.length() > 3 && Character.isUpperCase(mName.charAt(3))) {
                            String prop = Character.toLowerCase(mName.charAt(3)) + mName.substring(4);
                            if (prop.equals(name)) {
                                found.set(true);
                            }
                        }
                        return super.visitMethodInvocation(mi, found);
                    }
                }
                java.util.concurrent.atomic.AtomicBoolean found = new java.util.concurrent.atomic.AtomicBoolean();
                new Finder().visit(expr, found);
                return found.get();
            }
        };
    }
}
