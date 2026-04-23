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
            KotlinParser kparser = KotlinParser.builder()
                    .classpath(cpList)
                    .logCompilationWarningsAndErrors(false)
                    .build();
            kparser.parse(kotlinFiles, null, ctx).forEach(sources::add);
            if (verbose) System.out.println("[rewrite-runner] parsed " + kotlinFiles.size() + " kotlin files");
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
        return 0;
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
