# Gradle Provider API Migration — OpenRewrite Recipes

OpenRewrite recipes that mechanically migrate a Gradle build (Java / Kotlin / Groovy build-logic)
from the eager property API to the Provider API. Drives off an auto-generated catalog of migrated
properties extracted from Gradle's own `@ReplacesEagerProperty` annotations — so the recipes work
against your current Gradle version, no classpath gymnastics required.

## What gets rewritten

| Pattern | Recipe | Example |
|---|---|---|
| `setX(v)` → `getX().set(v)` for scalar `Property<T>` | `MigratePropertySetter` | `test.setMaxParallelForks(4)` |
| Same for `ListProperty` / `MapProperty` / `SetProperty` | `MigrateListPropertySetter`, ... | `opts.setCompilerArgs(args)` |
| `setX(v)` → `getX().setFrom(v)` for `ConfigurableFileCollection` | `MigrateConfigurableFileCollectionSetter` | `test.setClasspath(cp)` |
| Kotlin `isX` → `x` for migrated booleans | `RenameKotlinBooleanAccessors` | `isFailOnNoMatchingTests` |
| Add `import org.gradle.kotlin.dsl.assign` / `plusAssign` | `AddKotlinAssignImport`, `AddKotlinPlusAssignImport` | `.kt` files only |
| `exec.setCommandLine(...)` → `exec.commandLine(...)` | `MigrateSetCommandLineMethod` | |
| `exec.commandLine = list` → `exec.commandLine(list)` | `MigrateReadOnlyCommandLineAssignment` | Kotlin DSL |
| `sourceTask.source = x` → `sourceTask.setSource(x)` | `MigrateSourceAssignment` | Groovy |
| `delete.delete = x` → `delete.targetFiles.setFrom(x)` | `MigrateDeleteTaskToTargetFiles` | Kotlin |
| `prop += v` → `prop.add(v)` | `MigrateListPropertyPlusAssign` | |
| `prop.clear()` → `prop.empty()` | `MigrateListPropertyClear`, `MigrateMapPropertyClear` | |
| `prop["k"] = v` → `prop.put("k", v)` | `MigrateMapPropertyIndexedAssign` | |
| `prop.asList()` → `prop.get()` | `MigrateAsListToGet` | |
| `artifactId = artifactId.upper()` → `set(get().upper())` | `MigrateScalarPropertySelfReference` | Kotlin-DSL scalar-property self-reference |
| Chained eager access → `.get()` inserted | `InsertGetOnLazyAccess` | `task.maxMemory.endsWith(...)` |
| `dirProp.walkTopDown()` → `dirProp.get().asFile.walkTopDown()` | `InsertAsFileGet` | |
| Flag self-referencing CFC patterns | `DetectSelfReferencingFileCollection` | Attaches SearchResult marker |
| Flag overrides of removed setters | `DetectSetterOverride` | Attaches SearchResult marker |
| Flag unsupported `MapProperty` mutations (`remove`, `filterKeys`, ...) | `MigratePropertyMutations` | Emits mechanical copy-mutate-set rewrite + TODO advisor |

All 23 recipes are composed into one meta-recipe: `org.gradle.rewrite.providerapi.MigrateToProviderApi`.

Running on a real codebase and something looks off? Check **[KNOWN-ISSUES.md](./KNOWN-ISSUES.md)** — it tracks the false positives, coverage gaps, and upstream bugs we've hit on junit-framework, spring-framework, and gradle/gradle, with workarounds.

Adding a recipe or debugging the runner? See **[IMPLEMENTATION-README.md](./IMPLEMENTATION-README.md)** — maintainers' reference covering the project layout, the two execution paths (rewrite-gradle-plugin vs. standalone runner), every recipe's behavior, and the shared helpers (catalog, DSL scope walker, advisor).

## Prerequisites

