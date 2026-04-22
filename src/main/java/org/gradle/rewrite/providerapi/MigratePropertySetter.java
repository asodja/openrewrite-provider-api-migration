package org.gradle.rewrite.providerapi;

import org.gradle.rewrite.providerapi.internal.GradleBuildLogic;
import org.gradle.rewrite.providerapi.internal.MigratedProperties;
import org.gradle.rewrite.providerapi.internal.MigratedProperties.Kind;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import java.util.Collections;
import java.util.List;

/**
 * Migrate {@code recv.setX(v)} to {@code recv.getX().set(v)} for scalar {@code Property<T>} properties
 * that have migrated from the eager setter API.
 *
 * <p>The recipe uses {@link MigratedProperties} as the source of truth for which properties have
 * migrated — it does NOT inspect the corresponding getter's return type on the current classpath.
 * That matters: users run this recipe on their current (pre-migration) Gradle, which still returns
 * eager types from the getters. Consulting the classpath would cause the recipe to never fire.
 * Sibling recipes handle {@code ListProperty}, {@code MapProperty}, {@code SetProperty}, and
 * {@code ConfigurableFileCollection} by filtering on the catalog {@link Kind}.
 */
public class MigratePropertySetter extends Recipe {

    @Option(displayName = "Setter method pattern",
            description = "Optional method matcher to restrict which setter signatures are rewritten. " +
                          "By default, any setX(value) for a cataloged SCALAR_PROPERTY is rewritten.",
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
        return "Rewrites `recv.setX(v)` to `recv.getX().set(v)` for properties that migrated to " +
               "`Property<T>` on the Provider API. Driven by a hardcoded catalog of migrated " +
               "properties, so the recipe fires correctly when run against the old (pre-migration) " +
               "Gradle classpath — consistent with how other OpenRewrite Gradle-upgrade recipes work.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        MethodMatcher userMatcher = setterPattern == null ? null : new MethodMatcher(setterPattern, true);
        return new SetterToPropertyVisitor(userMatcher, Kind.SCALAR_PROPERTY, "set");
    }

    /**
     * Rewrites {@code recv.setX(v)} into {@code recv.getX().<targetMethod>(v)} when the catalog says the
     * property migrated to {@link Kind}. Shared by {@link MigratePropertySetter},
     * {@link MigrateListPropertySetter}, {@link MigrateMapPropertySetter},
     * {@link MigrateSetPropertySetter}, and {@link MigrateConfigurableFileCollectionSetter}.
     */
    static final class SetterToPropertyVisitor extends JavaIsoVisitor<ExecutionContext> {
        private final MethodMatcher userMatcher;
        private final Kind expectedKind;
        private final String targetMethodName;

        SetterToPropertyVisitor(MethodMatcher userMatcher, Kind expectedKind, String targetMethodName) {
            this.userMatcher = userMatcher;
            this.expectedKind = expectedKind;
            this.targetMethodName = targetMethodName;
        }

        @Override
        public boolean isAcceptable(SourceFile sourceFile, ExecutionContext ctx) {
            return GradleBuildLogic.isBuildLogic(sourceFile);
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation m = super.visitMethodInvocation(method, ctx);

            if (userMatcher != null && !userMatcher.matches(m)) {
                return m;
            }

            // Resolve the declaring type by preferring the method's declaring type when available
            // (most precise — works for both explicit and implicit-this calls), falling back to the
            // receiver's static type. For implicit-this in Kotlin DSL blocks, getSelect() is null but
            // getMethodType().getDeclaringType() points at the enclosing block's receiver type.
            JavaType.Method setterType = m.getMethodType();
            String methodName = setterType != null ? setterType.getName() : m.getSimpleName();
            String propName = MigratedProperties.propertyNameFromSetter(methodName);
            if (propName == null) {
                return m;
            }
            if (m.getArguments().size() != 1 || m.getArguments().get(0) instanceof J.Empty) {
                return m;
            }

            JavaType.FullyQualified declaring = resolveDeclaring(setterType, m.getSelect());
            Kind kind = MigratedProperties.lookup(declaring, propName);
            if (kind == null) {
                // Fallback: implicit-this calls inside .gradle.kts configuration blocks (and sometimes
                // doLast / doFirst closures) don't carry a resolved declaring type. If the property
                // name maps unambiguously to one kind across the whole catalog, we can safely apply
                // the rewrite without knowing the exact receiver type.
                kind = MigratedProperties.lookupByNameOnly(propName);
            }
            if (kind != expectedKind) {
                return m;
            }

            String getterName = "get" + methodName.substring(3);
            return rebuildAsPropertyChain(m, getterName, declaring);
        }

