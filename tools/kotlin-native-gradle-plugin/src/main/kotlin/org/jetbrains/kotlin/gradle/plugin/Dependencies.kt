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

package org.jetbrains.kotlin.gradle.plugin

import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.jetbrains.kotlin.gradle.plugin.tasks.KonanArtifactWithDependenciesTask
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.util.visibleName
import java.io.File
import javax.inject.Inject

// TODO: InputFiles? What kind of annotation should be used with Configuration?
open class Dependencies(val task: KonanArtifactWithDependenciesTask, val configuration: Configuration, val project: Project): DependenciesSpec {

    @Input var noDefaultLibs = false

    @Input val namedKlibs = mutableSetOf<String>()

    @Internal val explicitRepos = mutableSetOf<File>()

    val repos: Set<File>
        @Input get() = mutableSetOf<File>().apply {
            addAll(explicitRepos)
            add(task.destinationDir) // TODO: Check if task is a library - create a Library interface
            add(task.project.konanLibsBaseDir.targetSubdir(target))
        }

    val target: KonanTarget
        @Internal get() = task.konanTarget

    //  DSL Methods

    override fun compile(notation: Any) {
        configuration.dependencies.add(project.dependencies.create(notation))
    }

    override fun noDefaultLibs(flag: Boolean) {
        noDefaultLibs = flag
    }

    // region Deprecated methods. May be some of them (e.g. repos) will be used in the new DSL too.

    /** Absolute path */
    @Deprecated("Use 'compile files(...) instead'")
    fun file(file: Any)                   = compile(project.files(file))
    @Deprecated("Use 'compile files(...) instead'")
    fun files(vararg files: Any)          = compile(project.files(*files))
    @Deprecated("Use 'compile files(...) instead'")
    fun files(collection: FileCollection) = compile(collection)

    /** The compiler with search the library in repos */
    // TODO: Implement klib search by the plugin.
    fun klib(lib: String)             = namedKlibs.add(lib)
    fun klibs(vararg libs: String)    = namedKlibs.addAll(libs)
    fun klibs(libs: Iterable<String>) = namedKlibs.addAll(libs)

    private fun klibInternal(lib: KonanBuildingConfig<*>) {
        if (!(lib is KonanLibrary || lib is KonanInteropLibrary)) {
            throw InvalidUserDataException("Config ${lib.name} is not a library")
        }

        val libraryTask = lib[target] ?:
            throw InvalidUserDataException("Library ${lib.name} has no target ${target.visibleName}")

        if (libraryTask == task) {
            throw InvalidUserDataException("Attempt to use a library as its own dependency: " +
                    "${task.name} (in project: ${project.path})")
        }
        println("Add dependnecy for configuration ${this.configuration} from configuration: ${libraryTask.artifactConfiguration}")
        compile(project.files(libraryTask.artifact)) // TODO: Use artifact config. Find a way to pass an artifacts as dependencies
        task.dependsOn(libraryTask)
    }

    /** Direct link to a config */
    fun klib(lib: KonanLibrary) = klibInternal(lib)
    /** Direct link to a config */
    fun klib(lib: KonanInteropLibrary) = klibInternal(lib)

    /** Artifact in the specified project by name */
    fun artifact(libraryProject: Project, name: String) {
        project.evaluationDependsOn(libraryProject)
        klibInternal(libraryProject.konanArtifactsContainer.getByName(name))
    }

    /** Artifact in the current project by name */
    fun artifact(name: String) = artifact(project, name)

    /** Artifact by direct link */
    fun artifact(artifact: KonanLibrary) = klib(artifact)
    /** Direct link to a config */
    fun artifact(artifact: KonanInteropLibrary) = klib(artifact)

    private fun allArtifactsFromInternal(libraryProjects: Array<out Project>,
                                         filter: (KonanBuildingConfig<*>) -> Boolean) {
        libraryProjects.forEach { prj ->
            project.evaluationDependsOn(prj)
            prj.konanArtifactsContainer.filter(filter).forEach {
                klibInternal(it)
            }
        }
    }

    /** All libraries (both interop and non-interop ones) from the projects by direct references  */
    fun allLibrariesFrom(vararg libraryProjects: Project) = allArtifactsFromInternal(libraryProjects) {
        it is KonanLibrary || it is KonanInteropLibrary
    }

    /** All interop libraries from the projects by direct references */
    fun allInteropLibrariesFrom(vararg libraryProjects: Project) = allArtifactsFromInternal(libraryProjects) {
        it is KonanInteropLibrary
    }

    /** Add repo for library search */
    fun useRepo(directory: Any) = explicitRepos.add(project.file(directory))
    /** Add repos for library search */
    fun useRepos(vararg directories: Any) = directories.forEach { useRepo(it) }
    /** Add repos for library search */
    fun useRepos(directories: Iterable<Any>) = directories.forEach { useRepo(it) }

    private fun Project.evaluationDependsOn(another: Project) {
        if (this != another) { evaluationDependsOn(another.path) }
    }

    // endregion.
}
