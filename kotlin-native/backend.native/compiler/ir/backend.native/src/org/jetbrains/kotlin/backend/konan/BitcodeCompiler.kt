/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan

import org.jetbrains.kotlin.backend.konan.driver.PhaseContext
import org.jetbrains.kotlin.konan.exec.Command
import org.jetbrains.kotlin.konan.target.*
import java.io.File

typealias ObjectFile = String
typealias ExecutableFile = String

internal class BitcodeCompiler(
        private val context: PhaseContext,
) {

    private val config = context.config
    private val platform = config.platform
    private val optimize = context.shouldOptimize()
    private val debug = config.debug

    private val overrideClangOptions =
            config.configuration.getList(KonanConfigKeys.OVERRIDE_CLANG_OPTIONS)

    private fun MutableList<String>.addNonEmpty(elements: List<String>) {
        addAll(elements.filter { it.isNotEmpty() })
    }

    private fun runTool(vararg command: String) =
            Command(*command)
                    .logWith(context::log)
                    .execute()

    private fun targetTool(tool: String, vararg arg: String) {
        val absoluteToolName = "${platform.absoluteTargetToolchain}/bin/$tool"
        runTool(absoluteToolName, *arg)
    }

    private fun hostLlvmTool(tool: String, vararg arg: String) {
        val absoluteToolName = "${platform.absolute(platform.hostString("llvm12"))}/bin/$tool"
        runTool(absoluteToolName, *arg)
    }

    private fun clang(configurables: ClangFlags, bitcodeFile: File, objectFile: File) {
        val targetTriple = if (configurables is AppleConfigurables) {
            platform.targetTriple.withOSVersion(configurables.osVersionMin)
        } else {
            platform.targetTriple
        }
        val flags = overrideClangOptions.takeIf(List<String>::isNotEmpty)
                ?: mutableListOf<String>().apply {
                    addNonEmpty(configurables.clangFlags)
                    addNonEmpty(listOf("-triple", targetTriple.toString()))
                    if (configurables is ZephyrConfigurables) {
                        addNonEmpty(configurables.constructClangCC1Args())
                    }
                    addNonEmpty(when {
                        optimize -> configurables.clangOptFlags
                        debug -> configurables.clangDebugFlags
                        else -> configurables.clangNooptFlags
                    })
                    addNonEmpty(configurables.currentRelocationMode(context).translateToClangCc1Flag())
                    
                    // PGO Support - add Clang PGO flags
                    addNonEmpty(getPgoClangFlags())
                }
        val bitcodePath = bitcodeFile.absoluteFile.normalize().path
        val objectPath = objectFile.absoluteFile.normalize().path
        if (configurables is AppleConfigurables && config.configuration.get(BinaryOptions.compileBitcodeWithXcodeLlvm) == true) {
            targetTool("clang++", *flags.toTypedArray(), bitcodePath, "-o", objectPath)
        } else {
            hostLlvmTool("clang++", *flags.toTypedArray(), bitcodePath, "-o", objectPath)
        }
    }
    
    private fun getPgoClangFlags(): List<String> {
        val pgoFlags = mutableListOf<String>()
        
        // Check if we're using -cc1 mode by examining clang flags
        val configurables = platform.configurables
        val isCC1Mode = if (configurables is ClangFlags) {
            configurables.clangFlags.contains("-cc1")
        } else {
            false
        }
        
        if (isCC1Mode) {
            // In -cc1 mode, use LLVM frontend flags for PGO
            config.configuration.get(KonanConfigKeys.PROFILE_GENERATE)?.let { instrumentPath ->
                // Use LLVM frontend instrumentation flags for -cc1 mode
                pgoFlags.addAll(listOf(
                    "-fprofile-instrument=clang",
                    "-fprofile-instrument-path=$instrumentPath/default.profraw"
                ))

                // Add runtime initialization flags to ensure PGO works correctly
                pgoFlags.addAll(listOf(
                    "-mllvm", "-runtime-counter-relocation=true",
                    "-mllvm", "-pgo-warn-missing-function=false"
                ))
            }
            
            config.configuration.get(KonanConfigKeys.PROFILE_USE)?.let { profilePath ->
                // Use LLVM frontend optimization flags for -cc1 mode
                pgoFlags.addAll(listOf(
                    "-fprofile-instrument-use-path=$profilePath",
                    "-mllvm", "-pgo-warn-missing-function=false"
                ))
            }
            
            // Sample-based PGO in -cc1 mode
            if (config.configuration.get(KonanConfigKeys.PGO_SAMPLE) == true) {
                pgoFlags.add("-fprofile-sample-use")
            }
        } else {
            // Driver mode - use standard PGO flags
            config.configuration.get(KonanConfigKeys.PROFILE_GENERATE)?.let { instrumentPath ->
                pgoFlags.addAll(listOf(
                    "-fprofile-generate=$instrumentPath",
                    "-mllvm", "-runtime-counter-relocation=true"
                ))
            }
            
            config.configuration.get(KonanConfigKeys.PROFILE_USE)?.let { profilePath ->
                pgoFlags.add("-fprofile-use=$profilePath")
            }
            
            if (config.configuration.get(KonanConfigKeys.PGO_SAMPLE) == true) {
                pgoFlags.add("-fprofile-sample-use")
            }
        }
        
        return pgoFlags
    }

    private fun RelocationModeFlags.Mode.translateToClangCc1Flag() = when (this) {
        RelocationModeFlags.Mode.PIC -> listOf("-mrelocation-model", "pic")
        RelocationModeFlags.Mode.STATIC -> listOf("-mrelocation-model", "static")
        RelocationModeFlags.Mode.DEFAULT -> emptyList()
    }

    /**
     * Compile [bitcodeFile] to [objectFile].
     */
    fun makeObjectFile(bitcodeFile: File, objectFile: File) =
            when (val configurables = platform.configurables) {
                is ClangFlags -> clang(configurables, bitcodeFile, objectFile)
                else -> error("Unsupported configurables kind: ${configurables::class.simpleName}!")
            }
}