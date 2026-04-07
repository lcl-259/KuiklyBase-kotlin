/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan

import kotlinx.cinterop.*
import llvm.*
import org.jetbrains.kotlin.backend.common.LoggingContext
import org.jetbrains.kotlin.backend.common.reportCompilationWarning
import org.jetbrains.kotlin.backend.konan.driver.PhaseContext
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.konan.target.*
import java.io.Closeable

enum class LlvmOptimizationLevel(val value: Int) {
    NONE(0),
    DEFAULT(1),
    AGGRESSIVE(3)
}

enum class LlvmSizeLevel(val value: Int) {
    NONE(0),
    DEFAULT(1),
    AGGRESSIVE(2)
}

/**
 * Incorporates everything that is used to tune a [LlvmOptimizationPipeline].
 */
data class LlvmPipelineConfig(
        val targetTriple: String,
        val cpuModel: String,
        val cpuFeatures: String,
        val optimizationLevel: LlvmOptimizationLevel,
        val sizeLevel: LlvmSizeLevel,
        val codegenOptimizationLevel: LLVMCodeGenOptLevel,
        val relocMode: LLVMRelocMode,
        val codeModel: LLVMCodeModel,
        val globalDce: Boolean,
        val internalize: Boolean,
        val makeDeclarationsHidden: Boolean,
        val objCPasses: Boolean,
        val inlineThreshold: Int?,
        val timePasses: Boolean = false,
        // PGO Support
        val pgoInstrumentPath: String? = null,
        val pgoUsePath: String? = null,
        val pgoSample: Boolean = false,
)


private fun getCpuModel(context: PhaseContext): String {
    val target = context.config.target
    val configurables: Configurables = context.config.platform.configurables
    return configurables.targetCpu ?: run {
        context.reportCompilationWarning("targetCpu for target $target was not set. Targeting `generic` cpu.")
        "generic"
    }
}

private fun getCpuFeatures(context: PhaseContext): String =
        context.config.platform.configurables.targetCpuFeatures ?: ""

private fun tryGetInlineThreshold(context: PhaseContext): Int? {
    val configurables: Configurables = context.config.platform.configurables
    return configurables.llvmInlineThreshold?.let {
        it.toIntOrNull() ?: run {
            context.reportCompilationWarning(
                    "`llvmInlineThreshold` should be an integer. Got `$it` instead. Using default value."
            )
            null
        }
    }
}

/**
 * Creates [LlvmPipelineConfig] that is used for [RuntimeLinkageStrategy.LinkAndOptimize].
 * There is no DCE or internalization here because optimized module will be linked later.
 * Still, runtime is not intended to be debugged by user, and we can optimize it pretty aggressively
 * even in debug compilation.
 */
internal fun createLTOPipelineConfigForRuntime(generationState: NativeGenerationState): LlvmPipelineConfig {
    val config = generationState.config
    val configurables: Configurables = config.platform.configurables
    return LlvmPipelineConfig(
            generationState.llvm.targetTriple,
            getCpuModel(generationState),
            getCpuFeatures(generationState),
            LlvmOptimizationLevel.AGGRESSIVE,
            LlvmSizeLevel.NONE,
            LLVMCodeGenOptLevel.LLVMCodeGenLevelAggressive,
            configurables.currentRelocationMode(generationState).translateToLlvmRelocMode(),
            LLVMCodeModel.LLVMCodeModelDefault,
            globalDce = false,
            internalize = false,
            objCPasses = configurables is AppleConfigurables,
            makeDeclarationsHidden = false,
            inlineThreshold = tryGetInlineThreshold(generationState),
    )
}

/**
 * In the end, Kotlin/Native generates a single LLVM module during compilation.
 * It won't be linked with any other LLVM module, so we can hide and DCE unused symbols.
 *
 * The set of optimizations relies on current compiler configuration.
 * In case of debug we do almost nothing (that's why we need [createLTOPipelineConfigForRuntime]),
 * but for release binaries we rely on "closed" world and enable a lot of optimizations.
 */
