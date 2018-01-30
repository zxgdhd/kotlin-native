/*
 * Copyright 2010-2018 JetBrains s.r.o.
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

package org.jetbrains.kotlin.gradle.plugin_experimental

import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.plugins.ExtensionAware
import org.gradle.internal.reflect.Instantiator
import org.gradle.util.ConfigureUtil
import org.jetbrains.kotlin.gradle.plugin.KonanArtifactContainer
import org.jetbrains.kotlin.gradle.plugin.KonanExtension
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.target.TargetManager


open class KotlinNativeExtension(val project: ProjectInternal) {

    private val instantiator = project.services.get(Instantiator::class.java)

    // region DSL

    var targets = mutableListOf("host")
    // TODO: These two parameters may be defined in kotlin-base plugin.
    var languageVersion: String? = null
    var apiVersion: String? = null

    val jvmArgs = mutableListOf<String>()

    val artifactContainer = instantiator.newInstance(KonanArtifactContainer::class.java, project)

    // endregion

    internal val konanTargets: List<KonanTarget>
        get() = targets.map { TargetManager(it).target }.distinct()

    fun artifacts(closure: Closure<Unit>) = artifacts(ConfigureUtil.configureUsing(closure))
    fun artifacts(action: Action<KonanArtifactContainer>) = artifacts { action.execute(this) }
    fun artifacts(configure: KonanArtifactContainer.() -> Unit) = artifactContainer.configure()

    // TODO: Remove when tasks will use kotlin.native block instead of konan
    internal fun asKonanExtension() = object: KonanExtension() {
        override var targets: MutableList<String>
            set(value) { this@KotlinNativeExtension.targets = value }
            get() = this@KotlinNativeExtension.targets

        override var languageVersion: String?
            set(value) { this@KotlinNativeExtension.languageVersion = value }
            get() = this@KotlinNativeExtension.languageVersion

        override var apiVersion: String?
            set(value) { this@KotlinNativeExtension.apiVersion = value }
            get() = this@KotlinNativeExtension.apiVersion

        override val jvmArgs: MutableList<String>
            get() = this@KotlinNativeExtension.jvmArgs
    }
}