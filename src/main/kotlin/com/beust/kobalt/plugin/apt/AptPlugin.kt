package com.beust.kobalt.plugin.apt

import com.beust.kobalt.Constants
import com.beust.kobalt.Jvm
import com.beust.kobalt.TaskResult
import com.beust.kobalt.api.*
import com.beust.kobalt.api.annotation.AnnotationDefault
import com.beust.kobalt.api.annotation.Directive
import com.beust.kobalt.internal.CompilerUtils
import com.beust.kobalt.maven.DependencyManager
import com.beust.kobalt.maven.aether.Filters
import com.beust.kobalt.maven.aether.Scope
import com.beust.kobalt.maven.dependency.FileDependency
import com.beust.kobalt.misc.KFiles
import com.beust.kobalt.misc.KobaltLogger
import com.beust.kobalt.misc.warn
import com.beust.kobalt.plugin.kotlin.KotlinPlugin
import com.google.common.collect.ArrayListMultimap
import com.google.inject.Inject
import java.io.File
import java.util.*
import javax.inject.Singleton

/**
 * The AptPlugin manages both apt and kapt. Each of them has two components:
 * 1) A new apt directive inside a dependency{} block (similar to compile()) that declares where
 * the annotation processor is found
 * 2) An apt{} configuration on Project that lets the user configure how the annotation is performed
 * (outputDir, etc...).
 */