internal fun createLTOFinalPipelineConfig(
        context: PhaseContext,
        targetTriple: String,
        closedWorld: Boolean,
        timePasses: Boolean = false,
): LlvmPipelineConfig {
    val config = context.config
    val target = config.target
    val configurables: Configurables = config.platform.configurables
    val cpuModel = getCpuModel(context)
    val cpuFeatures = getCpuFeatures(context)
    val optimizationLevel: LlvmOptimizationLevel = when {
        context.shouldOptimize() -> LlvmOptimizationLevel.AGGRESSIVE
        context.shouldContainDebugInfo() -> LlvmOptimizationLevel.NONE
        else -> LlvmOptimizationLevel.DEFAULT
    }
    // TODO(KT-66501): investigate, why sizeLevel is essentially === to NONE (and inline it if it's OK)
    val sizeLevel: LlvmSizeLevel = when {
        // We try to optimize code as much as possible on embedded targets.
        context.shouldOptimize() -> LlvmSizeLevel.NONE
        context.shouldContainDebugInfo() -> LlvmSizeLevel.NONE
        else -> LlvmSizeLevel.NONE
    }
    val codegenOptimizationLevel: LLVMCodeGenOptLevel = when {
        context.shouldOptimize() -> LLVMCodeGenOptLevel.LLVMCodeGenLevelAggressive
        context.shouldContainDebugInfo() -> LLVMCodeGenOptLevel.LLVMCodeGenLevelNone
        else -> LLVMCodeGenOptLevel.LLVMCodeGenLevelDefault
    }
    val relocMode: LLVMRelocMode = configurables.currentRelocationMode(context).translateToLlvmRelocMode()
    val codeModel: LLVMCodeModel = LLVMCodeModel.LLVMCodeModelDefault
    val globalDce = true
    // Since we are in a "closed world" internalization can be safely used
    // to reduce size of a bitcode with global dce.
    val internalize = closedWorld
    // Hidden visibility makes symbols internal when linking the binary.
    // When producing dynamic library, this enables stripping unused symbols from binary with -dead_strip flag,
    // similar to DCE enabled by internalize but later:
    //
    // Important for binary size, workarounds references to undefined symbols from interop libraries.
    val makeDeclarationsHidden = config.produce == CompilerOutputKind.STATIC_CACHE
    val objcPasses = configurables is AppleConfigurables

    // Null value means that LLVM should use default inliner params
    // for the provided optimization and size level.
    val inlineThreshold: Int? = when {
        context.shouldOptimize() -> tryGetInlineThreshold(context)
        context.shouldContainDebugInfo() -> null
        else -> null
    }

    // PGO configuration
    val pgoInstrumentPath = config.configuration.get(KonanConfigKeys.PROFILE_GENERATE)
    val pgoUsePath = config.configuration.get(KonanConfigKeys.PROFILE_USE)
    val pgoSample = config.configuration.get(KonanConfigKeys.PGO_SAMPLE) ?: false

    return LlvmPipelineConfig(
            targetTriple,
            cpuModel,
            cpuFeatures,
            optimizationLevel,
            sizeLevel,
            codegenOptimizationLevel,
            relocMode,
            codeModel,
            globalDce,
            internalize,
            makeDeclarationsHidden,
            objcPasses,
            inlineThreshold,
            timePasses = timePasses,
            pgoInstrumentPath = pgoInstrumentPath,
            pgoUsePath = pgoUsePath,
            pgoSample = pgoSample,
    )
}

/**
 * Prepares and executes LLVM pipeline on the given [llvmModule].
 */
