# Implementation Guide

Maintainers' reference to how this project is structured, how it runs, and
what each recipe actually does. Pair with `README.md` (user-facing how-to),
`KNOWN-ISSUES.md` (false positives / coverage gaps), and `MIGRATION-ANALYSIS.md`
(pattern catalog the recipes were derived from).

---

## 1. Project layout

```
src/main/java/org/gradle/rewrite/providerapi/
    *.java                              — 23 recipes (flat package)
    internal/*.java                     — shared helpers (catalog, scope walker, etc.)
    tools/StandaloneRunner.java         — the bypass-Gradle-plugin runner
src/main/resources/META-INF/rewrite/
    migrate-to-provider-api.yml         — declarative meta-recipe (composes the 23 above)
src/test/java/org/gradle/rewrite/providerapi/
    *Test.java                          — one or more tests per recipe
    GradleApi{Stubs,KotlinStubs}.java   — Java / Kotlin stubs of Gradle API for tests
tools/
    extract_catalog.py                  — scans Gradle source for @ReplacesEagerProperty
    catalog_to_java.py                  — turns that scan into MigratedPropertiesCatalog.java
discovery-init.gradle.kts               — configuration-only init script, writes manifest
rewrite-init.gradle.kts                 — init script used when running via rewrite-gradle-plugin
run-migration.sh                        — shell wrapper around the standalone-runner path
```

---

## 2. How the migration runs

Two execution paths, depending on how much scale / classpath complexity the
user has.

### 2a. Via `rewrite-gradle-plugin` (the "normal" OpenRewrite path)

User adds the plugin + activates the meta-recipe, then runs
`./gradlew rewriteRun`. The plugin wires every compile task's output as
input, so it effectively runs the full compile cascade. Works fine on
small / medium projects. On large multi-project builds (Kotlin repo scale)
the cascade OOMs or stalls on unrelated broken tasks — that's why path 2
exists.

`rewrite-init.gradle.kts` excludes generated / tooling dirs
(`build/`, `.gradle/`, `.idea/`, `out/`, `bin/`, `target/`, `node_modules/`)
from the parser's input, saving up-front parse time. These dirs would
otherwise produce noisy ParseErrors and contribute nothing useful.

### 2b. Via `run-migration.sh` (the standalone runner)

Three stages:

1. **Discovery** — `discovery-init.gradle.kts` is passed to `./gradlew help`
   via `--init-script`. It hooks `gradle.allprojects { afterEvaluate }` and
   `gradle.projectsEvaluated`, extracts per-source-set info into
   `.rewrite-manifest.json`, and throws a `GradleException` to abort before
   any task runs. Keeps total time to ~5s on any project.

   The manifest records, per source set:
     - `project`, `sourceSet`, `srcDirs`
     - `kind`, a three-way classification driven by a single structural signal
       plus one opt-in fallback:
         - `"buildLogic"` — the project is declared directly on some script's
           **buildscript classpath** inside this build, i.e. some script has
           `buildscript { dependencies { classpath project(":this") } }`.
           That's Gradle's own "this is code loaded at configuration time to
           resolve plugins / buildscript blocks" signal — authoritative, not
           heuristic. Covers convention plugins wired in via the classic
           buildscript block.
         - `"publishedGradlePlugin"` — applies `java-gradle-plugin` but is NOT
           on any buildscript classpath in this build. Treated as a plugin the
           user ships to external consumers (maven-publish /
           com.gradle.plugin-publish / kotlin-dsl-of-a-plugin-module).
           Examples: `:kotlin-gradle-plugin`, `:kotlin-allopen`, the whole
           `libraries/tools/` family in the Kotlin monorepo. EXCLUDED by
           default because these plugins support many Gradle versions back
           and changing their public Kotlin/Java API breaks downstream users.
           Opt in via the runner's `--only-published-plugins` flag, which
           inverts the filter (migrate only these; buildLogic is skipped —
           the flag is mutually exclusive with the default).
         - `"production"` — neither of the above. Regular library / application
           code. Never migrated.
       Correctly excludes code that merely imports `org.gradle.*` like
       gradle/gradle's own source tree or Tooling API consumers.

       The classifier does NOT take the transitive closure over compile
       configurations of buildscript-classpath projects. A production library
       that a build-logic project happens to depend on via `implementation`
       (e.g. `:kotlin-stdlib` as a compile dep of an internal codegen
       `:generators`) is still production code; sweeping it in would run
       recipes against unsuspecting library sources. The known cost: if
       build-logic is split across multiple subprojects inside ONE build via
       `buildscript { classpath project(":a") }` + `:a` depends on `:b` via
       `implementation`, only `:a` is classified. Users should either add
       each helper to the buildscript classpath explicitly, or move the
       whole build-logic into `pluginManagement { includeBuild("...") }`
       where every project inside is caught by nested-build handling below.
     - `projectRoots`, `nestedBuilds` (separate entries for `buildSrc` and
       explicit `includeBuild` targets). Nested builds are walked whole-dir
       by the runner — a convention that assumes `buildSrc`, `build-logic/`,
       `gradle-build-conventions`, etc. contain exclusively build-logic (as
       is standard Gradle practice).
     - `gradleApi` — list of jars in the Gradle distribution's `lib/` dir,
       used as the shared type-attribution classpath

