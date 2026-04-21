package org.gradle.rewrite.providerapi;

/**
 * Source-only stubs of the subset of the Gradle API surface that the recipes need to type-attribute against.
 *
 * <p>These are passed to {@code rewrite-test} as additional classpath sources via
 * {@code spec.parser(JavaParser.fromJavaVersion().dependsOn(STUBS))} so tests do not need the real Gradle
 * artifacts on the classpath. Only the signatures referenced by the recipes (or their tests) need to appear
 * here; method bodies can throw.
 */
public final class GradleApiStubs {

    public static final String PROVIDER = "" +
            "package org.gradle.api.provider;\n" +
            "public interface Provider<T> {\n" +
            "    T get();\n" +
            "    T getOrNull();\n" +
            "    T getOrElse(T defaultValue);\n" +
            "    boolean isPresent();\n" +
            "    <S> Provider<S> map(java.util.function.Function<? super T, ? extends S> f);\n" +
            "    <S> Provider<S> flatMap(java.util.function.Function<? super T, ? extends Provider<? extends S>> f);\n" +
            "    Provider<T> orElse(T defaultValue);\n" +
            "    Provider<T> orElse(Provider<? extends T> defaultValue);\n" +
            "}\n";

    public static final String HAS_CONFIGURABLE_VALUE = "" +
            "package org.gradle.api.provider;\n" +
            "public interface HasConfigurableValue {\n" +
            "    void finalizeValue();\n" +
            "    void finalizeValueOnRead();\n" +
            "    void disallowChanges();\n" +
            "}\n";

    public static final String PROPERTY = "" +
            "package org.gradle.api.provider;\n" +
            "public interface Property<T> extends Provider<T>, HasConfigurableValue {\n" +
            "    void set(T value);\n" +
            "    void set(Provider<? extends T> provider);\n" +
            "    Property<T> value(T value);\n" +
            "    Property<T> convention(T value);\n" +
            "}\n";

    public static final String HAS_MULTIPLE_VALUES = "" +
            "package org.gradle.api.provider;\n" +
            "public interface HasMultipleValues<T> extends HasConfigurableValue {\n" +
            "    void add(T value);\n" +
            "    void add(Provider<? extends T> provider);\n" +
            "    void addAll(T... values);\n" +
            "    void addAll(Iterable<? extends T> values);\n" +
            "    void addAll(Provider<? extends Iterable<? extends T>> provider);\n" +
            "    void empty();\n" +
            "}\n";

    public static final String LIST_PROPERTY = "" +
            "package org.gradle.api.provider;\n" +
            "public interface ListProperty<T> extends Provider<java.util.List<T>>, HasMultipleValues<T> {\n" +
            "    void set(Iterable<? extends T> elements);\n" +
            "    void set(Provider<? extends Iterable<? extends T>> provider);\n" +
            "}\n";

    public static final String SET_PROPERTY = "" +
            "package org.gradle.api.provider;\n" +
            "public interface SetProperty<T> extends Provider<java.util.Set<T>>, HasMultipleValues<T> {\n" +
            "    void set(Iterable<? extends T> elements);\n" +
            "    void set(Provider<? extends Iterable<? extends T>> provider);\n" +
            "}\n";

    public static final String MAP_PROPERTY = "" +
            "package org.gradle.api.provider;\n" +
            "public interface MapProperty<K, V> extends Provider<java.util.Map<K, V>>, HasConfigurableValue {\n" +
            "    void set(java.util.Map<? extends K, ? extends V> entries);\n" +
            "    void set(Provider<? extends java.util.Map<? extends K, ? extends V>> provider);\n" +
            "    void put(K key, V value);\n" +
            "    void put(K key, Provider<? extends V> provider);\n" +
            "    void putAll(java.util.Map<? extends K, ? extends V> entries);\n" +
            "    void putAll(Provider<? extends java.util.Map<? extends K, ? extends V>> provider);\n" +
            "    void empty();\n" +
            "}\n";

    public static final String FILE_COLLECTION = "" +
            "package org.gradle.api.file;\n" +
            "public interface FileCollection extends Iterable<java.io.File> {\n" +
            "    java.util.Set<java.io.File> getFiles();\n" +
            "    boolean isEmpty();\n" +
            "    FileCollection plus(FileCollection other);\n" +
            "}\n";

    public static final String CONFIGURABLE_FILE_COLLECTION = "" +
            "package org.gradle.api.file;\n" +
            "public interface ConfigurableFileCollection extends FileCollection {\n" +
            "    ConfigurableFileCollection from(Object... paths);\n" +
            "    void setFrom(Object... paths);\n" +
            "    void setFrom(Iterable<?> paths);\n" +
            "}\n";

    public static final String DIRECTORY = "" +
            "package org.gradle.api.file;\n" +
            "public interface Directory {\n" +
            "    java.io.File getAsFile();\n" +
            "    RegularFile file(String path);\n" +
            "    Directory dir(String path);\n" +
            "}\n";

    public static final String REGULAR_FILE = "" +
            "package org.gradle.api.file;\n" +
            "public interface RegularFile {\n" +
            "    java.io.File getAsFile();\n" +
            "}\n";

    public static final String DIRECTORY_PROPERTY = "" +
            "package org.gradle.api.file;\n" +
            "import org.gradle.api.provider.Provider;\n" +
            "public interface DirectoryProperty extends org.gradle.api.provider.Property<Directory> {\n" +
            "    java.io.File getAsFile();\n" +
            "    Provider<java.io.File> getAsFileProvider();\n" +
            "    DirectoryProperty fileValue(java.io.File file);\n" +
            "    Provider<RegularFile> file(String path);\n" +
            "    Provider<Directory> dir(String path);\n" +
            "}\n";