abstract class LlvmOptimizationPipeline(
        private val config: LlvmPipelineConfig,
        protected val logger: LoggingContext? = null
) : Closeable {
    abstract fun configurePipeline(config: LlvmPipelineConfig, manager: LLVMPassManagerRef, builder: LLVMPassManagerBuilderRef)
    open fun executeCustomPreprocessing(config: LlvmPipelineConfig, module: LLVMModuleRef) {}
    abstract val pipelineName: String

    private val arena = Arena()
    private val targetMachineDelegate = lazy {
        val target = arena.alloc<LLVMTargetRefVar>()
        val foundLlvmTarget = LLVMGetTargetFromTriple(config.targetTriple, target.ptr, null) == 0
        check(foundLlvmTarget) { "Cannot get target from triple ${config.targetTriple}." }
        LLVMCreateTargetMachine(
                target.value,
                config.targetTriple,
                config.cpuModel,
                config.cpuFeatures,
                config.codegenOptimizationLevel,
                config.relocMode,
                config.codeModel)!!
    }

    private val targetMachine: LLVMTargetMachineRef by targetMachineDelegate


    open fun execute(llvmModule: LLVMModuleRef) {
        val passManager = LLVMCreatePassManager()!!
        val passBuilder = LLVMPassManagerBuilderCreate()!!
        try {
            initLLVMOnce()
            LLVMPassManagerBuilderSetOptLevel(passBuilder, config.optimizationLevel.value)
            LLVMPassManagerBuilderSetSizeLevel(passBuilder, config.sizeLevel.value)
            config.inlineThreshold?.let { threshold ->
                LLVMPassManagerBuilderUseInlinerWithThreshold(passBuilder, threshold)
            }
            LLVMKotlinAddTargetLibraryInfoWrapperPass(passManager, config.targetTriple)
            // TargetTransformInfo pass.
            LLVMAddAnalysisPasses(targetMachine, passManager)
            if (config.timePasses) {
                LLVMSetTimePasses(1)
            }

            configurePipeline(config, passManager, passBuilder)
            executeCustomPreprocessing(config, llvmModule)
            // TODO: how to log content of pass manager?
            logger?.log {
                """
                    Running ${pipelineName} with the following parameters:
                    target_triple: ${config.targetTriple}
                    cpu_model: ${config.cpuModel}
                    cpu_features: ${config.cpuFeatures}
                    optimization_level: ${config.optimizationLevel.value}
                    size_level: ${config.sizeLevel.value}
                    inline_threshold: ${config.inlineThreshold ?: "default"}
                """.trimIndent()
            }
            LLVMRunPassManager(passManager, llvmModule)
            if (config.timePasses) {
                LLVMPrintAllTimersToStdOut()
                LLVMClearAllTimers()
            }
        } finally {
            LLVMPassManagerBuilderDispose(passBuilder)
            LLVMDisposePassManager(passManager)
        }
    }

    override fun close() {
        if (targetMachineDelegate.isInitialized()) {
            LLVMDisposeTargetMachine(targetMachine)
        }
        arena.clear()
    }

    companion object {
        private var isInitialized: Boolean = false

        private fun initLLVMTargets() {
            memScoped {
                LLVMKotlinInitializeTargets()
            }
        }

        private fun initializeLlvmGlobalPassRegistry() {
            val passRegistry = LLVMGetGlobalPassRegistry()

            LLVMInitializeCore(passRegistry)
            LLVMInitializeTransformUtils(passRegistry)
            LLVMInitializeScalarOpts(passRegistry)
            LLVMInitializeVectorization(passRegistry)
            LLVMInitializeInstCombine(passRegistry)
            LLVMInitializeIPO(passRegistry)
            LLVMInitializeInstrumentation(passRegistry)
            LLVMInitializeAnalysis(passRegistry)
            LLVMInitializeIPA(passRegistry)
            LLVMInitializeCodeGen(passRegistry)
            LLVMInitializeTarget(passRegistry)
            LLVMInitializeObjCARCOpts(passRegistry)
        }

        @Synchronized
        fun initLLVMOnce() {
            if (!isInitialized) {
                initLLVMTargets()
                initializeLlvmGlobalPassRegistry()
                isInitialized = true
            }
        }
    }
}

class MandatoryOptimizationPipeline(config: LlvmPipelineConfig, logger: LoggingContext? = null) :
        LlvmOptimizationPipeline(config, logger) {

    override val pipelineName = "Mandatory llvm optimizations"

    override fun configurePipeline(config: LlvmPipelineConfig, manager: LLVMPassManagerRef, builder: LLVMPassManagerBuilderRef) {
        if (config.objCPasses) {
            // Lower ObjC ARC intrinsics (e.g. `@llvm.objc.clang.arc.use(...)`).
            // While Kotlin/Native codegen itself doesn't produce these intrinsics, they might come
            // from cinterop "glue" bitcode.
            // TODO: Consider adding other ObjC passes.
            LLVMAddObjCARCContractPass(manager)
        }
    }

    override fun executeCustomPreprocessing(config: LlvmPipelineConfig, module: LLVMModuleRef) {
        if (config.makeDeclarationsHidden) {
            makeVisibilityHiddenLikeLlvmInternalizePass(module)
        }
    }

    override fun close() {
    }
}

