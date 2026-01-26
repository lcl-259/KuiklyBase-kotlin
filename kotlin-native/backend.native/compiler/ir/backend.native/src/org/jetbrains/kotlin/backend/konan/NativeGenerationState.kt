/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan

import llvm.*
import org.jetbrains.kotlin.backend.common.phaser.BackendContextHolder
import org.jetbrains.kotlin.backend.common.serialization.FingerprintHash
import org.jetbrains.kotlin.backend.common.serialization.Hash128Bits
import org.jetbrains.kotlin.backend.konan.driver.BasicPhaseContext
import org.jetbrains.kotlin.backend.konan.driver.PhaseContext
import org.jetbrains.kotlin.backend.konan.driver.utilities.LlvmIrHolder
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.backend.konan.objcexport.ObjCExport
import org.jetbrains.kotlin.backend.konan.serialization.SerializedClassFields
import org.jetbrains.kotlin.backend.konan.serialization.SerializedEagerInitializedFile
import org.jetbrains.kotlin.backend.konan.serialization.SerializedInlineFunctionReference
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrSuspensionPoint

/**
 * LLVM 链接时优化 (LTO) 模式
 */
enum class LLVMLTOMode {
    NONE,  // 不使用 LTO
    FULL,  // 传统的完整 LTO（单体优化）
    THIN   // ThinLTO（分布式、可并行的 LTO）
}

/**
 * 表示 ThinLTO 处理的单个 bitcode 模块
 * 每个模块对应一个 Kotlin 源文件或依赖项
 */