    public static final String REGULAR_FILE_PROPERTY = "" +
            "package org.gradle.api.file;\n" +
            "import org.gradle.api.provider.Provider;\n" +
            "public interface RegularFileProperty extends org.gradle.api.provider.Property<RegularFile> {\n" +
            "    java.io.File getAsFile();\n" +
            "    Provider<java.io.File> getAsFileProvider();\n" +
            "    RegularFileProperty fileValue(java.io.File file);\n" +
            "}\n";

    public static final String TEST_TASK = "" +
            "package org.gradle.api.tasks.testing;\n" +
            "public abstract class Test {\n" +
            "    public abstract org.gradle.api.provider.Property<Integer> getMaxParallelForks();\n" +
            "    public abstract void setMaxParallelForks(int value);\n" +
            "    public abstract org.gradle.api.provider.Property<Boolean> getFailOnNoMatchingTests();\n" +
            "    public abstract void setFailOnNoMatchingTests(boolean value);\n" +
            "    public abstract org.gradle.api.provider.Property<Boolean> getIgnoreExitValue();\n" +
            "    public abstract void setIgnoreExitValue(boolean value);\n" +
            "    public abstract org.gradle.api.provider.Property<String> getMaxMemory();\n" +
            "    public abstract void setMaxMemory(String value);\n" +
            "    public abstract org.gradle.api.provider.ListProperty<String> getJvmArgs();\n" +
            "    public abstract void setJvmArgs(java.util.List<String> value);\n" +
            "    public abstract org.gradle.api.provider.MapProperty<String, Object> getSystemProperties();\n" +
            "    public abstract void setSystemProperties(java.util.Map<String, Object> value);\n" +
            "    public abstract org.gradle.api.file.ConfigurableFileCollection getClasspath();\n" +
            "    public abstract void setClasspath(org.gradle.api.file.FileCollection value);\n" +
            "    public abstract org.gradle.api.file.ConfigurableFileCollection getTestClassesDirs();\n" +
            "    public abstract void setTestClassesDirs(org.gradle.api.file.FileCollection value);\n" +
            "    public abstract org.gradle.api.file.DirectoryProperty getWorkingDir();\n" +
            "    public abstract void setWorkingDir(java.io.File value);\n" +
            "}\n";

    public static final String JAVA_COMPILE_TASK = "" +
            "package org.gradle.api.tasks.compile;\n" +
            "public abstract class JavaCompile {\n" +
            "    public abstract CompileOptions getOptions();\n" +
            "}\n";

    public static final String COMPILE_OPTIONS = "" +
            "package org.gradle.api.tasks.compile;\n" +
            "public abstract class CompileOptions {\n" +
            "    public abstract org.gradle.api.provider.Property<String> getEncoding();\n" +
            "    public abstract void setEncoding(String value);\n" +
            "    public abstract org.gradle.api.provider.ListProperty<String> getCompilerArgs();\n" +
            "    public abstract void setCompilerArgs(java.util.List<String> value);\n" +
            "}\n";

    public static final String EXEC_SPEC = "" +
            "package org.gradle.process;\n" +
            "public interface ExecSpec {\n" +
            "    org.gradle.api.provider.Property<Boolean> getIgnoreExitValue();\n" +
            "    void setIgnoreExitValue(boolean value);\n" +
            "    org.gradle.api.provider.Property<java.io.OutputStream> getStandardOutput();\n" +
            "    void setStandardOutput(java.io.OutputStream out);\n" +
            "    org.gradle.api.provider.Property<java.io.OutputStream> getErrorOutput();\n" +
            "    void setErrorOutput(java.io.OutputStream out);\n" +
            "    org.gradle.api.provider.ListProperty<String> getCommandLine();\n" +
            "    void setCommandLine(Iterable<?> args);\n" +
            "    void commandLine(String... args);\n" +
            "    void commandLine(Iterable<?> args);\n" +
            "    org.gradle.api.file.DirectoryProperty getWorkingDir();\n" +
            "    void setWorkingDir(java.io.File dir);\n" +
            "}\n";

    public static final String DELETE_TASK = "" +
            "package org.gradle.api.tasks;\n" +
            "public abstract class Delete {\n" +
            "    public abstract org.gradle.api.file.ConfigurableFileCollection getTargetFiles();\n" +
            "}\n";

    public static final String SOURCE_TASK = "" +
            "package org.gradle.api.tasks;\n" +
            "public abstract class SourceTask {\n" +
            "    public abstract org.gradle.api.file.ConfigurableFileCollection getSource();\n" +
            "    public abstract void setSource(Object source);\n" +
            "}\n";

    public static final String[] ALL = new String[]{
            PROVIDER,
            HAS_CONFIGURABLE_VALUE,
            PROPERTY,
            HAS_MULTIPLE_VALUES,
            LIST_PROPERTY,
            SET_PROPERTY,
            MAP_PROPERTY,
            FILE_COLLECTION,
            CONFIGURABLE_FILE_COLLECTION,
            DIRECTORY,
            REGULAR_FILE,
            DIRECTORY_PROPERTY,
            REGULAR_FILE_PROPERTY,
            TEST_TASK,
            JAVA_COMPILE_TASK,
            COMPILE_OPTIONS,
            EXEC_SPEC,
            DELETE_TASK,
            SOURCE_TASK,
    };

    private GradleApiStubs() {}
}