2. **Filter** — `run-migration.sh` reads the manifest and assembles inputs
   for exactly one kind at a time (mutually exclusive):
     - default                       → `--src-dir` args for source sets with
                                       `kind == "buildLogic"`
     - `--only-published-plugins` → `--src-dir` args for source sets with
                                       `kind == "publishedGradlePlugin"`
   plus:
     - `--src-dir` args for each nested build (walked as a whole, regardless
       of the chosen kind)
     - `--script-file` args for `*.gradle` / `*.gradle.kts` at project roots
     - `--classpath` = flattened Gradle distribution jars

3. **Run** — `tools/StandaloneRunner` parses those inputs via OpenRewrite's
   parsers directly, runs the meta-recipe, writes a unified diff (or
   applies changes when `--apply` is passed).

   The runner also:
     - Disables OpenRewrite's `requirePrintEqualsInput` check, so the
       rewrite-java 8.80.0 Javadoc bug doesn't silently demote affected
       files to `ParseError`. Files that tripped the bug are listed at the
       end of the run so the user can review their Javadoc diffs.
     - Uses Kotlin **script mode** for `.gradle.kts` files —
       `isKotlinScript(true).scriptImplicitReceivers(Project|Settings|Gradle)
       .scriptDefaultImports(Gradle kotlin-dsl default imports)` — so
       outer-level DSL calls (`tasks.withType<T>`, `configureEach`,
       `registering`) come back with resolved types instead of `null`.

Errors during discovery (wrong JDK, unresolvable plugins) are captured to
a tmpfile and the last 30 lines of Gradle's output are displayed, so the
user sees the real cause rather than a generic "manifest not produced".

---

## 3. Recipes

23 recipes, organized by tier. Tier numbering comes from `migrate-to-provider-api.yml`.

### Tier 1 — mechanical setter rewrites

Order matters: narrower receivers first so the generic `Property<T>` recipe
doesn't grab them.

| Recipe | What it does |
|---|---|
| `MigrateConfigurableFileCollectionSetter` | `recv.setX(v)` → `recv.getX().setFrom(v)` when `X` is cataloged as `ConfigurableFileCollection`. Setters on CFC were removed; `setFrom` is the public replacement. |
| `MigrateListPropertySetter` | Same shape, cataloged as `ListProperty<T>` → `recv.getX().set(v)`. |
| `MigrateSetPropertySetter` | Same shape, cataloged as `SetProperty<T>`. |
| `MigrateMapPropertySetter` | Same shape, cataloged as `MapProperty<K, V>`. |
| `MigratePropertySetter` | Same shape, cataloged as scalar `Property<T>`. The generic fallback — runs after the above so it doesn't overwrite a more specific rewrite. |

All five share the `SetterToPropertyVisitor` inner class defined in
`MigratePropertySetter` — they only differ in the `Kind` they filter on and
the target method name (`set` vs `setFrom`).

### Tier 1 — read-only / renamed properties

| Recipe | What it does |
|---|---|
| `MigrateSetCommandLineMethod` | `setCommandLine(...)` on `ExecSpec`/`Exec`/`JavaExec` → `commandLine(...)`. Method overload rename. |
| `MigrateReadOnlyCommandLineAssignment` | Kotlin DSL `exec.commandLine = list` → `exec.commandLine(list)`. The getter became read-only (`ListProperty<String>`); direct assignment doesn't compile; the `commandLine(Iterable<?>)` method preserves old semantics. |
| `MigrateSourceAssignment` | Groovy `sourceTask.source = x` → `sourceTask.setSource(x)`. `SourceTask.source` became read-only; method call form keeps working. |
| `MigrateDeleteTaskToTargetFiles` | Kotlin `delete.delete = x` → `delete.targetFiles.setFrom(x)`. The `delete` property was renamed to `targetFiles` AND became a read-only CFC. |

