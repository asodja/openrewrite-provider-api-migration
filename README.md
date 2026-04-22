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
| Chained eager access → `.get()` inserted | `InsertGetOnLazyAccess` | `task.maxMemory.endsWith(...)` |
| `dirProp.walkTopDown()` → `dirProp.get().asFile.walkTopDown()` | `InsertAsFileGet` | |
| Flag self-referencing CFC patterns | `DetectSelfReferencingFileCollection` | Attaches SearchResult marker |
| Flag overrides of removed setters | `DetectSetterOverride` | Attaches SearchResult marker |
| Flag unsupported `MapProperty` mutations (`remove`, `filterKeys`, ...) | `FlagMapPropertyMutations` | Attaches SearchResult marker |

All 18 recipes are composed into one meta-recipe: `org.gradle.rewrite.providerapi.MigrateToProviderApi`.

## Prerequisites

- JDK 17+ (Gradle toolchains auto-downloads if you use the foojay plugin)
- Your target project on **Gradle 7.6+**, using the **old** API (don't bump the wrapper first)

## Install the recipes locally

From this directory:

```bash
./gradlew publishToMavenLocal
```

Publishes `org.gradle.rewrite:gradle-provider-api-migration:0.1.0-SNAPSHOT` to `~/.m2/repository`.

## Run against a target project

Two ways, both drop-in — no edits to the target project.

### Option 1 — Standalone runner (recommended)

The standalone runner bypasses Gradle's task graph entirely. It uses a tiny discovery init-script
to enumerate source dirs + build scripts (configuration-only, no compile cascade), then runs the
recipe directly via OpenRewrite's parsers. Works cleanly on projects that choke the plugin path
(Kotlin repo, any large multi-project build).

```bash
export RECIPE_REPO=/Users/asodja/workspace/openrewrite-provider-api-migration
cd /path/to/your/project

# Dry run — prints list of files that would change
$RECIPE_REPO/run-migration.sh .

# Apply for real
$RECIPE_REPO/run-migration.sh . --apply
```

What happens under the hood:
1. `discovery-init.gradle.kts` fires in `projectsEvaluated` and writes `.rewrite-manifest.json`
   (source sets + project roots + nested builds), then throws to abort before any task runs. ~5s.
2. `run-migration.sh` filters the manifest to build-logic dirs + individual `*.gradle[.kts]`
   scripts at project roots.
3. `StandaloneRunner` (the main class packaged by `./gradlew installDist`) parses those files
   with OpenRewrite and runs the meta-recipe. Writes diffs in-place when `--apply` is passed.

No Gradle task execution. No cascade compile. Safe against broken sibling modules.

### Option 2 — rewrite-gradle-plugin (for small / medium projects)

If your target is not huge and has no pre-existing compile failures, the plugin path still works
and uses the task-graph-native `rewriteRun` task:

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

## The 3-pass workflow

The recipes are designed to work iteratively, matching how real migrations actually proceed.

**Pass 1 — on your current Gradle wrapper**
```bash
./gradlew --init-script $RECIPE_REPO/rewrite-init.gradle.kts rewriteRun
```
The catalog-driven recipes fire. Most `setX()` calls, import additions, and boolean renames get
rewritten. Your code doesn't compile yet against the old Gradle (that's expected — `getMaxParallelForks()`
on old Gradle returns `int`, not `Property<Integer>`), but it will after step 2.

**Pass 2 — bump the wrapper**

Edit `gradle/wrapper/gradle-wrapper.properties` to the new Gradle distribution.

```bash
./gradlew assemble
```

See what's left. Most code compiles. Remaining errors tend to be:
- Chained eager access `prop.someMethod()` → need `.get()` (recipe can catch these in pass 3)
- `MapProperty.remove(k)` → need manual copy-mutate-set refactor (advisor flags these)
- Third-party plugin issues (not our code to rewrite)

**Pass 3 — rerun on new Gradle for the Tier 2 pass**
```bash
./gradlew --init-script $RECIPE_REPO/rewrite-init.gradle.kts rewriteRun
```
`InsertGetOnLazyAccess` and `InsertAsFileGet` now have the new types visible and can catch the
remaining patterns. Advisor markers show up for human-review sites.

## Inspect the output

The diff is at `build/reports/rewrite/rewrite.patch`. You can also `git diff` directly since
`rewriteRun` writes changes in-place.

Advisor markers appear as inline block comments like:

```kotlin
/*~~(MapProperty does not support `remove`. Rewrite manually as a copy-mutate-set: ...)~~>*/
environment.remove("RUNNER_TEMP")
```

Search for `/*~~(` in your diff to find all of them.

## Known limitations

- **Groovy DSL property assignments** (`maxParallelForks = 4` in `.gradle` scripts) are not yet
  rewritten. If your build uses Groovy DSL heavily, recipes will hit buildSrc Java but miss build
  scripts themselves. Tracked as task #5.
- **Implicit-this receivers inside Kotlin `!!` null-asserts** (e.g. `destinationDir!!.walkTopDown()`)
  are not caught by `InsertAsFileGet` yet. Fix-able but not done.
- **Third-party Gradle plugins** with their own Provider-API issues (JMH, Shadow, etc.) can't be
  rewritten by this module — they live in external jars. The recipes catch what's in your source tree.

## Regenerating the catalog

The `MigratedPropertiesCatalog` class is auto-generated from Gradle's own `@ReplacesEagerProperty`
annotations. To refresh it against a newer Gradle branch:

```bash
python3 tools/extract_catalog.py /path/to/gradle-source \
    | python3 tools/catalog_to_java.py \
    > src/main/java/org/gradle/rewrite/providerapi/internal/MigratedPropertiesCatalog.java
./gradlew publishToMavenLocal
```

The generator scans every `.java` file for `@ReplacesEagerProperty` on a getter, infers the kind
from the return type, and emits a catalog entry keyed by the declaring type's FQN.

Hand-curated additions (boolean renames, third-party type aliases) go in `MigratedProperties.java`
after the generated seed — the two layers compose additively.

## Troubleshooting

**"Extension of type 'RewriteExtension' does not exist"**
The init script tried to apply the plugin to a project that's not a root. Should only happen with
unusual nested-build setups. If you hit this, open an issue with the `settings.gradle[.kts]`.

**"Could not find org.gradle.rewrite:gradle-provider-api-migration"**
You forgot `./gradlew publishToMavenLocal` in this directory, or your target project's
`settings.gradle[.kts]` doesn't include `mavenLocal()`. The init script adds it automatically for
the `rewrite` configuration, so this shouldn't happen — but if you applied the plugin directly in
your `build.gradle[.kts]` instead, you'll need to add `mavenLocal()` to `repositories { }` too.

**"Recipe was expected to make a change but made no changes"**
This appears in test output — it means the recipe's trigger didn't fire. If you're authoring a
new catalog entry, check that the declaring type FQN matches exactly (walk the supertype chain if
the user code is a subclass).

**Build hangs on first run**
The first `rewriteDryRun` / `rewriteRun` parses every source file. On a large project this can
take several minutes. Subsequent runs reuse the OpenRewrite parse cache.

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
    FlagMapPropertyMutations.java         # template for advisor recipes
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
