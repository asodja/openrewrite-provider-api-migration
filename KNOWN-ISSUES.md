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

## Infrastructure

### Nothing to carry over right now.

*(Add an entry here if a runner-level limitation — not a recipe precision
call — bites a real codebase.)*
