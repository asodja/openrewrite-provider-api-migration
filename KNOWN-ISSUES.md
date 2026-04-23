# Known Issues

Problems we've hit on real codebases (junit-framework, spring-framework,
gradle/gradle) that users of these recipes may encounter too. Organized by
whether the cause is upstream (OpenRewrite), in our own recipes (as a precision
/ recall trade-off we've deliberately made), or a limitation of the runner
infrastructure.

Each entry lists what the user will see, what the root cause is, whether there
is a workaround, and — where applicable — what it would take to properly fix.

---

## Upstream (OpenRewrite)

### rewrite-java 8.80.0 — Javadoc print-idempotency bug

**Symptom.** In dry-run you see Javadoc comments mangled in the diff:
whitespace around `{@code}` inline tags shifts (`* {@code X}` → `*  {@code X}`),
quoted string literals in comments gain trailing characters, multi-line
`@param` blocks collapse into a single line with ghost characters (e.g.
`projectoject`).

**Root cause.** rewrite-java 8.80.0's Javadoc AST → source reprint is not
idempotent. With the parser's `requirePrintEqualsInput` check enabled,
affected files are silently demoted to `ParseError` and every recipe skips
them — so the user loses real migrations too.

**Workaround.** The standalone runner disables the idempotency check (so
recipes fire and code gets migrated) and reports which files had
non-idempotent parses at the end of the run, like:

```
[rewrite-runner] WARN: 2 file(s) had non-idempotent parses
  (rewrite-java 8.80.0 Javadoc bug). Review Javadoc changes in these files
  before committing:
    ...buildSrc/src/main/java/org/springframework/build/JavaConventions.java
```

Review those files' Javadoc blocks before committing. Code changes are
correct — only the Javadoc text around the code may be corrupted.

**Fix.** Upstream, in a future rewrite-java release. No tracking issue found
at time of writing; feel free to file one against openrewrite/rewrite.

---

### rewrite-kotlin — no type attribution on implicit receivers in `.gradle.kts`

**Symptom.** Rewrites that depend on resolving a receiver's type don't fire
on patterns like

```kotlin
tasks.withType<Test> { setMaxMemory("1g") }
tasks.register<Javadoc>("x") { ... }
tasks.registering(Javadoc::class) { setMaxMemory("1g") }
tasks.javadoc { doLast { destinationDir!!.walkTopDown() } }
```

because `J.MethodInvocation.getMethodType()` is `null` for the inner
invocations (`setMaxMemory`, `destinationDir`, etc.).

**Root cause.** Tracked upstream as
[openrewrite/rewrite#6312](https://github.com/openrewrite/rewrite/issues/6312).
rewrite-kotlin doesn't propagate reified-generic receivers into nested
lambdas, so implicit-`this` invocations come back with null types.
Maintainer quote: *"the nature of .kts files and their incremental
compilation, combined with some APIs not exposed in Gradle mean we for now
have to deal with missing type information."*

**Workaround.** Our `KotlinDslScope` helper walks the cursor up from a bare
identifier and extracts the receiver type by source-pattern-matching the
enclosing DSL invocation (`withType<T>`, `register<T>`, `named<T>`,
`registering(T::class)`, `configure<T>`, and a hardcoded map of
`tasks.<builtin>` accessors). Covers the common shapes and unlocks most
cases that would otherwise be silently skipped.

The workaround relies on the user actually using the typed DSL. Receiver
inference in patterns our heuristic doesn't know about still fails — see
the next entry.

**Fix.** Requires changes in rewrite-kotlin or Gradle exposing extra model
data for script compilation. Out of scope for this project.

---

## Recipe-level (our trade-offs)

### `testTask.configure { ... }` is not recognized as a typed scope

**Symptom.** In a file like junit-framework's
`platform-tooling-support-tests.gradle.kts`, this site:

```kotlin
testTask.configure {
    environment.remove("JAVA_TOOL_OPTIONS")   // environment is MapProperty
}
```

does NOT mechanically rewrite. It gets a candidate-tier TODO advisor
instead, with the "receiver type could not be verified" note.

**Root cause.** `testTask` is a `TaskProvider<Test>`; `.configure(Action<Test>)`
implies the inner lambda's receiver is `Test`. Our `KotlinDslScope` walker
only handles invocations where the type argument is EXPLICIT
(`withType<T>`, `register<T>`, `tasks.<builtin>`, etc.) — it doesn't track
the generic parameter of a stored `TaskProvider<T>` through the
`.configure { }` call.

**Workaround.** Move the mutation inside an explicitly-typed DSL scope:

```kotlin
tasks.withType<Test>().named("...").configure {
    environment.remove("JAVA_TOOL_OPTIONS")
}
```

or apply the TODO's copy-mutate-set replacement by hand.

**Fix.** Extend `KotlinDslScope` to walk up past `TaskProvider<T>` /
`NamedDomainObjectProvider<T>` chains and extract `T` from their generic
parameters. Requires type attribution on the provider chain (see the
rewrite-kotlin issue above), which is often also missing in `.gradle.kts`.

---

### `AddKotlinAssignImport` false-positive: named function arguments

**Symptom.** An `import org.gradle.kotlin.dsl.assign` is added to a Kotlin
file that has no actual `Property<T>` assignment. First seen in gradle/gradle's
`build-logic/kotlin-dsl-shared-runtime/.../ApiTypeProvider.kt`, where the
file compiles without `gradle-kotlin-dsl` on its classpath — so the added
import becomes an unresolved reference.

**Root cause.** Two bugs stacked:

1. **Named-argument parse shape** — Kotlin named function arguments
   (`foo(name = value)`) are modeled as `J.Assignment` in the LST, same
   as real assignments. Our visitor fires on both without distinguishing.
2. **Name-only catalog fallback** — when the LHS identifier is bare
   (implicit `this`), we consult `MigratedProperties.isKnownPropertyName`.
   Common names like `type` match cataloged entries (e.g.
   `IvyArtifact.type`) even when the code isn't touching a Gradle property
   at all.

The ApiTypeProvider case: line 420 `type = apiTypeUsageFor(...)` — a named
argument to a constructor call. Matched via `type` → IvyArtifact.type.

**Workaround.** If `build.gradle.kts` / the diff surfaces an
`import org.gradle.kotlin.dsl.assign` in a file whose classpath doesn't
include `gradle-kotlin-dsl`, delete the import by hand. You'll also see a
compile error pointing directly at it.

**Fix.** Two directions worth addressing together:

1. Walk the cursor up in the visitor; if the `J.Assignment` is inside a
   `J.MethodInvocation.getArguments()` container, skip — it's a named
   argument, not an assignment.
2. Drop the catalog-wide name-only fallback from
   `AddKotlinAssignImport.shouldAddImport`, matching the precision stance
   already applied to the setter and collection-mutation recipes
   (commits `a596f12`, `c7326e2`).

Estimated cost: ~1 hour.

---

### Candidate-tier rewrites are skipped when the receiver is a third-party field

**Symptom.** A site like junit-framework's
`junitbuild.shadow-conventions.gradle.kts`:

```kotlin
shadowJar {
    excludes.remove("module-info.class")
}
```

gets a TODO advisor (not a mechanical rewrite). Applying the suggested
internal-API replacement would fail at runtime with:

```
class java.util.LinkedHashSet cannot be cast to class
org.gradle.api.internal.provider.DefaultListProperty
```

**Root cause.** `excludes` is cataloged as `JacocoTaskExtension.excludes`
(LIST_PROPERTY). Shadow plugin's `shadowJar.excludes` is a plain
`Set<String>` — same name, different class. The recipe can't tell them
apart without typed-scope context, so it emits a TODO advisor flagging the
site as "receiver type could not be verified."

**Workaround.** Review the TODO. If the receiver is really a
Map/List/SetProperty, apply the suggested replacement. If it's a plain
collection on a third-party DSL type (like Shadow), delete the TODO and
leave the code alone.

**Fix.** Structural — would need either type attribution on Shadow's DSL
types (same upstream issue) or a per-type allow-list of "known
non-Gradle" collisions. Both add complexity for marginal benefit.

---

### `InsertAsFileGet` emits Kotlin `.asFile` in Java files

**Symptom.** After migration, a Java file has:

```java
test.getWorkingDir().get().asFile.toPath().resolve("temp")
                       // ^^^^^^ compile error: cannot find symbol
```

**Root cause.** `InsertAsFileGet` emits `J.FieldAccess` with name `asFile`,
which is valid Kotlin syntax (where `.asFile` is a property accessor) but
not Java — `Directory.asFile` doesn't exist as a field; the Java accessor
is `getAsFile()`.

**Workaround.** Hand-edit `.asFile` → `.getAsFile()` in Java files. Easy
to grep for.

**Fix.** In `InsertAsFileGet.wrapWithGetAsFile`, dispatch on
`GradleBuildLogic.isKotlin(sourceFile)` — emit the existing J.FieldAccess
for Kotlin, emit a zero-arg J.MethodInvocation (`getAsFile()`) for Java.
~30 min.

---

### Missing recipes for `DirectoryProperty` / `RegularFileProperty` setters

**Symptom.** `test.setWorkingDir(File)` / `spec.setWorkingDir(File)`
survive untouched and fail to compile against migrated Gradle with
`cannot find symbol — method setWorkingDir(File)`.

**Root cause.** `MigratePropertySetter` and its siblings cover
`SCALAR_PROPERTY`, `LIST_PROPERTY`, `SET_PROPERTY`, `MAP_PROPERTY`, and
`CONFIGURABLE_FILE_COLLECTION`. No recipe filters on
`DIRECTORY_PROPERTY` or `REGULAR_FILE_PROPERTY`.

**Workaround.** Hand-rewrite `x.setFoo(v)` → `x.getFoo().set(v)` for any
cataloged directory/file property setter. Catalog entries with kind
`DIRECTORY_PROPERTY` / `REGULAR_FILE_PROPERTY`:
see `grep -nE 'directory\(|regularFile\(' MigratedPropertiesCatalog.java`.

**Fix.** Two new visitors reusing `SetterToPropertyVisitor` with the
target kind filter set to DIRECTORY_PROPERTY / REGULAR_FILE_PROPERTY.
~1 hr.

---

### Java `MigratePropertyMutations` leaves broken code

**Symptom.** A Java file after migration has a multi-line `TODO: ...`
comment immediately followed by the original call that no longer
compiles:

```java
/*
 * TODO: MapProperty does not support `putIfAbsent(...)`.
 *     ...
 */
test.getSystemProperties().putIfAbsent("K", "V");   // compile error
```

**Root cause.** For Kotlin, `MigratePropertyMutations` emits a mechanical
`(x as DefaultMapProperty<...>).replace { ... }` via KotlinTemplate. For
Java, no equivalent mechanical rewrite — we attach an advisor TODO but
keep the original call, which doesn't compile.

**Workaround.** Hand-edit to the copy-mutate-set pattern that the TODO
comment suggests:

```java
Map<String, Object> updated = new HashMap<>(test.getSystemProperties().get());
updated.putIfAbsent("K", "V");
test.getSystemProperties().set(updated);
```

**Fix.** For `.java` sources, emit the three-statement copy-mutate-set
replacement instead of (or in addition to) the TODO. Involves replacing
a single-statement expression with multiple statements — harder
splice mechanics, but doable via JavaTemplate. ~1-2 hrs.

---

### URL setters emit type-incompatible `.set(String/File)`

**Symptom.** After migration:

```java
repo.getUrl().set("https://...");        // compile error: no suitable method found for set(String)
repo.getUrl().set(new File(...));        // same, but for File
```

**Root cause.** `MavenArtifactRepository.url` and
`IvyArtifactRepository.url` changed VALUE type from `Object` to `URI`.
The catalog knows they're scalar properties; the recipe correctly
rewrites `setUrl(x)` → `getUrl().set(x)`. But `x` is still the old type.
`Property<URI>.set(String)` doesn't exist.

**Workaround.** Wrap the argument by hand: `URI.create(x)` for String,
`file.toURI()` for File.

**Fix.** Add a per-property value-type hint to the catalog — e.g. a
fourth catalog column `valueType: URI`. When the recipe sees a mismatch
between the arg's static type and the declared value type, wrap via
known converters (`URI.create`, `.toURI()`, etc.). ~2-3 hrs; needs a new
catalog schema bump.

---

### `WriteProperties.properties(map)` Groovy method-form not migrated

**Symptom.** Build script:

```groovy
tasks.register("generateVersionProperties", WriteProperties) {
    properties(versions)   // now deprecated with "The WriteProperties.properties(Map<String, Object>)
                           //   method has been deprecated..."
}
```

**Root cause.** Catalog has `WriteProperties.properties` as MAP_PROPERTY.
Our setter recipes match the Java-bean shape `setX(v)` or the Kotlin-DSL
assign shape `x = v`, not the Groovy method-form `x(v)` where `x` is the
property name.

**Workaround.** Hand-rewrite `properties(versions)` →
`getProperties().set(versions)`.

**Fix.** Groovy recipe that matches `<receiver>.<propName>(<args>)` where
`<propName>` is cataloged and the receiver's type agrees — rewrite to
`<receiver>.get<PropName>().set(<args>)`. Careful with false positives
(any `x.foo(y)` call could match syntactically). ~1 hr.

---

## Infrastructure

### Discovery fails with "25.0.2" (or similar bare version number)

**Symptom.** Running `./run-migration.sh <target>` exits early with:

```
[run-migration] ERROR: discovery did not produce .rewrite-manifest.json
[run-migration] last 30 lines of Gradle output:
    FAILURE: Build failed with an exception.
    * What went wrong:
    25.0.2
```

**Root cause.** JDK / Gradle version mismatch. Gradle 7.x doesn't run on
JDK 17+; Gradle 8.0–8.4 doesn't run on JDK 21+; Gradle 8.5+ on JDK 21; etc.
The bare version number in the error message is the JDK that can't be
loaded. Seen on elasticsearch (Gradle 7.2) with JDK 25.

**Workaround.** Point `JAVA_HOME` at a compatible JDK before running:

```
JAVA_HOME=/path/to/jdk-16 ./run-migration.sh <target>
```

Mapping:
- Gradle 7.0–7.2 → JDK 8–16
- Gradle 7.3–7.5 → JDK 8–17
- Gradle 8.0–8.4 → JDK 8–20
- Gradle 8.5+    → JDK 8–21

**Fix.** Runner could use `./gradlew -Dorg.gradle.java.home=...` to bypass
`JAVA_HOME`, but then the user has to know which JDK to point at. The
current error surfacing is already pretty direct.
