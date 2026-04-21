package org.gradle.rewrite.providerapi;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import java.util.Arrays;
import java.util.List;

/**
 * Rename {@code exec.setCommandLine(...)} to {@code exec.commandLine(...)}.
 *
 * <p>In the Provider API migration, {@code commandLine} became a lazy {@code ListProperty<String>} getter,
 * so the eager {@code setCommandLine} setter was removed. The same receiver still provides a
 * {@code commandLine(String...)} / {@code commandLine(Iterable<?>)} method, which is now the canonical way
 * to overwrite the command line.
 */
public class MigrateSetCommandLineMethod extends Recipe {

    private static final List<MethodMatcher> MATCHERS = Arrays.asList(
            new MethodMatcher("org.gradle.process.ExecSpec setCommandLine(..)", true),
            new MethodMatcher("org.gradle.api.tasks.Exec setCommandLine(..)", true),
            new MethodMatcher("org.gradle.api.tasks.JavaExec setCommandLine(..)", true)
    );

    @Override
    public String getDisplayName() {
        return "Rename `setCommandLine(...)` to `commandLine(...)` on Exec-like tasks";
    }

    @Override
    public String getDescription() {
        return "`setCommandLine` has been removed from `ExecSpec`, `Exec`, and `JavaExec`. The overloads " +
               "named `commandLine(...)` remain and have the same semantics.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                boolean matches = false;
                for (MethodMatcher matcher : MATCHERS) {
                    if (matcher.matches(m)) {
                        matches = true;
                        break;
                    }
                }
                if (!matches) {
                    return m;
                }
                JavaType.Method oldType = m.getMethodType();
                if (oldType == null) {
                    return m;
                }
                JavaType.Method newType = oldType.withName("commandLine");
                return m.withName(m.getName().withSimpleName("commandLine").withType(newType))
                        .withMethodType(newType);
            }
        };
    }
}