- JDK 17+ (Gradle toolchains auto-downloads if you use the foojay plugin)
- Your target project on **Gradle 7.6+**, using the **old** API (don't bump the wrapper first)

## Run against a target project

Two paths. Pick by project size and type — the standalone runner is what you want unless your
target is small, with no pre-existing compile failures, and you already have the rewrite plugin
wired up elsewhere.

### Option 1 — Standalone runner (recommended)

The standalone runner bypasses Gradle's task graph entirely. It uses a tiny discovery init-script
to enumerate source dirs + build scripts (configuration-only, no compile cascade), then runs the
recipe directly via OpenRewrite's parsers. Works cleanly on projects that choke the plugin path
(Kotlin repo, any large multi-project build).

```bash
export RECIPE_REPO=/path/to/openrewrite-provider-api-migration
cd /path/to/your/project

# Dry run — prints list of files that would change
$RECIPE_REPO/run-migration.sh .

# Apply for real
$RECIPE_REPO/run-migration.sh . --apply
```

**No need to `publishToMavenLocal`.** The first invocation auto-builds the launcher distribution
via `./gradlew installDist` inside `$RECIPE_REPO`; subsequent runs reuse it.

What happens under the hood:
1. `discovery-init.gradle.kts` fires in `projectsEvaluated` and writes `.rewrite-manifest.json`
   (source sets + project roots + nested builds), then throws to abort before any task runs. ~5s.
2. `run-migration.sh` filters the manifest to the target kind's source-set dirs + individual
   `*.gradle[.kts]` scripts at project roots.
3. `StandaloneRunner` parses those files with OpenRewrite and runs the meta-recipe. Writes diffs
   in-place when `--apply` is passed.

No Gradle task execution. No cascade compile. Safe against broken sibling modules.

### Option 2 — rewrite-gradle-plugin (for small / medium projects)

If your target is not huge and has no pre-existing compile failures, the plugin path still works
and uses the task-graph-native `rewriteRun` task.

First publish the recipes to your Maven Local so the init script can resolve them:

```bash
cd $RECIPE_REPO
./gradlew publishToMavenLocal
# publishes org.gradle.rewrite:gradle-provider-api-migration:0.1.0-SNAPSHOT to ~/.m2/repository
```

Then, from the target project:

```bash
./gradlew --init-script $RECIPE_REPO/rewrite-init.gradle.kts \
    --no-configuration-cache \
    -Dorg.gradle.unsafe.isolated-projects=false \
    providerApiMigrate
```

The init script aggregates `rewriteRun` across the primary build and every included build under
one task called `providerApiMigrate`. Avoid on projects with broken compile tasks or >1000 source
files — that's when Option 1 pays off.

The diff for the plugin path lands in `build/reports/rewrite/rewrite.patch`. The standalone runner
prints the diff list to stdout and writes changes in-place with `--apply`.

## Project classification

The standalone runner classifies each subproject using Gradle's own buildscript-classpath data
(structural, not heuristic) and filters by kind:

- **`buildLogic`** — project's compiled output is declared directly on some script's buildscript
  classpath via `buildscript { dependencies { classpath project(":this") } }`. This IS the
  build's own plugin code (convention plugins, buildSrc-style helpers). **Migrated by default.**
- **`publishedGradlePlugin`** — applies `java-gradle-plugin` but is NOT consumed by any
  buildscript classpath in this build. Treated as a plugin the user ships to external consumers
  (`:kotlin-gradle-plugin`, `:kotlin-allopen`, etc. in the Kotlin monorepo). **Skipped by
  default** — changes to the public API of a published plugin can break downstream users on
  older Gradles. Opt in via `$RECIPE_REPO/run-migration.sh . --only-published-plugins` (mutually
  exclusive with the default — pick buildLogic OR published plugins per run, never both).
- **`production`** — neither of the above. Regular library / application code. Never migrated.

Nested builds (`buildSrc`, `includeBuild`, `pluginManagement.includeBuild`) are walked whole-dir
— the convention is that these directories contain only build-logic, so every file inside is in
scope regardless of the kind partitioning above.

## The 3-pass workflow

The recipes are designed to work iteratively, matching how real migrations actually proceed.
Commands below use the standalone runner (Option 1). For Option 2, substitute
`./gradlew --init-script $RECIPE_REPO/rewrite-init.gradle.kts providerApiMigrate`.

**Pass 1 — on your current Gradle wrapper**
```bash
$RECIPE_REPO/run-migration.sh . --apply
```
The catalog-driven recipes fire. Most `setX()` calls, import additions, and boolean renames get
rewritten. Your code doesn't compile yet against the old Gradle (that's expected — `getMaxParallelForks()`
on old Gradle returns `int`, not `Property<Integer>`), but it will after step 2.