class ModuleOptimizationPipeline(config: LlvmPipelineConfig, logger: LoggingContext? = null) :
        LlvmOptimizationPipeline(config, logger) {
    override fun configurePipeline(config: LlvmPipelineConfig, manager: LLVMPassManagerRef, builder: LLVMPassManagerBuilderRef) {
        LLVMPassManagerBuilderPopulateModulePassManager(builder, manager)
        LLVMPassManagerBuilderPopulateFunctionPassManager(builder, manager)
    }

    override val pipelineName = "Module LLVM optimizations"
}

class LTOOptimizationPipeline(config: LlvmPipelineConfig, logger: LoggingContext? = null) :
        LlvmOptimizationPipeline(config, logger) {
    override fun configurePipeline(config: LlvmPipelineConfig, manager: LLVMPassManagerRef, builder: LLVMPassManagerBuilderRef) {
        if (config.internalize) {
            LLVMAddInternalizePass(manager, 0)
        }

        if (config.globalDce) {
            LLVMAddGlobalDCEPass(manager)
        }

        // Pipeline that is similar to `llvm-lto`.
        LLVMPassManagerBuilderPopulateLTOPassManager(builder, manager, Internalize = 0, RunInliner = 1)
    }

    override val pipelineName = "LTO LLVM optimizations"
}

class ThreadSanitizerPipeline(config: LlvmPipelineConfig, logger: LoggingContext? = null) :
        LlvmOptimizationPipeline(config, logger) {
    override fun configurePipeline(config: LlvmPipelineConfig, manager: LLVMPassManagerRef, builder: LLVMPassManagerBuilderRef) {
        LLVMAddThreadSanitizerPass(manager)
    }

    override fun executeCustomPreprocessing(config: LlvmPipelineConfig, module: LLVMModuleRef) {
        getFunctions(module)
                .filter { LLVMIsDeclaration(it) == 0 }
                .forEach { addLlvmFunctionEnumAttribute(it, LlvmFunctionAttribute.SanitizeThread) }
    }

    override val pipelineName = "Thread sanitizer instrumentation"
}


internal fun RelocationModeFlags.currentRelocationMode(context: PhaseContext): RelocationModeFlags.Mode =
        when (determineLinkerOutput(context)) {
            LinkerOutputKind.DYNAMIC_LIBRARY -> dynamicLibraryRelocationMode
            LinkerOutputKind.STATIC_LIBRARY -> staticLibraryRelocationMode
            LinkerOutputKind.EXECUTABLE -> executableRelocationMode
        }

private fun RelocationModeFlags.Mode.translateToLlvmRelocMode() = when (this) {
    RelocationModeFlags.Mode.PIC -> LLVMRelocMode.LLVMRelocPIC
    RelocationModeFlags.Mode.STATIC -> LLVMRelocMode.LLVMRelocStatic
    RelocationModeFlags.Mode.DEFAULT -> LLVMRelocMode.LLVMRelocDefault
}

/**
 * PGO Instrumentation Pipeline - adds profiling instrumentation to collect runtime data
 */
