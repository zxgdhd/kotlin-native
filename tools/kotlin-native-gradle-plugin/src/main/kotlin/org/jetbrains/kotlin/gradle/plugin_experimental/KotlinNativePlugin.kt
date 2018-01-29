package org.jetbrains.kotlin.gradle.plugin_experimental

import groovy.lang.Closure
import org.gradle.api.*
import org.gradle.api.internal.DefaultDomainObjectCollection
import org.gradle.api.internal.DefaultNamedDomainObjectCollection
import org.gradle.api.internal.DynamicObjectAware
import org.gradle.api.internal.plugins.DefaultConvention
import org.gradle.api.internal.plugins.DslObject
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.ExtensionContainer
import org.gradle.internal.metaobject.DynamicObject
import org.gradle.internal.reflect.Instantiator
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import org.gradle.util.ConfigureUtil
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.plugin.tasks.KonanBuildingTask
import org.jetbrains.kotlin.gradle.plugin.tasks.KonanCompileProgramTask
import org.jetbrains.kotlin.gradle.plugin.tasks.KonanCompilerDownloadTask
import org.jetbrains.kotlin.gradle.plugin.tasks.KonanGenerateCMakeTask
import java.security.cert.Extension
import javax.inject.Inject

// TODO: All the stuff from KonanPlugin will be moved here.

internal val ProjectInternal.instantiator: Instantiator
    get() = services.get(Instantiator::class.java)

class KotlinNativePlugin @Inject constructor(private val registry: ToolingModelBuilderRegistry)
    : Plugin<ProjectInternal> {

    private fun Project.cleanKonan() = project.tasks.withType(KonanBuildingTask::class.java).forEach {
        project.delete(it.artifact)
    }

    open class KotlinExtension(val project: ProjectInternal) {
        private val instantiator = project.instantiator

        val nativeExtension = instantiator.newInstance(KotlinNativeExtension::class.java, project)

        fun native(closure: Closure<Unit>) = native(ConfigureUtil.configureUsing(closure))
        fun native(action: Action<KotlinNativeExtension>) = native { action.execute(nativeExtension) }
        fun native(configure: KotlinNativeExtension.() -> Unit) = nativeExtension.configure()
    }

    override fun apply(project: ProjectInternal?) {
        if (project == null) return
        registry.register(KonanToolingModelBuilder)
        with(project) {
            plugins.apply("base")
            // Create necessary tasks and extensions.
            // TODO: Use standard ways for downloading.
            // TODO: Get rid of konan in task names (both class and user names)?
            tasks.create(KonanPlugin.KONAN_DOWNLOAD_TASK_NAME, KonanCompilerDownloadTask::class.java)
            tasks.create(KonanPlugin.KONAN_GENERATE_CMAKE_TASK_NAME, KonanGenerateCMakeTask::class.java)

            // TODO: The extension will be added by kotlin-base plugin but while it isn't here we create it ourselves.
            val kotlinExtension = extensions.create(KotlinExtension::class.java,"kotlin", KotlinExtension::class.java, this)

            // TODO: Also add an old 'konan' extension to be compatible with old tasks. Remove it when the old DSL will be changed
            extensions.add(KonanPlugin.KONAN_EXTENSION_NAME, kotlinExtension.nativeExtension.asKonanExtension())

            // Set additional project properties like konan.home, konan.build.targets etc.
            if (!hasProperty(KonanPlugin.ProjectProperty.KONAN_HOME)) {
                setProperty(KonanPlugin.ProjectProperty.KONAN_HOME, konanCompilerDownloadDir())
                setProperty(KonanPlugin.ProjectProperty.DOWNLOAD_COMPILER, true)
            }

            // Create and set up aggregate building tasks.
            val compileKonanTask = getOrCreateTask(KonanPlugin.COMPILE_ALL_TASK_NAME).apply {
                group = BasePlugin.BUILD_GROUP
                description = "Compiles all the Kotlin/Native artifacts"
            }
            getTask("build").apply {
                dependsOn(compileKonanTask)
            }
            getTask("clean").apply {
                doLast { cleanKonan() }
            }

            // Create task to run supported executables.
            // TODO: Do we really need this?
            getOrCreateTask("run").apply {
                dependsOn(getTask("build"))
                doLast {
                    for (task in tasks
                            .withType(KonanCompileProgramTask::class.java)
                            .matching { !it.isCrossCompile }) {
                        exec {
                            with(it) {
                                commandLine(task.artifact.canonicalPath)
                                if (extensions.extraProperties.has("runArgs")) {
                                    args(extensions.extraProperties.get("runArgs").toString().split(' '))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        internal const val KOTLIN_DOWNLOAD_TASK_NAME = "checkKotlinNativeCompiler"
        internal const val KOTLIN_GENERATE_CMAKE_TASK_NAME = "generateCMake"
        internal const val COMPILE_ALL_TASK_NAME = "compileKotlinNative"
    }
}