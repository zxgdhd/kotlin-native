package org.jetbrains.kotlin.native.interop.gen

import org.jetbrains.kotlin.konan.util.DefFile
import org.jetbrains.kotlin.native.interop.indexer.*
import java.io.File
import java.lang.Class
import java.lang.ClassNotFoundException
import java.lang.IllegalArgumentException
import java.lang.Runnable
import java.lang.System
import java.util.*
import kotlin.reflect.KFunction

fun defFileDependencies(args: Array<String>) {
    val konanHome = File(System.getProperty("konan.home")).absolutePath

    val substitutions = mapOf(
            "arch" to (System.getenv("TARGET_ARCH") ?: "x86-64"),
            "os" to (System.getenv("TARGET_OS") ?: host)
    )
    val defFiles = mutableListOf<String>()
    var target = defaultTarget

    var index = 0
    while (index < args.size) {
        val arg = args[index]

        ++index

        when (arg) {
            "-target" -> {
                target = args[index].targetSuffix()
                ++index
            }
            else -> {
                defFiles.add(arg)
            }
        }
    }

    val pendingLibs = defFiles.map {
        val def = DefFile(File(it), substitutions)
        def to processLib(konanHome, def, target).split()
    }.toMap().toMutableMap()

//    pendingLibs.forEach { (def, lib) ->
//        println(def.name)
//        lib.ownHeaders.forEach { println(it) }
//        println()
//    }

    val processedLibs = mutableMapOf<DefFile, SplittedNativeLibrary>()
    val processedHeaders = mutableMapOf<String, DefFile>()
//    val pendingHeaders = pendingLibs.values.flatMap { it.allHeaders }.toSet()

    val presentHeaders = pendingLibs.values.flatMap { it.ownHeaders }
    val missingHeaders = mutableMapOf<String, DefFile>()

    pendingLibs.forEach { (def, lib) ->
        val localMissingHeaders = (lib.foreignHeaders - presentHeaders)
        localMissingHeaders.forEach {
            missingHeaders.putIfAbsent(it, def)
        }
    }

    if (missingHeaders.isNotEmpty()) {
        error("Missing headers:\n" +
                missingHeaders.entries.joinToString("\n") { "${it.key}\n  included by: ${it.value.name}" })
    }

    while (pendingLibs.isNotEmpty()) {
        val nextDefFile = pendingLibs.entries.firstOrNull { (_, lib) ->
            lib.foreignHeaders.all { it in processedHeaders }
        }?.key

        if (nextDefFile == null) {
            pendingLibs.entries.forEach { (def, lib) ->
                println(def.name)
                println("Own headers:")
                lib.ownHeaders.forEach { println(it) }
                println()
                lib.foreignHeaders.forEach { if (it !in processedHeaders) println(it) }
            }
            error("Cyclic dependency? Remaining libs:\n" + pendingLibs.keys.joinToString("\n") { it.name })
        }

        val nextLib = pendingLibs.remove(nextDefFile)!!
        processedLibs[nextDefFile] = nextLib
        // TODO: check own headers are unique for some cases.
        val depends = nextLib.allHeaders.mapNotNull { processedHeaders[it] }
                .sortedBy { it.name }.distinct()

        println(nextDefFile.name)
//        println(nextLib.library.includes)
        nextLib.ownHeaders.forEach {
            processedHeaders.putIfAbsent(it, nextDefFile)
//            println(it)
        }
//        println()

        val defFileLines = nextDefFile.file!!.readLines()
        val dependsLine = buildString {
            append("depends =")
            depends.forEach {
                append(" ")
                append(it.name)
            }
        }
        val newDefFileLines = listOf(dependsLine) + defFileLines.filter { !it.startsWith("depends =") }

        nextDefFile.file!!.bufferedWriter().use { writer ->
            newDefFileLines.forEach { writer.appendln(it) }
        }
    }
}