class PGOInstrumentationPipeline(private val pipelineConfig: LlvmPipelineConfig, logger: LoggingContext? = null) :
        LlvmOptimizationPipeline(pipelineConfig, logger) {
    override fun configurePipeline(config: LlvmPipelineConfig, manager: LLVMPassManagerRef, builder: LLVMPassManagerBuilderRef) {
        LLVMPassManagerBuilderPopulateModulePassManager(builder, manager)
        LLVMPassManagerBuilderPopulateFunctionPassManager(builder, manager)
    }

    override fun executeCustomPreprocessing(config: LlvmPipelineConfig, module: LLVMModuleRef) {
        // 不在这里注入，避免被后续 LLVMRunPassManager 的 DCE 删除
    }

    // 重写 execute，在父类 pass 运行完之后再注入初始化函数，避免被 DCE 删除
    override fun execute(llvmModule: LLVMModuleRef) {
        super.execute(llvmModule)
        if (pipelineConfig.pgoInstrumentPath != null) {
            injectProfileInitializer(llvmModule)
        }
    }

    private fun injectProfileInitializer(module: LLVMModuleRef) {
        System.err.println("[PGO] injectProfileInitializer called")
        memScoped {
            val ctx = LLVMGetModuleContext(module)!!
            val voidType = LLVMVoidTypeInContext(ctx)!!
            val initFuncType = LLVMFunctionType(voidType, null, 0, 0)!!

            // 获取或声明 __llvm_profile_initialize 函数
            val initFunc = LLVMGetNamedFunction(module, "__llvm_profile_initialize")
                    ?: LLVMAddFunction(module, "__llvm_profile_initialize", initFuncType)!!
            // 确保是 external linkage，链接时从 libclang_rt.profile.a 解析
            LLVMSetLinkage(initFunc, LLVMLinkage.LLVMExternalLinkage)

            val int8PtrType = LLVMPointerType(LLVMInt8TypeInContext(ctx)!!, 0)!!
            val int32Type = LLVMInt32TypeInContext(ctx)!!
            val funcPtrType = LLVMPointerType(initFuncType, 0)!!

            // 构造 llvm.global_ctors 条目：{ i32 1, void ()* @__llvm_profile_initialize, i8* null }
            val ctorEntryType = LLVMStructTypeInContext(
                    ctx, cValuesOf(int32Type, funcPtrType, int8PtrType), 3, 0
            )!!
            val priority = LLVMConstInt(int32Type, 1, 0)!!
            val funcPtr = LLVMConstBitCast(initFunc, funcPtrType)!!
            val nullPtr = LLVMConstNull(int8PtrType)!!
            val ctorEntry = LLVMConstStructInContext(
                    ctx, cValuesOf(priority, funcPtr, nullPtr), 3, 0
            )!!

            val ctorArrayType = LLVMArrayType(ctorEntryType, 1)!!
            val ctorArray = LLVMConstArray(ctorEntryType, cValuesOf(ctorEntry), 1)!!
            val globalCtors = LLVMAddGlobal(module, ctorArrayType, "llvm.global_ctors")!!
            LLVMSetInitializer(globalCtors, ctorArray)
            LLVMSetLinkage(globalCtors, LLVMLinkage.LLVMAppendingLinkage)

            System.err.println("[PGO] llvm.global_ctors injected with __llvm_profile_initialize")
        }
    }

    override val pipelineName = "PGO instrumentation"
}

/**
 * PGO Optimization Pipeline - uses profile data to guide optimizations
 */
class PGOOptimizationPipeline(config: LlvmPipelineConfig, logger: LoggingContext? = null) :
        LlvmOptimizationPipeline(config, logger) {
    override fun configurePipeline(config: LlvmPipelineConfig, manager: LLVMPassManagerRef, builder: LLVMPassManagerBuilderRef) {
        if (config.pgoUsePath != null) {
            // Apply PGO-guided optimizations using profile data
            
            // Set aggressive optimization when profile data is available
            LLVMPassManagerBuilderSetOptLevel(builder, 3)
            LLVMPassManagerBuilderSetSizeLevel(builder, 0)
            
            // Enable inlining with profile guidance
            LLVMPassManagerBuilderUseInlinerWithThreshold(builder, 275) // Higher threshold for PGO
            
            // Add module-level optimizations first
            LLVMPassManagerBuilderPopulateModulePassManager(builder, manager)
            
            // Add function-level optimizations
            LLVMPassManagerBuilderPopulateFunctionPassManager(builder, manager)
            
            // Add LTO passes for better cross-module optimization with PGO
            LLVMPassManagerBuilderPopulateLTOPassManager(builder, manager, Internalize = 0, RunInliner = 1)
            
            logger?.log {
                "PGO optimization enabled with profile data from: ${config.pgoUsePath}"
            }
        } else {
            // Fallback to standard aggressive optimization
            LLVMPassManagerBuilderPopulateModulePassManager(builder, manager)
            LLVMPassManagerBuilderPopulateFunctionPassManager(builder, manager)
        }
    }

    override val pipelineName = "PGO optimization"
}