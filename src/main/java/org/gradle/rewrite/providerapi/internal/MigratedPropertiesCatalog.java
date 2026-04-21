package org.gradle.rewrite.providerapi.internal;

import java.util.Map;

/**
 * Auto-generated catalog of Gradle properties migrated to the Provider API.
 *
 * <p>Generated from the {@code gradle10/provider-api-migration} branch by scanning
 * {@code @ReplacesEagerProperty} annotations. Regenerate with
 * {@code tools/extract_catalog.py | tools/catalog_to_java.py}.
 *
 * <p>DO NOT EDIT BY HAND — hand-curated additions go in {@link MigratedProperties} directly.
 */
final class MigratedPropertiesCatalog {

    static void populate(CatalogSink sink) {
        sink.put("org.gradle.api.artifacts.repositories.MavenArtifactRepository", sink.setLike("artifactUrls"));
        sink.put("org.gradle.api.artifacts.repositories.UrlArtifactRepository", sink.scalar("allowInsecureProtocol", "url"));
        sink.put("org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter", sink.setLike("commandLineIncludePatterns"));
        sink.put("org.gradle.api.plugins.JavaApplication", sink.listLike("applicationDefaultJvmArgs"));
        sink.put("org.gradle.api.plugins.JavaApplication", sink.scalar("applicationName", "executableDir"));
        sink.put("org.gradle.api.plugins.antlr.AntlrTask", sink.configurableFileCollection("antlrClasspath"));
        sink.put("org.gradle.api.plugins.antlr.AntlrTask", sink.directory("outputDirectory"));
        sink.put("org.gradle.api.plugins.antlr.AntlrTask", sink.listLike("arguments"));
        sink.put("org.gradle.api.plugins.antlr.AntlrTask", sink.scalar("maxHeapSize", "trace", "traceLexer", "traceParser", "traceTreeWalker"));
        sink.put("org.gradle.api.plugins.quality.Checkstyle", sink.configurableFileCollection("checkstyleClasspath", "classpath"));
        sink.put("org.gradle.api.plugins.quality.Checkstyle", sink.mapLike("configProperties"));
        sink.put("org.gradle.api.plugins.quality.Checkstyle", sink.scalar("isIgnoreFailures", "maxErrors", "maxWarnings", "showViolations"));
        sink.put("org.gradle.api.plugins.quality.CheckstyleExtension", sink.mapLike("configProperties"));
        sink.put("org.gradle.api.plugins.quality.CheckstyleExtension", sink.scalar("maxErrors", "maxWarnings", "showViolations"));
        sink.put("org.gradle.api.plugins.quality.CodeNarc", sink.configurableFileCollection("codenarcClasspath", "compilationClasspath"));
        sink.put("org.gradle.api.plugins.quality.CodeNarc", sink.scalar("maxPriority1Violations", "maxPriority2Violations", "maxPriority3Violations"));
        sink.put("org.gradle.api.plugins.quality.CodeNarcExtension", sink.scalar("maxPriority1Violations", "maxPriority2Violations", "maxPriority3Violations", "reportFormat"));
        sink.put("org.gradle.api.plugins.quality.CodeQualityExtension", sink.directory("reportsDir"));
        sink.put("org.gradle.api.plugins.quality.CodeQualityExtension", sink.listLike("sourceSets"));
        sink.put("org.gradle.api.plugins.quality.CodeQualityExtension", sink.scalar("ignoreFailures", "toolVersion"));
        sink.put("org.gradle.api.plugins.quality.Pmd", sink.configurableFileCollection("classpath", "pmdClasspath", "ruleSetFiles"));
        sink.put("org.gradle.api.plugins.quality.Pmd", sink.listLike("ruleSets"));
        sink.put("org.gradle.api.plugins.quality.Pmd", sink.scalar("consoleOutput", "incrementalCacheFile", "targetJdk"));
        sink.put("org.gradle.api.plugins.quality.PmdExtension", sink.configurableFileCollection("ruleSetFiles"));
        sink.put("org.gradle.api.plugins.quality.PmdExtension", sink.listLike("ruleSets"));
        sink.put("org.gradle.api.plugins.quality.PmdExtension", sink.scalar("consoleOutput", "targetJdk"));
        sink.put("org.gradle.api.publish.PublicationArtifact", sink.scalar("file"));
        sink.put("org.gradle.api.publish.ivy.IvyArtifact", sink.scalar("classifier", "conf", "extension", "name", "type"));
        sink.put("org.gradle.api.publish.ivy.IvyModuleDescriptorSpec", sink.scalar("branch", "status"));
        sink.put("org.gradle.api.publish.ivy.IvyPublication", sink.scalar("module", "organisation", "revision"));
        sink.put("org.gradle.api.publish.ivy.tasks.GenerateIvyDescriptor", sink.regularFile("destination"));
        sink.put("org.gradle.api.publish.maven.MavenArtifact", sink.scalar("classifier", "extension"));
        sink.put("org.gradle.api.publish.maven.MavenPom", sink.scalar("packaging"));
        sink.put("org.gradle.api.publish.maven.MavenPublication", sink.scalar("artifactId", "groupId", "version"));
        sink.put("org.gradle.api.publish.maven.tasks.GenerateMavenPom", sink.regularFile("destination"));
        sink.put("org.gradle.api.tasks.AbstractExecTask", sink.listLike("args"));
        sink.put("org.gradle.api.tasks.AbstractExecTask", sink.scalar("errorOutput", "ignoreExitValue", "standardInput", "standardOutput"));
        sink.put("org.gradle.api.tasks.Exec", sink.listLike("args"));
        sink.put("org.gradle.api.tasks.Exec", sink.scalar("errorOutput", "ignoreExitValue", "standardInput", "standardOutput"));
        sink.put("org.gradle.api.tasks.JavaExec", sink.configurableFileCollection("classpath"));
        sink.put("org.gradle.api.tasks.JavaExec", sink.listLike("args"));
        sink.put("org.gradle.api.tasks.JavaExec", sink.scalar("errorOutput", "ignoreExitValue", "javaVersion", "standardInput", "standardOutput"));
        sink.put("org.gradle.api.tasks.WriteProperties", sink.mapLike("properties"));
        sink.put("org.gradle.api.tasks.WriteProperties", sink.scalar("comment", "encoding", "lineSeparator"));
        sink.put("org.gradle.api.tasks.ant.AntTarget", sink.directory("baseDir"));
        sink.put("org.gradle.api.tasks.ant.AntTarget", sink.scalar("target"));
        sink.put("org.gradle.api.tasks.bundling.AbstractArchiveTask", sink.scalar("preserveFileTimestamps", "reproducibleFileOrder"));
        sink.put("org.gradle.api.tasks.bundling.Tar", sink.scalar("compression"));
        sink.put("org.gradle.api.tasks.bundling.War", sink.configurableFileCollection("classpath"));
        sink.put("org.gradle.api.tasks.bundling.War", sink.regularFile("webXml"));
        sink.put("org.gradle.api.tasks.bundling.Zip", sink.scalar("entryCompression", "metadataCharset", "zip64"));
        sink.put("org.gradle.api.tasks.compile.AbstractCompile", sink.configurableFileCollection("classpath"));
        sink.put("org.gradle.api.tasks.compile.BaseForkOptions", sink.listLike("jvmArgs"));
        sink.put("org.gradle.api.tasks.compile.BaseForkOptions", sink.scalar("memoryInitialSize", "memoryMaximumSize"));
        sink.put("org.gradle.api.tasks.compile.CompileOptions", sink.configurableFileCollection("annotationProcessorPath", "bootstrapClasspath", "sourcepath"));
        sink.put("org.gradle.api.tasks.compile.CompileOptions", sink.listLike("compilerArgs"));
        sink.put("org.gradle.api.tasks.compile.CompileOptions", sink.scalar("allCompilerArgs", "debug", "deprecation", "encoding", "extensionDirs", "failOnError", "fork", "incremental", "listFiles", "verbose", "warnings"));
        sink.put("org.gradle.api.tasks.compile.DebugOptions", sink.scalar("debugLevel"));
        sink.put("org.gradle.api.tasks.compile.ForkOptions", sink.scalar("executable", "tempDir"));
        sink.put("org.gradle.api.tasks.compile.GroovyCompile", sink.configurableFileCollection("groovyClasspath"));
        sink.put("org.gradle.api.tasks.compile.GroovyCompileOptions", sink.listLike("fileExtensions"));
        sink.put("org.gradle.api.tasks.compile.GroovyCompileOptions", sink.mapLike("optimizationOptions"));
        sink.put("org.gradle.api.tasks.compile.GroovyCompileOptions", sink.regularFile("configurationScript"));
        sink.put("org.gradle.api.tasks.compile.GroovyCompileOptions", sink.scalar("encoding", "failOnError", "fork", "javaAnnotationProcessing", "keepStubs", "listFiles", "parameters", "verbose"));
        sink.put("org.gradle.api.tasks.compile.ProviderAwareCompilerDaemonForkOptions", sink.listLike("jvmArgumentProviders"));
        sink.put("org.gradle.api.tasks.compile.ProviderAwareCompilerDaemonForkOptions", sink.scalar("allJvmArgs"));
        sink.put("org.gradle.api.tasks.diagnostics.AbstractDependencyReportTask", sink.setLike("configurations"));
        sink.put("org.gradle.api.tasks.diagnostics.ConventionReportTask", sink.regularFile("outputFile"));
        sink.put("org.gradle.api.tasks.diagnostics.ConventionReportTask", sink.setLike("projects"));
        sink.put("org.gradle.api.tasks.diagnostics.DependencyInsightReportTask", sink.scalar("configuration", "dependencyNotation"));
        sink.put("org.gradle.api.tasks.diagnostics.TaskReportTask", sink.scalar("displayGroup"));
        sink.put("org.gradle.api.tasks.javadoc.Groovydoc", sink.configurableFileCollection("classpath", "groovyClasspath"));
        sink.put("org.gradle.api.tasks.javadoc.Groovydoc", sink.directory("destinationDir"));
        sink.put("org.gradle.api.tasks.javadoc.Groovydoc", sink.scalar("docTitle", "footer", "header", "noTimestamp", "noVersionStamp", "use", "windowTitle"));
        sink.put("org.gradle.api.tasks.javadoc.Groovydoc", sink.setLike("links"));
        sink.put("org.gradle.api.tasks.javadoc.Javadoc", sink.configurableFileCollection("classpath"));
        sink.put("org.gradle.api.tasks.javadoc.Javadoc", sink.directory("destinationDir"));
        sink.put("org.gradle.api.tasks.javadoc.Javadoc", sink.scalar("executable", "failOnError", "maxMemory", "optionsFile", "outputDirectory", "title"));
        sink.put("org.gradle.api.tasks.scala.ScalaCompile", sink.configurableFileCollection("scalaClasspath", "scalaCompilerPlugins", "zincClasspath"));
        sink.put("org.gradle.api.tasks.scala.ScalaDoc", sink.configurableFileCollection("classpath", "scalaClasspath"));
        sink.put("org.gradle.api.tasks.scala.ScalaDoc", sink.directory("destinationDir"));
        sink.put("org.gradle.api.tasks.scala.ScalaDoc", sink.scalar("title"));
        sink.put("org.gradle.api.tasks.scala.ScalaDocOptions", sink.listLike("additionalParameters"));
        sink.put("org.gradle.api.tasks.scala.ScalaDocOptions", sink.scalar("bottom", "deprecation", "docTitle", "footer", "header", "top", "unchecked", "windowTitle"));
        sink.put("org.gradle.api.tasks.testing.JUnitXmlReport", sink.scalar("outputPerTestCase"));
        sink.put("org.gradle.api.tasks.testing.Test", sink.configurableFileCollection("classpath", "testClassesDirs"));
        sink.put("org.gradle.api.tasks.testing.Test", sink.scalar("forkEvery", "javaVersion", "maxParallelForks", "scanForTestClasses", "testFramework"));
        sink.put("org.gradle.api.tasks.testing.TestFilter", sink.scalar("failOnNoMatchingTests"));
        sink.put("org.gradle.api.tasks.testing.TestFilter", sink.setLike("excludePatterns", "includePatterns"));
        sink.put("org.gradle.api.tasks.testing.junit.JUnitOptions", sink.setLike("excludeCategories", "includeCategories"));
        sink.put("org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions", sink.setLike("excludeEngines", "excludeTags", "includeEngines", "includeTags"));
        sink.put("org.gradle.api.tasks.testing.logging.TestLogging", sink.scalar("displayGranularity", "exceptionFormat", "maxGranularity", "minGranularity"));
        sink.put("org.gradle.api.tasks.testing.logging.TestLogging", sink.setLike("events", "stackTraceFilters"));
        sink.put("org.gradle.api.tasks.testing.testng.TestNGOptions", sink.configurableFileCollection("suiteXmlFiles"));
        sink.put("org.gradle.api.tasks.testing.testng.TestNGOptions", sink.directory("outputDirectory"));
        sink.put("org.gradle.api.tasks.testing.testng.TestNGOptions", sink.scalar("configFailurePolicy", "parallel", "suiteName", "suiteXml", "suiteXmlBuilder", "suiteXmlWriter", "testName", "threadCount", "threadPoolFactoryClass"));
        sink.put("org.gradle.api.tasks.testing.testng.TestNGOptions", sink.setLike("excludeGroups", "includeGroups", "listeners"));
        sink.put("org.gradle.api.tasks.wrapper.Wrapper", sink.regularFile("jarFile", "scriptFile"));
        sink.put("org.gradle.api.tasks.wrapper.Wrapper", sink.scalar("archiveBase", "archivePath", "batchScript", "distributionBase", "distributionPath", "propertiesFile"));
        sink.put("org.gradle.buildinit.tasks.InitBuild", sink.scalar("availableBuildTypes", "availableDSLs", "availableTestFrameworks", "projectName"));
        sink.put("org.gradle.caching.configuration.BuildCache", sink.scalar("enabled", "push"));
        sink.put("org.gradle.caching.http.HttpBuildCache", sink.scalar("allowInsecureProtocol", "allowUntrustedServer", "url", "useExpectContinue"));
        sink.put("org.gradle.caching.local.DirectoryBuildCache", sink.directory("directory"));
        sink.put("org.gradle.external.javadoc.MinimalJavadocOptions", sink.configurableFileCollection("bootClasspath", "classpath", "docletpath", "extDirs", "modulePath", "optionFiles"));
        sink.put("org.gradle.external.javadoc.MinimalJavadocOptions", sink.directory("destinationDirectory"));
        sink.put("org.gradle.external.javadoc.MinimalJavadocOptions", sink.listLike("jFlags", "sourceNames"));
        sink.put("org.gradle.external.javadoc.MinimalJavadocOptions", sink.scalar("breakIterator", "doclet", "encoding", "header", "locale", "memberLevel", "outputLevel", "overview", "source", "verbose", "windowTitle"));
        sink.put("org.gradle.external.javadoc.StandardJavadocDocletOptions", sink.configurableFileCollection("tagletPath"));
        sink.put("org.gradle.external.javadoc.StandardJavadocDocletOptions", sink.listLike("excludeDocFilesSubDir", "links", "linksOffline", "noQualifiers", "taglets", "tags"));
        sink.put("org.gradle.external.javadoc.StandardJavadocDocletOptions", sink.mapLike("groups"));
        sink.put("org.gradle.external.javadoc.StandardJavadocDocletOptions", sink.regularFile("helpFile", "stylesheetFile"));
        sink.put("org.gradle.external.javadoc.StandardJavadocDocletOptions", sink.scalar("author", "bottom", "charSet", "docEncoding", "docFilesSubDirs", "docTitle", "footer", "keyWords", "linkSource", "noComment", "noDeprecated", "noDeprecatedList", "noHelp", "noIndex", "noNavBar", "noSince", "noTimestamp", "noTree", "serialWarn", "splitIndex", "use", "version"));
        sink.put("org.gradle.jvm.application.tasks.CreateStartScripts", sink.configurableFileCollection("classpath"));
        sink.put("org.gradle.jvm.application.tasks.CreateStartScripts", sink.directory("outputDir"));
        sink.put("org.gradle.jvm.application.tasks.CreateStartScripts", sink.listLike("defaultJvmOpts"));
        sink.put("org.gradle.jvm.application.tasks.CreateStartScripts", sink.scalar("applicationName", "executableDir", "optsEnvironmentVar"));
        sink.put("org.gradle.jvm.tasks.Jar", sink.scalar("manifestContentCharset"));
        sink.put("org.gradle.language.scala.tasks.BaseScalaCompileOptions", sink.listLike("additionalParameters", "loggingPhases"));
        sink.put("org.gradle.language.scala.tasks.BaseScalaCompileOptions", sink.scalar("debugLevel", "deprecation", "encoding", "failOnError", "force", "listFiles", "loggingLevel", "optimize", "unchecked"));
        sink.put("org.gradle.plugin.devel.PluginDeclaration", sink.scalar("description", "displayName", "id", "implementationClass"));
        sink.put("org.gradle.plugins.ear.Ear", sink.scalar("libDirName"));
        sink.put("org.gradle.plugins.ear.descriptor.DeploymentDescriptor", sink.mapLike("moduleTypeMappings"));
        sink.put("org.gradle.plugins.ear.descriptor.DeploymentDescriptor", sink.scalar("applicationName", "description", "displayName", "initializeInOrder", "libraryDirectory", "version"));
        sink.put("org.gradle.plugins.ear.descriptor.DeploymentDescriptor", sink.setLike("modules", "securityRoles"));
        sink.put("org.gradle.plugins.ear.descriptor.EarModule", sink.scalar("altDeployDescriptor", "path"));
        sink.put("org.gradle.plugins.ear.descriptor.EarSecurityRole", sink.scalar("description", "roleName"));
        sink.put("org.gradle.plugins.ear.descriptor.EarWebModule", sink.scalar("contextRoot"));
        sink.put("org.gradle.process.BaseExecSpec", sink.scalar("commandLine", "errorOutput", "ignoreExitValue", "standardInput", "standardOutput"));
        sink.put("org.gradle.process.ExecSpec", sink.listLike("args"));
        sink.put("org.gradle.process.ExecSpec", sink.scalar("commandLine"));
        sink.put("org.gradle.process.JavaExecSpec", sink.configurableFileCollection("classpath"));
        sink.put("org.gradle.process.JavaExecSpec", sink.listLike("args"));
        sink.put("org.gradle.process.JavaForkOptions", sink.configurableFileCollection("bootstrapClasspath"));
        sink.put("org.gradle.process.JavaForkOptions", sink.listLike("jvmArgs"));
        sink.put("org.gradle.process.JavaForkOptions", sink.mapLike("systemProperties"));
        sink.put("org.gradle.process.JavaForkOptions", sink.scalar("allJvmArgs", "defaultCharacterEncoding", "maxHeapSize", "minHeapSize"));
        sink.put("org.gradle.process.ProcessForkOptions", sink.directory("workingDir"));
        sink.put("org.gradle.process.ProcessForkOptions", sink.mapLike("environment"));
        sink.put("org.gradle.process.ProcessForkOptions", sink.scalar("executable"));
        sink.put("org.gradle.testing.jacoco.plugins.JacocoPluginExtension", sink.scalar("toolVersion"));
        sink.put("org.gradle.testing.jacoco.plugins.JacocoTaskExtension", sink.directory("classDumpDir"));
        sink.put("org.gradle.testing.jacoco.plugins.JacocoTaskExtension", sink.listLike("excludeClassLoaders", "excludes", "includes"));
        sink.put("org.gradle.testing.jacoco.plugins.JacocoTaskExtension", sink.regularFile("destinationFile"));
        sink.put("org.gradle.testing.jacoco.plugins.JacocoTaskExtension", sink.scalar("address", "asJvmArg", "dumpOnExit", "enabled", "includeNoLocationClasses", "jmx", "output", "port", "sessionId"));
        sink.put("org.gradle.testing.jacoco.tasks.JacocoBase", sink.configurableFileCollection("jacocoClasspath"));
        sink.put("org.gradle.vcs.VersionControlRepository", sink.scalar("rootDir"));
        sink.put("org.gradle.vcs.VersionControlSpec", sink.scalar("repoName", "rootDir", "uniqueId"));
        sink.put("org.gradle.vcs.git.GitVersionControlSpec", sink.scalar("url"));
    }

    interface CatalogSink {
        void put(String declaringType, Map<String, MigratedProperties.Kind> entries);
        Map<String, MigratedProperties.Kind> scalar(String... names);
        Map<String, MigratedProperties.Kind> listLike(String... names);
        Map<String, MigratedProperties.Kind> setLike(String... names);
        Map<String, MigratedProperties.Kind> mapLike(String... names);
        Map<String, MigratedProperties.Kind> configurableFileCollection(String... names);
        Map<String, MigratedProperties.Kind> directory(String... names);
        Map<String, MigratedProperties.Kind> regularFile(String... names);
    }

    private MigratedPropertiesCatalog() {}
}