        /**
         * Construct {@code select.getX().<targetMethodName>(arg)}. Opportunistically attaches types when
         * the getter is visible on the classpath (hybrid Gradle API, test stubs); on a strictly old
         * classpath the generated call just carries null types — the next parse cycle re-attributes it.
         */
        private J.MethodInvocation rebuildAsPropertyChain(J.MethodInvocation m, String getterName,
                                                           JavaType.FullyQualified declaring) {
            Expression select = m.getSelect();
            // Implicit-this calls (`setX(v)` inside a Kotlin DSL block) have no explicit receiver. We
            // rewrite them to implicit-this getter chains (`getX().set(v)`) by leaving select null on
            // the inner invocation too. Kotlin resolves the implicit receiver at compile time.
            JavaType.Method getterType = findNoArgMethod(declaring, getterName);
            JavaType.Method targetType = null;
            if (getterType != null) {
                JavaType returnType = getterType.getReturnType();
                JavaType.FullyQualified returnFq = returnType instanceof JavaType.FullyQualified
                        ? (JavaType.FullyQualified) returnType : null;
                if (returnFq != null) {
                    targetType = findMethod(returnFq, targetMethodName, 1);
                }
            }

            J.Identifier getterId = new J.Identifier(org.openrewrite.Tree.randomId(),
                    org.openrewrite.java.tree.Space.EMPTY, m.getMarkers(), Collections.emptyList(),
                    getterName, getterType, null);
            J.MethodInvocation getterCall = new J.MethodInvocation(
                    org.openrewrite.Tree.randomId(),
                    m.getPrefix(),
                    m.getMarkers(),
                    select == null ? null : org.openrewrite.java.tree.JRightPadded.build(select),
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
                    targetMethodName, targetType, null);
            List<Expression> args = m.getArguments();
            return new J.MethodInvocation(
                    org.openrewrite.Tree.randomId(),
                    org.openrewrite.java.tree.Space.EMPTY,
                    m.getMarkers(),
                    org.openrewrite.java.tree.JRightPadded.build(getterCall),
                    null,
                    targetId,
                    org.openrewrite.java.tree.JContainer.build(org.openrewrite.java.tree.Space.EMPTY,
                            Collections.singletonList(
                                    org.openrewrite.java.tree.JRightPadded.build(
                                            args.get(0).withPrefix(org.openrewrite.java.tree.Space.EMPTY))),
                            m.getMarkers()),
                    targetType
            );
        }

        private static JavaType.Method findNoArgMethod(JavaType.FullyQualified declaring, String name) {
            if (declaring == null) return null;
            for (JavaType.Method method : declaring.getMethods()) {
                if (method.getName().equals(name) && method.getParameterTypes().isEmpty()) {
                    return method;
                }
            }
            JavaType.FullyQualified supertype = declaring.getSupertype();
            if (supertype != null && supertype != declaring) {
                JavaType.Method r = findNoArgMethod(supertype, name);
                if (r != null) return r;
            }
            for (JavaType.FullyQualified iface : declaring.getInterfaces()) {
                JavaType.Method r = findNoArgMethod(iface, name);
                if (r != null) return r;
            }
            return null;
        }

        private static JavaType.Method findMethod(JavaType.FullyQualified declaring, String name, int arity) {
            if (declaring == null) return null;
            for (JavaType.Method method : declaring.getMethods()) {
                if (method.getName().equals(name) && method.getParameterTypes().size() == arity) {
                    return method;
                }
            }
            JavaType.FullyQualified supertype = declaring.getSupertype();
            if (supertype != null && supertype != declaring) {
                JavaType.Method r = findMethod(supertype, name, arity);
                if (r != null) return r;
            }
            for (JavaType.FullyQualified iface : declaring.getInterfaces()) {
                JavaType.Method r = findMethod(iface, name, arity);
                if (r != null) return r;
            }
            return null;
        }

        private static JavaType.FullyQualified resolveDeclaring(JavaType.Method setterType, Expression select) {
            if (setterType != null) {
                JavaType.FullyQualified declaring = setterType.getDeclaringType();
                if (declaring != null) {
                    return declaring;
                }
            }
            if (select == null) {
                return null;
            }
            return select.getType() instanceof JavaType.FullyQualified
                    ? (JavaType.FullyQualified) select.getType() : null;
        }
    }
}
