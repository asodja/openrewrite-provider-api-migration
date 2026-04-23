package org.gradle.rewrite.providerapi.tools;

import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.config.Environment;
import org.openrewrite.java.JavaParser;
import org.openrewrite.kotlin.KotlinParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Standalone main that parses source directories with OpenRewrite directly and applies a recipe,
 * bypassing the rewrite-gradle-plugin entirely. Used for projects where the plugin's cascade-compile
 * behavior is prohibitive (e.g. the Kotlin repo, which runs out of memory with the plugin).
 *
 * <p>Usage:
 * <pre>
 *   java -cp recipe-jar:openrewrite-deps org.gradle.rewrite.providerapi.tools.StandaloneRunner \
 *       --src-dir src/main/java \
 *       --src-dir src/main/kotlin \
 *       --classpath /path/to/gradle-api.jar:/path/to/other.jar \
 *       --recipe org.gradle.rewrite.providerapi.MigrateToProviderApi
 * </pre>
 *
 * <p>Multiple {@code --src-dir} / {@code --classpath} entries are allowed. The recipe is resolved
 * from the classpath via OpenRewrite's {@link Environment} scanner, which discovers recipes
 * declared in {@code META-INF/rewrite/*.yml} or via the {@code @RecipeDescriptor} annotation.
 *
 * <p>Output: results are written back in-place when {@code --apply} is passed. Without that flag,
 * the program prints a unified diff to stdout and exits with code 0 (dry-run default).
 */
public final class StandaloneRunner {

    private final List<Path> srcDirs = new ArrayList<>();
    private final List<Path> scriptFiles = new ArrayList<>();
    private final Set<Path> classpath = new java.util.LinkedHashSet<>();
    private String recipeName = "org.gradle.rewrite.providerapi.MigrateToProviderApi";
    private boolean apply = false;
    private boolean verbose = false;

    public static void main(String[] args) throws Exception {
        StandaloneRunner r = new StandaloneRunner();
        r.parseArgs(args);
        int exit = r.run();
        System.exit(exit);
    }

    private void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--src-dir":
                    srcDirs.add(Paths.get(args[++i]).toAbsolutePath().normalize());
                    break;
                case "--src-dirs":
                    for (String p : args[++i].split("[,:]")) {
                        if (!p.isEmpty()) srcDirs.add(Paths.get(p).toAbsolutePath().normalize());
                    }
                    break;
                case "--script-file":
                    scriptFiles.add(Paths.get(args[++i]).toAbsolutePath().normalize());
                    break;
                case "--classpath":
                    for (String p : args[++i].split("[,:]")) {
                        if (!p.isEmpty()) classpath.add(Paths.get(p).toAbsolutePath().normalize());
                    }
                    break;
                case "--recipe":
                    recipeName = args[++i];
                    break;
                case "--apply":
                    apply = true;
                    break;
                case "--verbose":
                case "-v":
                    verbose = true;
                    break;
                case "--help":
                case "-h":
                    printUsage();
                    System.exit(0);
                    break;
                default:
                    System.err.println("unknown arg: " + a);
                    printUsage();
                    System.exit(2);
            }
        }
        if (srcDirs.isEmpty() && scriptFiles.isEmpty()) {
            System.err.println("at least one --src-dir or --script-file is required");
            printUsage();
            System.exit(2);
        }
    }

    private static void printUsage() {
        System.err.println("usage: StandaloneRunner --src-dir <path> [--src-dir <path>] ...");
        System.err.println("                        [--classpath path1:path2:...]");
        System.err.println("                        [--recipe <fqn>]");
        System.err.println("                        [--apply]       # write changes to disk (default: dry run)");
        System.err.println("                        [--verbose]");
    }

    private int run() throws Exception {
        List<Path> existingDirs = srcDirs.stream()
                .filter(Files::isDirectory)
                .collect(Collectors.toList());
        List<Path> existingScripts = scriptFiles.stream()
                .filter(Files::isRegularFile)
                .collect(Collectors.toList());
        if (existingDirs.isEmpty() && existingScripts.isEmpty()) {
            System.err.println("none of the supplied --src-dir / --script-file values exist");
            return 2;
        }

        List<Path> javaFiles = new ArrayList<>();
        List<Path> kotlinFiles = new ArrayList<>();
        List<Path> groovyFiles = new ArrayList<>();
        for (Path root : existingDirs) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).forEach(p -> classify(p, javaFiles, kotlinFiles, groovyFiles));
            }
        }
        for (Path scriptFile : existingScripts) {
            classify(scriptFile, javaFiles, kotlinFiles, groovyFiles);
        }

        int fileCount = javaFiles.size() + kotlinFiles.size() + groovyFiles.size();
        System.out.println(String.format(
                "[rewrite-runner] %d files (%d java, %d kotlin, %d groovy) from %d source dir%s",
                fileCount, javaFiles.size(), kotlinFiles.size(), groovyFiles.size(),
                existingDirs.size(), existingDirs.size() == 1 ? "" : "s"));
        if (fileCount == 0) {
            System.out.println("[rewrite-runner] nothing to do");
            return 0;
        }

        ExecutionContext ctx = new InMemoryExecutionContext(Throwable::printStackTrace);
        // Disable OpenRewrite's built-in print-idempotency check (Parser.requirePrintEqualsInput).
        // rewrite-java 8.80.0 has deterministic Javadoc reprint bugs — multi-line `@param` blocks
        // collapse onto one line, whitespace around `{@code}` tags shifts, etc. With the check on,
        // any file tripping the bug becomes a ParseError and is silently skipped by recipes. With
        // it off, we get a real J.CompilationUnit, recipes can fire on the code, and any Javadoc
        // noise shows up visibly in the rewrite diff for the user to review before `--apply`.
        ctx.putMessage("org.openrewrite.requirePrintEqualsInput", false);

        List<SourceFile> sources = new ArrayList<>();

        if (verbose) {
            System.out.println("[rewrite-runner] classpath entries: " + classpath.size());
        }
        List<Path> cpList = new ArrayList<>(classpath);
        if (!javaFiles.isEmpty()) {
            JavaParser parser = JavaParser.fromJavaVersion()
                    .classpath(cpList)
                    .logCompilationWarningsAndErrors(false)
                    .build();
            parser.parse(javaFiles, null, ctx).forEach(sources::add);
            if (verbose) System.out.println("[rewrite-runner] parsed " + javaFiles.size() + " java files");
        }
        if (!kotlinFiles.isEmpty()) {
            // Split into script vs plain .kt. Gradle .kts files need Kotlin's script-compilation mode
            // so the implicit receiver (Project / Settings / Gradle) resolves — without it, every
            // `tasks.withType<T>()`, `configureEach`, `registering`, etc. comes back with null type
            // attribution and catalog-driven recipes can't fire. See openrewrite/rewrite#6312.
            List<Path> plainKt = new ArrayList<>();
            List<Path> projectScripts = new ArrayList<>();
            List<Path> settingsScripts = new ArrayList<>();
            List<Path> initScripts = new ArrayList<>();
            for (Path p : kotlinFiles) {
                String name = p.getFileName().toString();
                if (name.equals("settings.gradle.kts") || name.endsWith(".settings.gradle.kts")) {
                    settingsScripts.add(p);
                } else if (name.equals("init.gradle.kts") || name.endsWith(".init.gradle.kts")) {
                    initScripts.add(p);
                } else if (name.endsWith(".gradle.kts")) {
                    projectScripts.add(p);
                } else {
                    plainKt.add(p);
                }
            }
            if (!plainKt.isEmpty()) {
                KotlinParser kparser = KotlinParser.builder()
                        .classpath(cpList)
                        .logCompilationWarningsAndErrors(false)
                        .build();
                kparser.parse(plainKt, null, ctx).forEach(sources::add);
            }
            if (!projectScripts.isEmpty()) {
                buildScriptParser(cpList, "org.gradle.api.Project")
                        .parse(projectScripts, null, ctx).forEach(sources::add);
            }
            if (!settingsScripts.isEmpty()) {
                buildScriptParser(cpList, "org.gradle.api.initialization.Settings")
                        .parse(settingsScripts, null, ctx).forEach(sources::add);
            }
            if (!initScripts.isEmpty()) {
                buildScriptParser(cpList, "org.gradle.api.invocation.Gradle")
                        .parse(initScripts, null, ctx).forEach(sources::add);
            }
            if (verbose) {
                System.out.println("[rewrite-runner] parsed " + kotlinFiles.size() +
                        " kotlin files (" + plainKt.size() + " plain, " +
                        projectScripts.size() + " project .gradle.kts, " +
                        settingsScripts.size() + " settings, " + initScripts.size() + " init)");
            }
        }
        if (!groovyFiles.isEmpty()) {
            // rewrite-groovy's GroovyParser takes classpath too. Loading it reflectively avoids a
            // hard compile-time dep from this runner on rewrite-groovy (already a runtime dep of
            // the recipe module).
            try {
                Class<?> gpClass = Class.forName("org.openrewrite.groovy.GroovyParser");
                Object builder = gpClass.getMethod("builder").invoke(null);
                builder.getClass().getMethod("classpath", java.util.Collection.class).invoke(builder, cpList);
                builder.getClass().getMethod("logCompilationWarningsAndErrors", boolean.class).invoke(builder, false);
                Object parser = builder.getClass().getMethod("build").invoke(builder);
                Object stream = parser.getClass().getMethod("parse", Iterable.class, Path.class, ExecutionContext.class)
                        .invoke(parser, groovyFiles, null, ctx);
                if (stream instanceof java.util.stream.Stream) {
                    ((java.util.stream.Stream<?>) stream).forEach(s -> sources.add((SourceFile) s));
                }
                if (verbose) System.out.println("[rewrite-runner] parsed " + groovyFiles.size() + " groovy files");
            } catch (ReflectiveOperationException e) {
                System.err.println("[rewrite-runner] WARN: groovy parser unavailable: " + e.getMessage());
            }
        }

        if (sources.isEmpty()) {
            System.out.println("[rewrite-runner] no sources parsed successfully");
            return 0;
        }

        // Flag sources whose reparse doesn't match the original bytes — rewrite-java 8.80.0 has
        // Javadoc reprint bugs that silently corrupt `{@code}` whitespace and `@param` line breaks.
        // With the idempotency check disabled above, these files now parse and recipes fire on
        // their code, but if we write changes, the output will include the Javadoc noise.
        List<Path> nonIdempotentSources = new ArrayList<>();
        for (SourceFile sf : sources) {
            if (sf instanceof org.openrewrite.tree.ParseError) continue;
            Path sourcePath = sf.getSourcePath();
            Path onDisk = sourcePath.isAbsolute() ? sourcePath : sourcePath.toAbsolutePath();
            if (!Files.isRegularFile(onDisk)) continue;
            try {
                String original = new String(Files.readAllBytes(onDisk), StandardCharsets.UTF_8);
                if (!original.equals(sf.printAll())) {
                    nonIdempotentSources.add(onDisk);
                }
            } catch (IOException ignored) {
                // Can't read original bytes; skip the check.
            }
        }

        Environment env = Environment.builder().scanRuntimeClasspath().build();
        Recipe recipe = env.activateRecipes(recipeName);
        if (recipe.getRecipeList().isEmpty() && !recipeName.startsWith("org.openrewrite.")) {
            // Fallback: try to instantiate directly if the meta-recipe's YAML wasn't found.
            try {
                Class<?> cls = Class.forName(recipeName);
                recipe = (Recipe) cls.getDeclaredConstructor().newInstance();
            } catch (Throwable ignored) {
                System.err.println("[rewrite-runner] could not resolve recipe: " + recipeName);
                return 2;
            }
        }

        System.out.println("[rewrite-runner] running recipe: " + recipe.getName());
        if (verbose) {
            System.out.println("[rewrite-runner] recipe list (" + recipe.getRecipeList().size() + "): ");
            recipe.getRecipeList().forEach(r -> System.out.println("    - " + r.getName()));
        }
        org.openrewrite.LargeSourceSet lss = new org.openrewrite.internal.InMemoryLargeSourceSet(sources);
        List<Result> results = recipe.run(lss, ctx).getChangeset().getAllResults();

        int changed = 0;
        for (Result result : results) {
            if (result.getAfter() == null || result.getBefore() == null) continue;
            if (result.getBefore().getSourcePath().equals(result.getAfter().getSourcePath())
                    && result.getBefore().printAll().equals(result.getAfter().printAll())) {
                continue;
            }
            changed++;
            Path target = result.getAfter().getSourcePath().toAbsolutePath();
            System.out.println("--- " + target);
            if (apply) {
                writeSource(target, result.getAfter().printAll());
            } else if (verbose) {
                System.out.println(result.diff());
            }
        }
        System.out.println(String.format("[rewrite-runner] %d files with changes (%s)",
                changed, apply ? "applied" : "dry run — pass --apply to write"));
        if (!nonIdempotentSources.isEmpty()) {
            System.out.println("[rewrite-runner] WARN: " + nonIdempotentSources.size() +
                    " file(s) had non-idempotent parses (rewrite-java 8.80.0 Javadoc bug). Review" +
                    " Javadoc changes in these files before committing:");
            for (Path p : nonIdempotentSources) {
                System.out.println("    " + p);
            }
        }
        return 0;
    }

    /**
     * Default imports applied to every Kotlin script parse — mirrors the set Gradle injects when
     * compiling {@code .gradle.kts} files. Without these, unqualified references like {@code Test}
     * or {@code Javadoc} wouldn't resolve even with the correct implicit receiver set.
     */
    private static final String[] GRADLE_SCRIPT_DEFAULT_IMPORTS = {
            "org.gradle.*",
            "org.gradle.api.*",
            "org.gradle.api.artifacts.*",
            "org.gradle.api.artifacts.dsl.*",
            "org.gradle.api.attributes.*",
            "org.gradle.api.component.*",
            "org.gradle.api.file.*",
            "org.gradle.api.initialization.*",
            "org.gradle.api.invocation.*",
            "org.gradle.api.logging.*",
            "org.gradle.api.model.*",
            "org.gradle.api.plugins.*",
            "org.gradle.api.provider.*",
            "org.gradle.api.publish.*",
            "org.gradle.api.resources.*",
            "org.gradle.api.services.*",
            "org.gradle.api.specs.*",
            "org.gradle.api.tasks.*",
            "org.gradle.api.tasks.bundling.*",
            "org.gradle.api.tasks.compile.*",
            "org.gradle.api.tasks.javadoc.*",
            "org.gradle.api.tasks.testing.*",
            "org.gradle.external.javadoc.*",
            "org.gradle.jvm.*",
            "org.gradle.jvm.toolchain.*",
            "org.gradle.plugin.use.*",
            "org.gradle.process.*",
            "org.gradle.kotlin.dsl.*",
    };

    private static KotlinParser buildScriptParser(List<Path> cpList, String implicitReceiverFqn) {
        return KotlinParser.builder()
                .classpath(cpList)
                .isKotlinScript(true)
                .scriptImplicitReceivers(implicitReceiverFqn)
                .scriptDefaultImports(GRADLE_SCRIPT_DEFAULT_IMPORTS)
                .logCompilationWarningsAndErrors(false)
                .build();
    }

    private static void classify(Path p, List<Path> javaFiles, List<Path> kotlinFiles, List<Path> groovyFiles) {
        String name = p.getFileName().toString();
        if (name.endsWith(".java")) javaFiles.add(p);
        else if (name.endsWith(".kt") || name.endsWith(".kts")) kotlinFiles.add(p);
        else if (name.endsWith(".groovy") || name.endsWith(".gradle")) groovyFiles.add(p);
    }

    private void writeSource(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    private StandaloneRunner() {}
}