### Tier 1 — Kotlin accessor rename

| Recipe | What it does |
|---|---|
| `RenameKotlinBooleanAccessors` | `isX` → `x` for migrated booleans (e.g. `isFailOnNoMatchingTests` → `failOnNoMatchingTests`). When a boolean getter becomes a `Property<Boolean>`, Kotlin drops the `is` prefix. Driven by a hardcoded name list in `internal/BooleanRenames`. |

### Tier 1 — Kotlin DSL import additions

Enabled by default in `MigrateToProviderApi`; the `.NoKotlinAssignPlugin`
variant omits them for users whose build-logic does not apply `kotlin-dsl`
(which bundles the Kotlin assignment compiler plugin).

| Recipe | What it does |
|---|---|
| `AddKotlinAssignImport` | Adds `import org.gradle.kotlin.dsl.assign` to `.kt` files that assign to a `Property<T>` with `=`. `.gradle.kts` files auto-import the kotlin-dsl bundle; plain `.kt` sources don't. |
| `AddKotlinPlusAssignImport` | Same, for `+=` on a `ListProperty`/`SetProperty`. |

### Tier 2 — multi-value mutations

Compose with the setter rewrites above. Run after, so they see `getX().set(v)`
already in place.

| Recipe | What it does |
|---|---|
| `MigrateListPropertyPlusAssign` | `prop += v` → `prop.add(v)` when `prop` is `ListProperty`/`SetProperty`. The `+=` form relied on the removed setter+getter pair. |
| `MigrateListPropertyClear` | `prop.clear()` → `prop.empty()` on `HasMultipleValues` (ListProperty/SetProperty). |
| `MigrateMapPropertyClear` | `prop.clear()` → `prop.empty()` on MapProperty. |
| `MigrateMapPropertyIndexedAssign` | Kotlin/Groovy `prop["k"] = v` → `prop.put("k", v)` on MapProperty. |
| `MigrateAsListToGet` | `prop.asList()` / `asSet()` → `prop.get()`. Those methods don't exist on the lazy API; `get()` returns the materialized collection directly. |
| `MigratePropertyMutations` | `remove(...)` / `compute(...)` / `filterKeys(...)` / etc. on Map/List/SetProperty → mechanical rewrite using Gradle's internal `DefaultXxxProperty.replace(Transformer)` API for Kotlin, or a TODO advisor for Java. Tiered confidence: confident matches (typed scope resolvable) get the mechanical rewrite; name-only candidates get a TODO advisor that the user reviews (defends against the Shadow plugin `excludes: Set<String>` false positive). Every emitted rewrite also carries a TODO with the public copy-mutate-set alternative and the `@file:Suppress` line to silence `UNCHECKED_CAST` / `UPPER_BOUND_VIOLATED_IN_TYPE_OPERATOR_OR_PARAMETER_BOUNDS_WARNING`. |
| `MigrateScalarPropertySelfReference` | `prop = prop.m(...)` → `prop.set(prop.get().m(...))` for cataloged `SCALAR_PROPERTY`. Uses the public `.get()` form rather than the internal `replace { }` so the rewrite compiles without the recipe needing to know `T` statically. |
| `InsertGetOnLazyAccess` | Eager chained access like `recv.propName.someMethod(...)` on a scalar `Property<T>` → inserts `.get()` between propName and the chained member. |
| `InsertAsFileGet` | Same shape for `RegularFileProperty` / `DirectoryProperty`: inserts `.get().asFile`. Also peels Kotlin `!!` non-null assertions (redundant after `.get()` returns non-null). |

### Tier 3 — advisor-only (SearchResult/TODO markers)

No rewriting — just flags sites that need a human decision. Emit via
`internal/Advisor.addTodo`, which prepends a multi-line `/* TODO: ... */`
block above the expression (not the default OpenRewrite `/*~~(...)~~>*/`
inline marker — we found the block-comment form is far more readable at
scale).

| Recipe | What it does |
|---|---|
| `DetectSelfReferencingFileCollection` | `x.setFrom(..., x, ...)` / `x.from(..., x, ...)` on a CFC — the receiver appears in its own args. Deferred evaluation in the lazy API means this loops to an empty collection or deadlocks. |
| `DetectSetterOverride` | Subclass overrides a setter that's been removed from its Gradle parent type (e.g. custom `Test` subclass with `setClasspath(FileCollection)`). The override is orphaned. |