internal data class BitcodeModule(
    val identifier: String,          // 唯一标识符（例如：文件路径或模块名称）
    val llvmModule: LLVMModuleRef,   // LLVM 模块引用
    val bitcodeData: ByteArray? = null // 序列化的 bitcode，用于线程安全处理
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BitcodeModule) return false

        if (identifier != other.identifier) return false
        if (llvmModule != other.llvmModule) return false
        if (bitcodeData != null) {
            if (other.bitcodeData == null) return false
            if (!bitcodeData.contentEquals(other.bitcodeData)) return false
        } else if (other.bitcodeData != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = identifier.hashCode()
        result = 31 * result + llvmModule.hashCode()
        result = 31 * result + (bitcodeData?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * 保存 ThinLTO 模式下延迟链接的模块和元数据
 * 模块保持独立状态，直到 ThinLTO 优化之后才链接
 */
internal data class DeferredLinkageState(
    val runtimeModules: List<BitcodeModule>,     // 运行时和标准库模块
    val additionalModules: List<BitcodeModule>,  // 用户代码和依赖项
    val thinLtoEnabled: Boolean = false          // 是否启用 ThinLTO
)

internal class InlineFunctionOriginInfo(val irFunction: IrFunction, val irFile: IrFile, val startOffset: Int, val endOffset: Int)

internal class FileLowerState {
    private var functionReferenceCount = 0
    private var coroutineCount = 0
    private var cStubCount = 0

    fun getFunctionReferenceImplUniqueName(targetFunction: IrFunction): String =
            getFunctionReferenceImplUniqueName("${targetFunction.name}\$FUNCTION_REFERENCE\$")

    fun getCoroutineImplUniqueName(function: IrFunction): String =
            "${function.name}COROUTINE\$${coroutineCount++}"

    fun getFunctionReferenceImplUniqueName(prefix: String) =
            "$prefix${functionReferenceCount++}"

    fun getCStubUniqueName(prefix: String) =
            "$prefix${cStubCount++}"
}

internal interface BitcodePostProcessingContext : PhaseContext, LlvmIrHolder {
    val llvm: BasicLlvmHelpers
    val llvmContext: LLVMContextRef
}

internal class BitcodePostProcessingContextImpl(
        config: KonanConfig,
        override val llvmModule: LLVMModuleRef,
        override val llvmContext: LLVMContextRef
) : BitcodePostProcessingContext, BasicPhaseContext(config) {
    override val llvm: BasicLlvmHelpers = BasicLlvmHelpers(this, llvmModule)
}

internal class NativeGenerationState(
        config: KonanConfig,
        // TODO: Get rid of this property completely once transition to the dynamic driver is complete.
        //  It will reduce code coupling and make it easier to create NativeGenerationState instances.
        val context: Context,
        val cacheDeserializationStrategy: CacheDeserializationStrategy?,
        val dependenciesTracker: DependenciesTracker,
        val llvmModuleSpecification: LlvmModuleSpecification,
        val outputFiles: OutputFiles,
        val llvmModuleName: String,
) : BasicPhaseContext(config), BackendContextHolder, LlvmIrHolder, BitcodePostProcessingContext {
    val outputFile = outputFiles.mainFileName

    var klibHash: FingerprintHash = FingerprintHash(Hash128Bits(0U, 0U))

    val inlineFunctionBodies = mutableListOf<SerializedInlineFunctionReference>()
    val classFields = mutableListOf<SerializedClassFields>()
    val eagerInitializedFiles = mutableListOf<SerializedEagerInitializedFile>()
    val calledFromExportedInlineFunctions = mutableSetOf<IrFunction>()
    val constructedFromExportedInlineFunctions = mutableSetOf<IrClass>()
    val inlineFunctionOrigins = mutableMapOf<IrFunction, InlineFunctionOriginInfo>()
    val liveVariablesAtSuspensionPoints = mutableMapOf<IrSuspensionPoint, List<IrVariable>>()
    val visibleVariablesAtSuspensionPoints = mutableMapOf<IrSuspensionPoint, List<IrVariable>>()

    // ThinLTO 相关状态
    // 保存延迟链接的模块状态，在 ThinLTO 优化之前收集，优化之后链接
    var deferredLinkageState: DeferredLinkageState? = null
    // 保存优化后的 bitcode 文件路径列表，用于增量链接
    var thinLtoOptimizedBitcodeFiles: List<String>? = null

    private val localClassNames = mutableMapOf<IrAttributeContainer, String>()
    fun getLocalClassName(container: IrAttributeContainer): String? = localClassNames[container.attributeOwnerId]
    fun putLocalClassName(container: IrAttributeContainer, name: String) {
        localClassNames[container.attributeOwnerId] = name
    }
    fun copyLocalClassName(source: IrAttributeContainer, destination: IrAttributeContainer) {
        getLocalClassName(source)?.let { name -> putLocalClassName(destination, name) }
    }

    lateinit var fileLowerState: FileLowerState

    val producedLlvmModuleContainsStdlib get() = llvmModuleSpecification.containsModule(context.stdlibModule)

    private val runtimeDelegate = lazy { Runtime(llvmContext, config.distribution.compilerInterface(config.target)) }
    private val llvmDelegate = lazy { CodegenLlvmHelpers(this, LLVMModuleCreateWithNameInContext(llvmModuleName, llvmContext)!!) }
    private val debugInfoDelegate = lazy { DebugInfo(this) }

    override val llvmContext = LLVMContextCreate()!!
    val runtime by runtimeDelegate
    override val llvm by llvmDelegate
    val debugInfo by debugInfoDelegate
    val cStubsManager = CStubsManager(config.target, this)
    lateinit var llvmDeclarations: LlvmDeclarations

    val virtualFunctionTrampolines = mutableMapOf<IrSimpleFunction, LlvmCallable>()

    lateinit var objCExport: ObjCExport

    fun hasDebugInfo() = debugInfoDelegate.isInitialized()

    private var isDisposed = false

    // Both NativeGenerationState and Context could be used for logging purposes.
    // Unfortunately, only NativeGenerationState is used as a PhaseContext, so logging in Context
    // will do nothing. Workaround that by setting inVerbosePhase of "parent" context.
    //
    // A proper solution would be decoupling of logging, error reporting, etc. into a separate (PhaseEnvironment?) object.
    override var inVerbosePhase: Boolean
        get() = super.inVerbosePhase
        set(value) {
            super.inVerbosePhase = value
            context.inVerbosePhase = value
        }

    override fun dispose() {
        if (isDisposed) return

        if (hasDebugInfo()) {
            LLVMDisposeDIBuilder(debugInfo.builder)
        }
        if (llvmDelegate.isInitialized()) {
            LLVMDisposeModule(llvm.module)
        }
        if (runtimeDelegate.isInitialized()) {
            LLVMDisposeTargetData(runtime.targetData)
            LLVMDisposeModule(runtime.llvmModule)
        }
        LLVMContextDispose(llvmContext)

        isDisposed = true
    }

    override val heldBackendContext: Context
        get() = context

    override val llvmModule: LLVMModuleRef
        get() = llvm.module
}
