/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.gradle.plugin.tasks

import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.PublishArtifact
import org.gradle.api.artifacts.dsl.ArtifactHandler
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.util.ConfigureUtil
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.plugin_experimental.KotlinNativePlugin
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.target.TargetManager
import org.jetbrains.kotlin.konan.util.visibleName
import java.io.File
import javax.inject.Inject

internal val Project.host
    get() = TargetManager.host.visibleName

internal val Project.simpleOsName
    get() = TargetManager.simpleOsName()

/** A task with a KonanTarget specified. */
abstract class KonanTargetableTask: DefaultTask() {

    @Input internal lateinit var konanTarget: KonanTarget

    internal open fun init(target: KonanTarget) {
        this.konanTarget = target
    }

    val targetIsSupported: Boolean
        @Internal get() = konanTarget.enabled

    val isCrossCompile: Boolean
        @Internal get() = (konanTarget != TargetManager.host)

    val target: String
        @Internal get() = konanTarget.visibleName
}

/** A task building an artifact. */
abstract class KonanArtifactTask: KonanTargetableTask(), KonanArtifactSpec {

    open val artifact: File
        @OutputFile get() = destinationDir.resolve(artifactFullName)

    @Internal lateinit var destinationDir: File
    @Internal lateinit var artifactName: String
    @Internal lateinit var artifactConfiguration: Configuration

    protected val artifactFullName: String
        @Internal get() = "$artifactPrefix$artifactName$artifactSuffix"

    val artifactPath: String
        @Internal get() = artifact.canonicalPath

    protected abstract val artifactSuffix: String
        @Internal get

    protected abstract val artifactPrefix: String
        @Internal get

    protected fun getArtifactConfigurationName(artifactName: String, target: String) = "${artifactName}Artifacts$target"

    internal open fun init(destinationDir: File, artifactName: String, target: KonanTarget) {
        super.init(target)
        this.destinationDir = destinationDir
        this.artifactName = artifactName
        artifactConfiguration = project.configurations.create(getArtifactConfigurationName(artifactName, this.target))
        artifactConfiguration.attributes.attribute(KotlinNativePlugin.TARGET_ATTRIBUTE, konanTarget)
        artifactConfiguration.outgoing.artifact(artifact)
    }

    // DSL.

    override fun artifactName(name: String) {
        artifactName = name
    }

    fun destinationDir(dir: Any) {
        destinationDir = project.file(dir)
    }
}

/** Task building an artifact with dependencies */
abstract class KonanArtifactWithDependenciesTask: KonanArtifactTask(), KonanArtifactWithDependenciesSpec {

    // TODO: Try to remove this stupid lateinits: split the task into a config and a task.
    @Nested
    lateinit var dependencies: Dependencies

    @Deprecated("Use dependencies.noDefaultLibs instead")
    var noDefaultLibs: Boolean
        get() = dependencies.noDefaultLibs
        set(value) = dependencies.noDefaultLibs(value)

    protected fun getConfigurationName(artifactName: String, target: String) = "${artifactName}Compile$target"


    override fun init(destinationDir: File, artifactName: String, target: KonanTarget) {
        super.init(destinationDir, artifactName, target)
        val configurationName = getConfigurationName(artifactName, target.visibleName)
        val compileConfiguration = project.configurations.create(configurationName)
        compileConfiguration.attributes.attribute(KotlinNativePlugin.TARGET_ATTRIBUTE, konanTarget)
        artifactConfiguration.extendsFrom(compileConfiguration)
        dependencies = Dependencies(this, compileConfiguration, project)
    }

    // DSL

    @Deprecated("Libraries will be replaced with configuration model")
    override fun libraries(closure: Closure<Unit>) = libraries(ConfigureUtil.configureUsing(closure))
    @Deprecated("Libraries will be replaced with configuration model")
    override fun libraries(action: Action<Dependencies>) = libraries { action.execute(this) }
    @Deprecated("Libraries will be replaced with configuration model")
    override fun libraries(configure: Dependencies.() -> Unit) { dependencies.configure() }

    @Deprecated("Libraries will be replaced with configuration model")
    override fun noDefaultLibs(flag: Boolean) {
        noDefaultLibs = flag
    }

    // DSL

    override fun dependencies(closure: Closure<Unit>) = dependencies(ConfigureUtil.configureUsing(closure))
    override fun dependencies(action: Action<DependenciesSpec>) = dependencies { action.execute(this) }
    override fun dependencies(configure: DependenciesSpec.() -> Unit) { dependencies.configure() }
}