---

## 4. Shared infrastructure

Helpers under `internal/` — pulled out when the same shape was needed by
more than one recipe.

### `MigratedProperties` + `MigratedPropertiesCatalog`

The catalog. Indexed by `(declaring-type FQN, bean property name)` →
`Kind` (`SCALAR_PROPERTY` / `LIST_PROPERTY` / `SET_PROPERTY` / `MAP_PROPERTY`
/ `CONFIGURABLE_FILE_COLLECTION` / `DIRECTORY_PROPERTY` / `REGULAR_FILE_PROPERTY`).

- `MigratedPropertiesCatalog.java` is **auto-generated** from Gradle's own
  `@ReplacesEagerProperty` annotations via `tools/extract_catalog.py`
  (scans a gradle-source clone) → `tools/catalog_to_java.py` (emits Java).
  Regenerate: `python3 tools/extract_catalog.py --source-root $GRADLE_CHECKOUT
  | python3 tools/catalog_to_java.py > src/main/java/.../internal/MigratedPropertiesCatalog.java`.
- `MigratedProperties.java` is **hand-curated**. Additions go here when a
  property didn't land in the `@ReplacesEagerProperty` scan — e.g. an
  inherited property (`Test.environment` via `ProcessForkOptions`) or a
  boolean Kotlin-DSL-specific accessor.
- Lookup methods: `lookup(declaringFq, propName)` walks supertypes;
  `lookupExact` doesn't; `lookupBySimpleName` matches by class simple name
  only when every cataloged entry with that name agrees on `Kind` (used as
  the "maybe" tier by `KotlinDslScope` callers); `lookupByNameOnly` is the
  broadest check — returns a `Kind` when every cataloged entry with that
  property name agrees. Recipes that use it are aware of false-positive
  risk from third-party field name collisions.

The recipes consult this catalog **instead of** the user's current classpath
when deciding whether to rewrite — critically, so the recipes fire correctly
when run against the OLD (pre-migration) Gradle classpath. Otherwise users
would have to bump the Gradle wrapper first, which defeats the whole
purpose.

### `KotlinDslScope`

Cursor-walker that recovers implicit receiver types for Kotlin DSL scopes
where rewrite-kotlin 8.80.0's type attribution gives up (see
`openrewrite/rewrite#6312`, `KNOWN-ISSUES.md`).

Supported shapes:
  - `withType<T> { }`
  - `register<T>(...) { }` / `named<T>(...) { }` / `configure<T> { }`
  - `registering(T::class) { }` / `register(T::class, ...) { }`
  - Chained `withType<T>().configureEach { }` / `.all { }`
  - `tasks.<builtin> { }` — hardcoded map for `javadoc`, `compileJava`,
    `compileTestJava`, `test`, `jar`, `sourcesJar`, `javadocJar`,
    `processResources`, `processTestResources`, `clean`

Returns only the **simple name** of the receiver type (e.g. `"Javadoc"`).
Callers pair it with `MigratedProperties.lookupBySimpleName(scope, prop)`
to get a `Kind`.

### `Advisor`

Produces the multi-line `/* TODO: ... */` block-comment advisors used by
Tier 3 and by the mechanical-rewrite tiers when they want to annotate
their output (internal-API fragility hint, candidate-match disclaimer).

- `addTodo(expr, message)` prepends the comment with indentation derived
  from the expression's prefix whitespace.
- Idempotency guard: if an identical comment is already in the prefix
  (from a previous recipe cycle), skip — without this, OpenRewrite's
  multi-cycle convergence check stacks duplicates.

### `GradleBuildLogic`

- `isBuildLogic(sourceFile)` — fallback heuristic used when the standalone
  runner's manifest classification isn't available (i.e. running via
  `rewrite-gradle-plugin` directly). Checks file path (`.gradle[.kts]`,
  `settings.gradle[.kts]`, etc.) and imports (`org.gradle.*`). Has known
  limitations — see `KNOWN-ISSUES.md`.
- `onlyBuildLogic(innerVisitor)` — wraps a visitor so it only runs on
  build-logic sources.
- `isKotlin(sourceFile)` — simple path-based check, used by recipes that
  emit Kotlin-specific replacement syntax.

