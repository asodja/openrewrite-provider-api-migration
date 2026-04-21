package org.gradle.rewrite.providerapi;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import java.util.Collections;
import java.util.List;

import static org.gradle.rewrite.providerapi.internal.PropertyTypes.PROPERTY_FQN;

/**
 * Migrate {@code recv.setX(v)} where {@code recv.getX()} returns {@code org.gradle.api.provider.Property<T>}
 * to {@code recv.getX().set(v)}.
 *
 * <p>Matches on the declared return type of the corresponding getter, so recipe #1 does not overlap with
 * recipes that handle {@code ListProperty}, {@code MapProperty}, {@code ConfigurableFileCollection}, etc.
 */
public class MigratePropertySetter extends Recipe {

    @Option(displayName = "Setter method pattern",
            description = "Optional method matcher to restrict which setter signatures are rewritten. " +
                          "By default, any setX(value) whose corresponding getX() returns Property<T> is rewritten.",
            example = "org.gradle.api.tasks.testing.Test setMaxParallelForks(int)",
            required = false)
    private final String setterPattern;

    public MigratePropertySetter() {
        this(null);
    }

    public MigratePropertySetter(String setterPattern) {
        this.setterPattern = setterPattern;
    }

    @Override
    public String getDisplayName() {
        return "Migrate `setX(v)` to `getX().set(v)` for `Property<T>` getters";
    }

    @Override
    public String getDescription() {
        return "Rewrites `recv.setX(v)` to `recv.getX().set(v)` when `recv.getX()` returns " +
               "`org.gradle.api.provider.Property<T>`. Sibling recipes handle `ListProperty`, `MapProperty`, " +
               "`SetProperty`, `ConfigurableFileCollection`, `DirectoryProperty`, and `RegularFileProperty`.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        MethodMatcher userMatcher = setterPattern == null ? null : new MethodMatcher(setterPattern, true);
        return new SetterToPropertyVisitor(userMatcher, PROPERTY_FQN, "set");
    }

    /**
     * Generic visitor for rewriting {@code recv.setX(v)} into {@code recv.getX().<targetMethod>(v)} when the
     * corresponding getter returns a specific property type. Shared by sibling recipes targeting
     * {@code ListProperty}, {@code MapProperty}, etc.
     */
    static final class SetterToPropertyVisitor extends JavaIsoVisitor<ExecutionContext> {
        private final MethodMatcher userMatcher;
        private final String targetPropertyFqn;
        private final String targetMethodName;

        SetterToPropertyVisitor(MethodMatcher userMatcher, String targetPropertyFqn, String targetMethodName) {
            this.userMatcher = userMatcher;
            this.targetPropertyFqn = targetPropertyFqn;
            this.targetMethodName = targetMethodName;
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation m = super.visitMethodInvocation(method, ctx);

            if (userMatcher != null && !userMatcher.matches(m)) {
                return m;
            }

            if (m.getSelect() == null) {
                return m;
            }

            JavaType.Method setterType = m.getMethodType();
            if (setterType == null) {
                return m;
            }

            String name = setterType.getName();
            if (!name.startsWith("set") || name.length() <= 3 || !Character.isUpperCase(name.charAt(3))) {
                return m;
            }
            if (setterType.getParameterTypes().size() != 1) {
                return m;
            }

            List<Expression> args = m.getArguments();
            if (args.size() != 1 || args.get(0) instanceof J.Empty) {
                return m;
            }

            String getterName = "get" + name.substring(3);
            JavaType.Method getterType = findNoArgMethod(setterType.getDeclaringType(), getterName);
            if (getterType == null) {
                return m;
            }

            JavaType.FullyQualified returnFq = asFullyQualified(getterType.getReturnType());
            if (returnFq == null || !targetPropertyFqn.equals(returnFq.getFullyQualifiedName())) {
                return m;
            }

            JavaType.Method targetMethodType = findTargetMethod(returnFq, targetMethodName);
            if (targetMethodType == null) {
                return m;
            }

            Expression select = m.getSelect();
            J.Identifier getterId = new J.Identifier(org.openrewrite.Tree.randomId(),
                    org.openrewrite.java.tree.Space.EMPTY, m.getMarkers(), Collections.emptyList(),
                    getterName, getterType, null);
            J.MethodInvocation getterCall = new J.MethodInvocation(
                    org.openrewrite.Tree.randomId(),
                    m.getPrefix(),
                    m.getMarkers(),
                    org.openrewrite.java.tree.JRightPadded.build(select),
                    null,
                    getterId,
                    org.openrewrite.java.tree.JContainer.build(org.openrewrite.java.tree.Space.EMPTY,
                            Collections.singletonList(
                                    org.openrewrite.java.tree.JRightPadded.build(
                                            new J.Empty(org.openrewrite.Tree.randomId(),
                                                    org.openrewrite.java.tree.Space.EMPTY, m.getMarkers()))),
                            m.getMarkers()),
                    getterType
            );

            J.Identifier targetId = new J.Identifier(org.openrewrite.Tree.randomId(),
                    org.openrewrite.java.tree.Space.EMPTY, m.getMarkers(), Collections.emptyList(),
                    targetMethodName, targetMethodType, null);
            return new J.MethodInvocation(
                    org.openrewrite.Tree.randomId(),
                    org.openrewrite.java.tree.Space.EMPTY,
                    m.getMarkers(),
                    org.openrewrite.java.tree.JRightPadded.build(getterCall),
                    null,
                    targetId,
                    org.openrewrite.java.tree.JContainer.build(org.openrewrite.java.tree.Space.EMPTY,
                            Collections.singletonList(
                                    org.openrewrite.java.tree.JRightPadded.build(args.get(0))),
                            m.getMarkers()),
                    targetMethodType
            );
        }

        private static JavaType.Method findNoArgMethod(JavaType.FullyQualified declaring, String name) {
            if (declaring == null) {
                return null;
            }
            for (JavaType.Method method : declaring.getMethods()) {
                if (method.getName().equals(name) && method.getParameterTypes().isEmpty()) {
                    return method;
                }
            }
            JavaType.FullyQualified supertype = declaring.getSupertype();
            if (supertype != null && supertype != declaring) {
                JavaType.Method r = findNoArgMethod(supertype, name);
                if (r != null) {
                    return r;
                }
            }
            for (JavaType.FullyQualified iface : declaring.getInterfaces()) {
                JavaType.Method r = findNoArgMethod(iface, name);
                if (r != null) {
                    return r;
                }
            }
            return null;
        }

        private static JavaType.Method findTargetMethod(JavaType.FullyQualified declaring, String methodName) {
            if (declaring == null) {
                return null;
            }
            for (JavaType.Method method : declaring.getMethods()) {
                if (method.getName().equals(methodName) && method.getParameterTypes().size() == 1) {
                    return method;
                }
            }
            JavaType.FullyQualified supertype = declaring.getSupertype();
            if (supertype != null && supertype != declaring) {
                JavaType.Method r = findTargetMethod(supertype, methodName);
                if (r != null) {
                    return r;
                }
            }
            for (JavaType.FullyQualified iface : declaring.getInterfaces()) {
                JavaType.Method r = findTargetMethod(iface, methodName);
                if (r != null) {
                    return r;
                }
            }
            return null;
        }

        private static JavaType.FullyQualified asFullyQualified(JavaType t) {
            return t instanceof JavaType.FullyQualified ? (JavaType.FullyQualified) t : null;
        }
    }
}