private val host: String by lazy {
    val os = System.getProperty("os.name")
    when {
        os == "Linux" -> "linux"
        os.startsWith("Windows") -> "mingw"
        os == "Mac OS X" -> "osx"
        os == "FreeBSD" -> "freebsd"
        else -> {
            throw IllegalArgumentException("we don't know ${os} value")
        }
    }
}
private val defaultTarget: String by lazy {
    host
}
// TODO: share KonanTarget class here.
private val knownTargets = mapOf(
        "host" to host,
        "linux" to "linux",
        "macbook" to "osx",
        "osx" to "osx",
        "iphone" to "ios",
        "ios" to "ios",
        "iphone_sim" to "ios_sim",
        "ios_sim" to "ios_sim",
        "raspberrypi" to "raspberrypi",
        "linux_mips32" to "linux_mips32",
        "linux_mipsel32" to "linux_mipsel32",
        "android_arm32" to "android_arm32",
        "android_arm64" to "android_arm64",
        "mingw" to "mingw",
        "wasm32" to "wasm32"
)

private fun String.targetSuffix(): String =
        knownTargets[this] ?: error("Unsupported target $this.")

// Performs substitution similar to:
//  foo = ${foo} ${foo.${arch}} ${foo.${os}}
private fun substitute(properties: Properties, substitutions: Map<String, String>) {
    for (key in properties.stringPropertyNames()) {
        for (substitution in substitutions.values) {
            val suffix = ".$substitution"
            if (key.endsWith(suffix)) {
                val baseKey = key.removeSuffix(suffix)
                val oldValue = properties.getProperty(baseKey, "")
                val appendedValue = properties.getProperty(key, "")
                val newValue = if (oldValue != "") "$oldValue $appendedValue" else appendedValue
                properties.setProperty(baseKey, newValue)
            }
        }
    }
}

private fun Properties.getHostSpecific(
        name: String) = getProperty("$name.${host}")

private fun Properties.getTargetSpecific(
        name: String, target: String) = getProperty("$name.$target")

private fun Properties.getHostTargetSpecific(
        name: String, target: String) = if (host != target)
    getProperty("$name.${host}-$target")
else
    getProperty("$name.${host}")

private fun maybeExecuteHelper(dependenciesRoot: String, properties: Properties, dependencies: List<String>) {
    try {
        val kClass = Class.forName("org.jetbrains.kotlin.konan.util.Helper0").kotlin
        @Suppress("UNCHECKED_CAST")
        val ctor = kClass.constructors.single() as KFunction<Runnable>
        val result = ctor.call(dependenciesRoot, properties, dependencies)
        result.run()
    } catch (notFound: ClassNotFoundException) {
        // Just ignore, no helper.
    } catch (e: Throwable) {
        throw IllegalStateException("Cannot download dependencies.", e)
    }
}

private fun Properties.getClangFlags(target: String, targetSysRoot: String): List<String> {
    val flags = getTargetSpecific("clangFlags", target)
    if (flags == null) return emptyList()
    return flags.replace("<sysrootDir>", targetSysRoot).split(' ')
}

private fun Properties.defaultCompilerOpts(target: String, dependencies: String): List<String> {
    val targetToolchainDir = getHostTargetSpecific("targetToolchain", target)!!
    val targetToolchain = "$dependencies/$targetToolchainDir"
    val targetSysRootDir = getTargetSpecific("targetSysRoot", target)!!
    val targetSysRoot = "$dependencies/$targetSysRootDir"
    val llvmHomeDir = getHostSpecific("llvmHome")!!
    val llvmHome = "$dependencies/$llvmHomeDir"

    val libclang = when (host) {
        "mingw" -> "$llvmHome/bin/libclang.dll"
        else -> "$llvmHome/lib/${System.mapLibraryName("clang")}"
    }
    System.load(libclang)

    val llvmVersion = getHostSpecific("llvmVersion")!!

    // StubGenerator passes the arguments to libclang which
    // works not exactly the same way as the clang binary and
    // (in particular) uses different default header search path.
    // See e.g. http://lists.llvm.org/pipermail/cfe-dev/2013-November/033680.html
    // We workaround the problem with -isystem flag below.
    val isystem = "$llvmHome/lib/clang/$llvmVersion/include"
    val quadruple = getTargetSpecific("quadruple", target)
    val arch = getTargetSpecific("arch", target)
    val archSelector = if (quadruple != null)
        listOf("-target", quadruple) else listOf("-arch", arch!!)
    val commonArgs = listOf("-isystem", isystem, "--sysroot=$targetSysRoot") + getClangFlags(target, targetSysRoot)
    when (host) {
        "osx" -> {
            val osVersionMinFlag = getTargetSpecific("osVersionMinFlagClang", target)
            val osVersionMinValue = getTargetSpecific("osVersionMin", target)
            return archSelector + commonArgs + listOf("-B$targetToolchain/bin") +
                    (if (osVersionMinFlag != null && osVersionMinValue != null)
                        listOf("$osVersionMinFlag=$osVersionMinValue") else emptyList())
        }
        "linux" -> {
            val libGcc = getTargetSpecific("libGcc", target)
            val binDir = "$targetSysRoot/${libGcc ?: "bin"}"
            return archSelector + commonArgs + listOf(
                    "-B$binDir", "--gcc-toolchain=$targetToolchain",
                    "-fuse-ld=$targetToolchain/bin/ld")
        }
        "mingw" -> {
            return archSelector + commonArgs + listOf("-B$targetSysRoot/bin")
        }
        else -> error("Unexpected target: ${target}")
    }
}

