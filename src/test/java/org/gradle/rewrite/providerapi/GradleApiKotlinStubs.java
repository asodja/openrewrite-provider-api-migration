package org.gradle.rewrite.providerapi;

/**
 * Minimal Kotlin-source stubs of the Gradle API surface needed by rewrite-kotlin tests.
 *
 * <p>rewrite-kotlin's {@code KotlinParser.Builder.dependsOn(String...)} accepts Kotlin source, not Java —
 * so {@link GradleApiStubs} can't be reused here. These stubs mirror the hybrid Gradle API: Kotlin-visible
 * {@code isX} accessors for boolean getters, plus the new {@code Property<T>}-returning getters so the
 * rename recipe has something to match against.
 */
public final class GradleApiKotlinStubs {

    public static final String PROVIDER =
            "package org.gradle.api.provider\n" +
            "interface Provider<T> {\n" +
            "    fun get(): T\n" +
            "    fun getOrNull(): T?\n" +
            "    fun isPresent(): Boolean\n" +
            "}\n";

    public static final String HAS_CONFIGURABLE_VALUE =
            "package org.gradle.api.provider\n" +
            "interface HasConfigurableValue {\n" +
            "    fun finalizeValue()\n" +
            "    fun disallowChanges()\n" +
            "}\n";

    public static final String PROPERTY =
            "package org.gradle.api.provider\n" +
            "interface Property<T> : Provider<T>, HasConfigurableValue {\n" +
            "    fun set(value: T?)\n" +
            "    fun set(provider: Provider<out T>?)\n" +
            "    fun convention(value: T?): Property<T>\n" +
            "}\n";

    public static final String LIST_PROPERTY =
            "package org.gradle.api.provider\n" +
            "interface ListProperty<T> : Provider<List<T>>, HasMultipleValues<T> {\n" +
            "    fun set(elements: Iterable<T>?)\n" +
            "}\n";

    public static final String MAP_PROPERTY =
            "package org.gradle.api.provider\n" +
            "interface MapProperty<K, V> : Provider<Map<K, V>>, HasConfigurableValue {\n" +
            "    fun set(entries: Map<out K, V>?)\n" +
            "    fun put(key: K, value: V)\n" +
            "}\n";

    public static final String CONFIGURABLE_FILE_COLLECTION =
            "package org.gradle.api.file\n" +
            "interface FileCollection : Iterable<java.io.File>\n" +
            "interface ConfigurableFileCollection : FileCollection {\n" +
            "    fun from(vararg paths: Any?): ConfigurableFileCollection\n" +
            "    fun setFrom(vararg paths: Any?)\n" +
            "}\n";

    public static final String TEST_TASK =
            "package org.gradle.api.tasks.testing\n" +
            "import org.gradle.api.provider.Property\n" +
            "abstract class Test {\n" +
            "    abstract fun getEnabled(): Property<Boolean>\n" +
            "    abstract fun setEnabled(value: Boolean)\n" +
            "    abstract fun getFailOnNoMatchingTests(): Property<Boolean>\n" +
            "    abstract fun setFailOnNoMatchingTests(value: Boolean)\n" +
            "    abstract fun getIgnoreFailures(): Property<Boolean>\n" +
            "    abstract fun setIgnoreFailures(value: Boolean)\n" +
            "    abstract fun getMaxParallelForks(): Property<Int>\n" +
            "    abstract fun setMaxParallelForks(value: Int)\n" +
            "}\n";

    public static final String EXEC_SPEC =
            "package org.gradle.process\n" +
            "import org.gradle.api.provider.Property\n" +
            "interface ExecSpec {\n" +
            "    fun getIgnoreExitValue(): Property<Boolean>\n" +
            "    fun setIgnoreExitValue(value: Boolean)\n" +
            "}\n";

    public static final String KOTLIN_DSL_ASSIGN =
            "package org.gradle.kotlin.dsl\n" +
            "import org.gradle.api.provider.Property\n" +
            "import org.gradle.api.provider.HasMultipleValues\n" +
            "operator fun <T> Property<T>.assign(value: T) { this.set(value) }\n" +
            "operator fun <T> HasMultipleValues<T>.plusAssign(value: T) { this.add(value) }\n";

    public static final String HAS_MULTIPLE_VALUES =
            "package org.gradle.api.provider\n" +
            "interface HasMultipleValues<T> : HasConfigurableValue {\n" +
            "    fun add(value: T)\n" +
            "    fun addAll(vararg values: T)\n" +
            "}\n";

    public static final String JAR_TASK =
            "package org.gradle.api.tasks.bundling\n" +
            "import org.gradle.api.provider.Property\n" +
            "abstract class AbstractArchiveTask {\n" +
            "    abstract fun getPreserveFileTimestamps(): Property<Boolean>\n" +
            "    abstract fun setPreserveFileTimestamps(value: Boolean)\n" +
            "    abstract fun getReproducibleFileOrder(): Property<Boolean>\n" +
            "    abstract fun setReproducibleFileOrder(value: Boolean)\n" +
            "    abstract fun getZip64(): Property<Boolean>\n" +
            "    abstract fun setZip64(value: Boolean)\n" +
            "}\n" +
            "abstract class Jar : AbstractArchiveTask()\n";

    public static final String[] ALL = new String[] {
            PROVIDER,
            HAS_CONFIGURABLE_VALUE,
            HAS_MULTIPLE_VALUES,
            PROPERTY,
            LIST_PROPERTY,
            MAP_PROPERTY,
            CONFIGURABLE_FILE_COLLECTION,
            TEST_TASK,
            EXEC_SPEC,
            JAR_TASK,
            KOTLIN_DSL_ASSIGN,
    };

    private GradleApiKotlinStubs() {}
}