**Pass 2 — bump the wrapper to the Provider API prototype distribution**

Edit `gradle/wrapper/gradle-wrapper.properties`:

```properties
distributionUrl=https\://services.gradle.org/distributions-snapshots/gradle-9.6.0-branch-gradle10ProviderApiMigration-<TIMESTAMP>-bin.zip
```

The `<TIMESTAMP>` rolls forward — grab the current snapshot from the
[distributions-snapshots index](https://services.gradle.org/distributions-snapshots/). Remove
any `distributionSha256Sum=` line since snapshots don't publish stable checksums. Then:

```bash
./gradlew assemble
```

See what's left. Most code compiles. Remaining errors tend to be:
- Chained eager access `prop.someMethod()` → need `.get()` (recipe can catch these in pass 3)
- `MapProperty.remove(k)` → need manual copy-mutate-set refactor (advisor flags these)
- Third-party plugin issues (not our code to rewrite)
- Settings-script compilation failures (block discovery in pass 3 — patch by hand first, see
  KNOWN-ISSUES.md)

**Pass 3 — rerun on the prototype wrapper for the Tier 2 pass**
```bash
$RECIPE_REPO/run-migration.sh . --apply
```
`InsertGetOnLazyAccess` and `InsertAsFileGet` now have the new types visible and can catch the
remaining patterns. Advisor markers show up for human-review sites.

## Inspect the output

- **Option 1 (standalone runner)**: writes changes in-place when `--apply` is passed and prints
  the list of modified files to stdout. Inspect via `git diff` in the target project. Without
  `--apply` the runner prints unified diffs (pass `--verbose` for full diffs).
- **Option 2 (rewrite-gradle-plugin)**: diff lands at `build/reports/rewrite/rewrite.patch`, and
  `rewriteRun` also writes changes in-place.

Advisor markers appear as inline block comments like:

```kotlin
/*~~(MapProperty does not support `remove`. Rewrite manually as a copy-mutate-set: ...)~~>*/
environment.remove("RUNNER_TEMP")
```

Search for `/*~~(` in your diff to find all of them.

## Known limitations

- **Published Gradle plugins are skipped by default.** Projects classified as
  `publishedGradlePlugin` (apply `java-gradle-plugin` but not consumed by any buildscript
  classpath in this build) are excluded because migrating their public API can break downstream
  users on older Gradles. Pass `--only-published-plugins` to target them deliberately (mutually
  exclusive with the default run — pick one kind per run).
- **Groovy DSL property assignments** (`maxParallelForks = 4` in `.gradle` scripts) are not yet
  rewritten. If your build uses Groovy DSL heavily, recipes will hit buildSrc Java but miss build
  scripts themselves. Tracked as task #5.
- **Implicit-this receivers inside Kotlin `!!` null-asserts** (e.g. `destinationDir!!.walkTopDown()`)
  are not caught by `InsertAsFileGet` yet. Fix-able but not done.
- **Third-party Gradle plugins** with their own Provider-API issues (JMH, Shadow, etc.) can't be
  rewritten by this module — they live in external jars. The recipes catch what's in your source tree.
- **Settings-script compilation failures after a Pass-2 wrapper bump** block Pass-3 discovery,
  because the init-script runs inside the target's `./gradlew`. See KNOWN-ISSUES.md for the
  chicken-and-egg and manual-patch workflow.

## Regenerating the catalog

The `MigratedPropertiesCatalog` class is auto-generated from Gradle's own `@ReplacesEagerProperty`
annotations. To refresh it against a newer Gradle branch:

```bash
python3 tools/extract_catalog.py /path/to/gradle-source \
    | python3 tools/catalog_to_java.py \
    > src/main/java/org/gradle/rewrite/providerapi/internal/MigratedPropertiesCatalog.java
./gradlew installDist               # rebuild the standalone runner (Option 1)
# ./gradlew publishToMavenLocal     # also needed if you use Option 2
```

The generator scans every `.java` file for `@ReplacesEagerProperty` on a getter, infers the kind
from the return type, and emits a catalog entry keyed by the declaring type's FQN.

Hand-curated additions (boolean renames, third-party type aliases) go in `MigratedProperties.java`
after the generated seed — the two layers compose additively.

## Troubleshooting

**`[run-migration] ERROR: discovery did not produce .rewrite-manifest.json`** *(Option 1)*
The discovery init-script failed before writing the manifest. Last 30 lines of Gradle output are
printed below the error. Most common causes:
- Wrong JDK for the target project's Gradle version (e.g. JDK 25 with Gradle 7.x → `error: 25.0.2`).
  Set `JAVA_HOME` to a compatible JDK and retry.
- Unresolvable plugins / deps in `settings.gradle[.kts]` or the root build script.
- Settings-script compilation failures after a Pass-2 wrapper bump — patch manually first, see
  KNOWN-ISSUES.md.

**"Extension of type 'RewriteExtension' does not exist"** *(Option 2)*
The init script tried to apply the plugin to a project that's not a root. Should only happen with
unusual nested-build setups. If you hit this, open an issue with the `settings.gradle[.kts]`.

**"Could not find org.gradle.rewrite:gradle-provider-api-migration"** *(Option 2)*
You forgot `./gradlew publishToMavenLocal` in this directory, or your target project's
`settings.gradle[.kts]` doesn't include `mavenLocal()`. The init script adds it automatically for
the `rewrite` configuration, so this shouldn't happen — but if you applied the plugin directly in
your `build.gradle[.kts]` instead, you'll need to add `mavenLocal()` to `repositories { }` too.
Not applicable to Option 1.

**"Recipe was expected to make a change but made no changes"** *(test output)*
The recipe's trigger didn't fire. If you're authoring a new catalog entry, check that the
declaring type FQN matches exactly (walk the supertype chain if the user code is a subclass).

**Build hangs on first run**
The first Pass-1 run parses every source file under buildLogic/publishedGradlePlugin dirs + all
`*.gradle[.kts]` scripts. On a large project this can take several minutes. Option 1 reuses the
OpenRewrite parse cache across invocations; Option 2 reuses it across `rewriteDryRun` /
`rewriteRun` task executions.

## Contributing

- **Tests**: `./gradlew test` — 41 tests, all green as of this writing. Most recipes have a hybrid-API
  test (stubs declare both the old setter and the new `Property` getter). `MigratePropertySetterOldClasspathTest`
  proves the catalog-driven trigger works against an eager-only classpath — add similar tests when
  you add a new catalog-driven recipe.
- **New recipe**: extend `JavaIsoVisitor` or `JavaVisitor`. For recipes that dispatch on the catalog,
  take a `MigratedProperties.Kind` parameter like `MigratePropertySetter.SetterToPropertyVisitor` does.
- **New catalog entry**: regenerate from Gradle source (preferred), or hand-add in
  `MigratedProperties.java` after the `MigratedPropertiesCatalog.populate(...)` call.

## Files of interest

```
src/main/java/org/gradle/rewrite/providerapi/
    MigratePropertySetter.java            # template for setter recipes
    InsertGetOnLazyAccess.java            # catalog-driven .get() insertion
    MigratePropertyMutations.java         # template for mutation + advisor recipes
    internal/
        MigratedProperties.java           # the catalog (+ hand-curated entries)
        MigratedPropertiesCatalog.java    # auto-generated from gradle2
        GradleBuildLogic.java             # autodiscovery of build-logic sources
    tools/
        StandaloneRunner.java             # main-method for run-migration.sh

src/main/resources/META-INF/rewrite/
    migrate-to-provider-api.yml           # meta-recipe composition order

tools/
    extract_catalog.py                    # scan gradle source → triples
    catalog_to_java.py                    # triples → Java source

discovery-init.gradle.kts                 # configuration-only manifest extractor
run-migration.sh                          # driver — discovery → filter → runner
rewrite-init.gradle.kts                   # plugin-path init script
```