### `PropertyTypes` / `BooleanRenames`

Simple string-constants holders.

- `PropertyTypes`: FQNs for `Property`, `HasMultipleValues`,
  `ListProperty`, `SetProperty`, `MapProperty`, `ConfigurableFileCollection`,
  `FileCollection`, `org.gradle.kotlin.dsl.assign` / `plusAssign`.
- `BooleanRenames`: hardcoded table of boolean getter renames consumed by
  `RenameKotlinBooleanAccessors`.

---

## 5. Standalone runner internals

`tools/StandaloneRunner.java` is ~270 lines. Flow:

1. Parse CLI: `--src-dir`, `--script-file`, `--classpath`, `--recipe`,
   `--apply`, `--verbose`.
2. Walk src-dirs, classify each file as Java / Kotlin / Groovy by
   extension.
3. Build execution context; disable `requirePrintEqualsInput`.
4. Parse:
   - Java files — standard `JavaParser.fromJavaVersion()` with classpath.
   - Kotlin files — split further into plain `.kt`, `.gradle.kts` (Project
     receiver), `.settings.gradle.kts` (Settings receiver),
     `.init.gradle.kts` (Gradle receiver). Each group gets its own
     `KotlinParser` with the appropriate `isKotlinScript` / implicit
     receivers / default imports.
   - Groovy — reflectively, since rewrite-groovy is a runtime dep rather
     than a compile-time one.
5. Check each parsed source against its on-disk bytes; collect a list of
   non-idempotent sources (rewrite-java 8.80.0 Javadoc bug) for the end-
   of-run report.
6. Resolve the recipe: `Environment.builder().scanRuntimeClasspath()` →
   `activateRecipes(name)`. Fallback to `Class.forName(name).newInstance()`
   if the YAML wasn't found.
7. Run: `recipe.run(lss, ctx)`, produce a `Changeset`.
8. For each changed file: print `--- path` and either the unified diff (if
   `--verbose` and dry-run) or write the result to disk (if `--apply`).
9. End-of-run summary: N files with changes, WARN list of non-idempotent
   parses.

---

## 6. Adding a new recipe

1. Add the class under `src/main/java/org/gradle/rewrite/providerapi/`.
   Extend `Recipe`. Return `GradleBuildLogic.onlyBuildLogic(innerVisitor)`
   from `getVisitor()` so you don't fire on production code.
2. If you need catalog data: add a hand-curated entry to
   `MigratedProperties` (if the property isn't in the auto-generated
   catalog yet).
3. If you need implicit-receiver resolution inside Kotlin DSL: call
   `KotlinDslScope.findEnclosingTypedScope(cursor)`, then
   `MigratedProperties.lookupBySimpleName(scope, propName)`.
4. Add the recipe FQN to **both** variants in
   `src/main/resources/META-INF/rewrite/migrate-to-provider-api.yml`:
   `MigrateToProviderApi` and `MigrateToProviderApi.NoKotlinAssignPlugin`.
   Pick the right tier based on order-sensitivity.
5. Write at least two tests: one positive (expected rewrite), one negative
   (expected no-op). For Kotlin tests on DSL scope, the parser needs
   `isKotlinScript(true)` + `scriptImplicitReceivers(...)` + default
   imports to match how the runner parses real scripts — see
   `MigratePropertyMutationsTest` for the full RecipeSpec.
6. Run `./gradlew test && ./gradlew installDist --rerun-tasks` and verify
   against at least one real-world target
   (`./run-migration.sh /path/to/target`).

---

## 7. Regenerating the catalog

When Gradle adds more `@ReplacesEagerProperty` annotations, regenerate:

```bash
python3 tools/extract_catalog.py \
    --source-root /path/to/gradle-source-checkout \
    > /tmp/catalog.json
python3 tools/catalog_to_java.py < /tmp/catalog.json \
    > src/main/java/org/gradle/rewrite/providerapi/internal/MigratedPropertiesCatalog.java
./gradlew test
```

The generator is a clean-overwrite — hand edits to
`MigratedPropertiesCatalog.java` will be lost on regen. Put hand-curated
entries in `MigratedProperties.java` instead, under the `// Hand-curated
additions` comment.

---

## 8. See also

- **`README.md`** — end-user how-to (install, run).
- **`KNOWN-ISSUES.md`** — false positives, coverage gaps, upstream bugs.
- **`MIGRATION-ANALYSIS.md`** — the source-pattern catalog the recipes
  were derived from.