@Singleton
class AptPlugin @Inject constructor(val dependencyManager: DependencyManager, val kotlinPlugin: KotlinPlugin,
        val compilerUtils: CompilerUtils, val jvm: Jvm)
    : BasePlugin(), ICompilerFlagContributor, ISourceDirectoryContributor, IClasspathContributor, ITaskContributor {

    companion object {
        const val PLUGIN_NAME = "Apt"
        const val KAPT_CONFIG = "kaptConfig"
        const val APT_CONFIG = "aptConfig"
    }

    override val name = PLUGIN_NAME

    override fun apply(project: Project, context: KobaltContext) {
        super.apply(project, context)
        val kaptConfig = kaptConfigs[project.name]

        // Delete the output directories
        listOf(aptConfigs[project.name]?.outputDir, kaptConfig?.outputDir)
            .filterNotNull()
            .distinct()
            .map { aptGeneratedDir(project, it) }
            .forEach {
                it.normalize().absolutePath.let { path ->
                    context.logger.log(project.name, 1, "  Deleting " + path)
                    val success = it.deleteRecursively()
                    if (!success) warn("  Couldn't delete " + path)
                }
            }
    }

    // IClasspathContributor
    override fun classpathEntriesFor(project: Project?, context: KobaltContext): Collection<IClasspathDependency> {
        val result = arrayListOf<IClasspathDependency>()
        val kaptConfig = kaptConfigs[project?.name]
        if (project != null && kaptConfig != null) {
            kaptConfig.let { config ->
                val c = kaptClassesDir(project, config.outputDir)
                File(c).mkdirs()
                result.add(FileDependency(c))
            }
        }
        return result
    }

    private fun aptGeneratedDir(project: Project, outputDir: String) : File
            = File(KFiles.joinDir(project.directory, KFiles.KOBALT_BUILD_DIR, outputDir))

    // ISourceDirectoryContributor
    override fun sourceDirectoriesFor(project: Project, context: KobaltContext): List<File> {
        val result = arrayListOf<File>()
        aptConfigs[project.name]?.let { config ->
            result.add(aptGeneratedDir(project, config.outputDir))
        }

        kaptConfigs[project.name]?.let { config ->
            result.add(File(kaptSourcesDir(project, config.outputDir)))
        }

        return result
    }

    private fun kaptGenerated(project: Project, outputDir: String) =
            KFiles.joinAndMakeDir(project.directory, project.buildDirectory, outputDir)

    private fun kaptSourcesDir(project: Project, outputDir: String) =
            KFiles.joinDir(kaptGenerated(project, outputDir), "sources")
    private fun kaptStubsDir(project: Project, outputDir: String) =
            KFiles.joinDir(kaptGenerated(project, outputDir), "stubs")
    private fun kaptClassesDir(project: Project, outputDir: String) =
            KFiles.joinDir(kaptGenerated(project, outputDir), "classes")

    // ITaskContributor
    override fun tasksFor(project: Project, context: KobaltContext): List<DynamicTask> {
        val kaptConfig = kaptConfigs[project.name]
        val result =
            if (kaptConfig != null) {
                listOf(
                    DynamicTask(this, "runKapt", "Run kapt", AnnotationDefault.GROUP, project,
                        reverseDependsOn = listOf("compile"), runAfter = listOf("clean"),
                        closure = {p: Project -> taskRunKapt(p)}),
                    DynamicTask(this, "compileKapt", "Compile the sources generated by kapt",
                        AnnotationDefault.GROUP, project,
                        dependsOn = listOf("runKapt"), reverseDependsOn = listOf("compile"),
                        closure = {p: Project -> taskCompileKapt(p)})
                )
            } else {
                emptyList()
            }
        return result
    }

    fun taskCompileKapt(project: Project) : TaskResult {
        var success = true
        kaptConfigs[project.name]?.let { config ->
            val sourceDirs = listOf(
                    kaptStubsDir(project, config.outputDir),
                    kaptSourcesDir(project, config.outputDir))
            val sourceFiles = KFiles.findSourceFiles(project.directory, sourceDirs, listOf("kt")).toList()
            val buildDirectory = File(KFiles.joinDir(project.directory,
                    kaptClassesDir(project, config.outputDir)))
            val flags = listOf<String>()
            val cai = CompilerActionInfo(project.directory, allDependencies(project), sourceFiles, listOf(".kt"),
                    buildDirectory, flags, emptyList(), forceRecompile = true, compilerSeparateProcess = true)

            val cr = compilerUtils.invokeCompiler(project, context, kotlinPlugin.compiler, cai)
            success = cr.failedResult == null
        }

        return TaskResult(success)
    }

    val annotationDependencyId = "org.jetbrains.kotlin:kotlin-annotation-processing:" +
            Constants.KOTLIN_COMPILER_VERSION

    fun annotationProcessorDependency() = dependencyManager.create(annotationDependencyId)

    fun aptJarDependencies(project: Project) = aptDependencies[project.name].map { dependencyManager.create(it) }

    fun allDependencies(project: Project): List<IClasspathDependency> {
        val allDeps = arrayListOf<IClasspathDependency>()
        allDeps.add(annotationProcessorDependency())
        allDeps.addAll(aptJarDependencies(project))

        return allDeps
    }

    fun taskRunKapt(project: Project) : TaskResult {
        var success = true
        val flags = arrayListOf<String>()
        val kaptConfig = kaptConfigs[project.name]
        kaptConfig?.let { config ->
            val generated = kaptGenerated(project, config.outputDir)
            val generatedSources = kaptSourcesDir(project, config.outputDir).replace("//", "/")
            File(generatedSources).mkdirs()

            //
            // Tell the Kotlin compiler to use the annotation plug-in
            //
            flags.add("-Xplugin")
            flags.add(annotationProcessorDependency().jarFile.get().absolutePath)

            // Also need tools.jar on the plug-in classpath
            val toolsJar = jvm.toolsJar
            if (toolsJar != null) {
                flags.add("-Xplugin")
                flags.add(toolsJar.absolutePath)
            } else {
                warn("Couldn't find tools.jar from the JDK")
            }

            aptJarDependencies(project).forEach {
                flags.add("-Xplugin")
                flags.add(it.jarFile.get().absolutePath)
            }

            //
            // Pass options to the annotation plugin
            //
            flags.add("-P")
            fun kaptPluginFlag(flagValue: String) = "plugin:org.jetbrains.kotlin.kapt3:$flagValue"
            val kaptPluginFlags = arrayListOf<String>()
            val verbose = KobaltLogger.LOG_LEVEL >= 2
            listOf("sources=" + generatedSources,
                    "classes=" + kaptClassesDir(project, config.outputDir),
                    "stubs=" + kaptStubsDir(project, config.outputDir),
                    "verbose=$verbose",
                    "aptOnly=true").forEach {
                kaptPluginFlags.add(kaptPluginFlag(it))
            }

            //
            // Dependencies for the annotation plug-in and the generation
            //
            val allDeps = allDependencies(project)
            val dependencies = dependencyManager.calculateDependencies(project, context,
                    Filters.EXCLUDE_OPTIONAL_FILTER,
                    listOf(Scope.COMPILE),
                    allDeps)
            dependencies.forEach {
                val jarFile = it.jarFile.get().absolutePath
                kaptPluginFlags.add(kaptPluginFlag("apclasspath=$jarFile"))
            }

            flags.add(kaptPluginFlags.joinToString(","))
            listOf("-language-version", "1.1", "-api-version", "1.1").forEach {
                flags.add(it)
            }

            val sourceFiles =
                KFiles.findSourceFiles(project.directory, project.sourceDirectories, listOf("kt"))
                        .toList() + generatedSources
            val buildDirectory = File(KFiles.joinDir(project.directory, generated))
            val cai = CompilerActionInfo(project.directory, allDeps, sourceFiles, listOf(".kt"),
                    buildDirectory, flags, emptyList(), forceRecompile = true, compilerSeparateProcess = true)

            context.logger.log(project.name, 2, "kapt3 flags:")
            context.logger.log(project.name, 2, "  " + kaptPluginFlags.joinToString("\n  "))
            val cr = compilerUtils.invokeCompiler(project, context, kotlinPlugin.compiler, cai)
            success = cr.failedResult == null
        }

        return TaskResult(success)
    }

    // ICompilerFlagContributor
    override fun compilerFlagsFor(project: Project, context: KobaltContext, currentFlags: List<String>,
            suffixesBeingCompiled: List<String>): List<String> {
        val result = arrayListOf<String>()

        // Only run for Java files
        if (!suffixesBeingCompiled.contains("java")) return emptyList()

        fun addFlags(outputDir: String) {
            aptDependencies[project.name]?.let {
                result.add("-s")
                aptGeneratedDir(project, outputDir).let { generatedSource ->
                    generatedSource.mkdirs()
                    result.add(generatedSource.path)
                }
            }
        }

        aptConfigs[project.name]?.let { config ->
            addFlags(config.outputDir)
        }

        context.logger.log(project.name, 2, "New flags from apt: " + result.joinToString(" "))
        return result
    }

    private val aptDependencies = ArrayListMultimap.create<String, String>()

    fun addAptDependency(dependencies: Dependencies, it: String) {
        aptDependencies.put(dependencies.project.name, it)
    }

    private val aptConfigs: HashMap<String, AptConfig> = hashMapOf()
    private val kaptConfigs: HashMap<String, KaptConfig> = hashMapOf()

    fun addAptConfig(project: Project, kapt: AptConfig) {
        project.projectProperties.put(APT_CONFIG, kapt)
        aptConfigs.put(project.name, kapt)
    }

    fun addKaptConfig(project: Project, kapt: KaptConfig) {
        project.projectProperties.put(KAPT_CONFIG, kapt)
        kaptConfigs.put(project.name, kapt)
    }
}

class AptConfig(var outputDir: String = "generated/source/apt")

@Directive
fun Project.apt(init: AptConfig.() -> Unit) {
    AptConfig().let {
        it.init()
        (Kobalt.findPlugin(AptPlugin.PLUGIN_NAME) as AptPlugin).addAptConfig(this, it)
    }
}

@Directive
fun Dependencies.apt(vararg dep: String) {
    dep.forEach {
        (Kobalt.findPlugin(AptPlugin.PLUGIN_NAME) as AptPlugin).addAptDependency(this, it)
    }
}

class KaptConfig(var outputDir: String = "generated/source/apt")

@Directive
fun Project.kapt(init: KaptConfig.() -> Unit) {
    KaptConfig().let {
        it.init()
        (Kobalt.findPlugin(AptPlugin.PLUGIN_NAME) as AptPlugin).addKaptConfig(this, it)
    }
}