private fun loadProperties(file: File?, substitutions: Map<String, String>): Properties {
    val result = Properties()
    file?.bufferedReader()?.use { reader ->
        result.load(reader)
    }
    substitute(result, substitutions)
    return result
}

private fun downloadDependencies(dependenciesRoot: String, target: String, konanProperties: Properties) {
    val dependencyList = konanProperties.getHostTargetSpecific("dependencies", target)?.split(' ') ?: listOf<String>()
    maybeExecuteHelper(dependenciesRoot, konanProperties, dependencyList)
}

private fun selectNativeLanguage(config: DefFile.DefFileConfig): Language {
    val languages = mapOf(
            "C" to Language.C,
            "Objective-C" to Language.OBJECTIVE_C
    )

    val language = config.language ?: return Language.C

    return languages[language] ?:
            error("Unexpected language '$language'. Possible values are: ${languages.keys.joinToString { "'$it'" }}")
}

private fun processLib(konanHome: String,
                       def: DefFile,
                       target: String): NativeLibrary {

    // args["-properties"]?.single() ?:
    val konanFileName = "${konanHome}/konan/konan.properties"
    val konanFile = File(konanFileName)
    val konanProperties = loadProperties(konanFile, mapOf())
    val dependencies = "$konanHome/dependencies"
    downloadDependencies(dependencies, target, konanProperties)

    // TODO: We can provide a set of flags to find the components in the absence of 'dist' or 'dist/dependencies'.

    val defaultOpts = konanProperties.defaultCompilerOpts(target, dependencies)
    val headerFiles = def.config.headers
    val language = selectNativeLanguage(def.config)
    val compilerOpts: List<String> = mutableListOf<String>().apply {
        addAll(def.config.compilerOpts)
        addAll(defaultOpts)
        addAll(when (language) {
            Language.C -> emptyList()
            Language.OBJECTIVE_C -> {
                // "Objective-C" within interop means "Objective-C with ARC":
                listOf("-fobjc-arc")
                // Using this flag here has two effects:
                // 1. The headers are parsed with ARC enabled, thus the API is visible correctly.
                // 2. The generated Objective-C stubs are compiled with ARC enabled, so reference counting
                // calls are inserted automatically.
            }
        })
    }
    val excludeSystemLibs = def.config.excludeSystemLibs
    val excludeDependentModules = def.config.excludeDependentModules

    val headerFilterGlobs = def.config.headerFilter
    val imports = ImportsImpl(emptyMap())
    val headerInclusionPolicy = HeadersInclusionPolicyImpl(headerFilterGlobs, imports)

    return NativeLibrary(
            includes = headerFiles,
            additionalPreambleLines = def.defHeaderLines,
            compilerArgs = compilerOpts,
            language = language,
            excludeSystemLibs = excludeSystemLibs,
            excludeDepdendentModules = excludeDependentModules,
            headerInclusionPolicy = headerInclusionPolicy
    )
}