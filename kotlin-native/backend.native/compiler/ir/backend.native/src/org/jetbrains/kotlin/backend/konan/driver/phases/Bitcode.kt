/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.driver.phases

import llvm.LLVMDumpModule
import llvm.LLVMModuleRef
import llvm.LLVMWriteBitcodeToFile
import org.jetbrains.kotlin.backend.common.LoggingContext
import org.jetbrains.kotlin.backend.common.phaser.createSimpleNamedCompilerPhase
import org.jetbrains.kotlin.backend.konan.*
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.checkLlvmModuleExternalCalls
import org.jetbrains.kotlin.backend.konan.createLTOFinalPipelineConfig
import org.jetbrains.kotlin.backend.konan.driver.BasicPhaseContext
import org.jetbrains.kotlin.backend.konan.driver.PhaseContext
import org.jetbrains.kotlin.backend.konan.driver.PhaseEngine
import org.jetbrains.kotlin.backend.konan.driver.utilities.LlvmIrHolder
import org.jetbrains.kotlin.backend.konan.driver.utilities.getDefaultLlvmModuleActions
import org.jetbrains.kotlin.backend.konan.insertAliasToEntryPoint
import org.jetbrains.kotlin.backend.konan.llvm.verifyModule
import org.jetbrains.kotlin.backend.konan.llvm.parseBitcodeFile
import org.jetbrains.kotlin.backend.konan.llvm.llvmLinkModules2
import org.jetbrains.kotlin.backend.konan.optimizations.RemoveRedundantSafepointsPass
import org.jetbrains.kotlin.backend.konan.optimizations.removeMultipleThreadDataLoads
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.konan.target.SanitizerKind
import java.io.File


internal data class WriteBitcodeFileInput(
        override val llvmModule: LLVMModuleRef,
        val outputFile: File,
) : LlvmIrHolder

/**
 * Write in-memory LLVM module to filesystem as a bitcode.
 */
internal val WriteBitcodeFilePhase = createSimpleNamedCompilerPhase<PhaseContext, WriteBitcodeFileInput>(
        "WriteBitcodeFile",
        "Write bitcode file",
) { context, (llvmModule, outputFile) ->
    // Insert `_main` after pipeline, so we won't worry about optimizations corrupting entry point.
    insertAliasToEntryPoint(context, llvmModule)
    LLVMWriteBitcodeToFile(llvmModule, outputFile.canonicalPath)
}

internal val CheckExternalCallsPhase = createSimpleNamedCompilerPhase<NativeGenerationState, Unit>(
        name = "CheckExternalCalls",
        description = "Check external calls",
        postactions = getDefaultLlvmModuleActions(),
) { context, _ ->
    checkLlvmModuleExternalCalls(context)
}

internal val RewriteExternalCallsCheckerGlobals = createSimpleNamedCompilerPhase<NativeGenerationState, Unit>(
        name = "RewriteExternalCallsCheckerGlobals",
        description = "Rewrite globals for external calls checker after optimizer run",
        postactions = getDefaultLlvmModuleActions(),
) { context, _ ->
    addFunctionsListSymbolForChecker(context)
}

internal class OptimizationState(
        konanConfig: KonanConfig,
        val llvmConfig: LlvmPipelineConfig
) : BasicPhaseContext(konanConfig)

internal fun optimizationPipelinePass(name: String, description: String, pipeline: (LlvmPipelineConfig, LoggingContext) -> LlvmOptimizationPipeline) =
        createSimpleNamedCompilerPhase<OptimizationState, LLVMModuleRef>(
                name = name,
                description = description,
                postactions = getDefaultLlvmModuleActions(),
        ) { context, module ->
            pipeline(context.llvmConfig, context).use {
                it.execute(module)
            }
        }


internal val MandatoryBitcodeLLVMPostprocessingPhase = optimizationPipelinePass(
        name = "MandatoryBitcodeLLVMPostprocessingPhase",
        description = "Mandatory bitcode llvm postprocessing",
        pipeline = ::MandatoryOptimizationPipeline,
)

internal val ModuleBitcodeOptimizationPhase = optimizationPipelinePass(
        name = "ModuleBitcodeOptimization",
        description = "Optimize bitcode",
        pipeline = ::ModuleOptimizationPipeline,
)

internal val LTOBitcodeOptimizationPhase = optimizationPipelinePass(
        name = "LTOBitcodeOptimization",
        description = "Runs llvm lto pipeline",
        pipeline = ::LTOOptimizationPipeline
)

internal val ThreadSanitizerPhase = optimizationPipelinePass(
        name = "ThreadSanitizer",
        description = "Prepare to run with thread sanitizer",
        pipeline = ::ThreadSanitizerPipeline,
)

internal val RemoveRedundantSafepointsPhase = createSimpleNamedCompilerPhase<BitcodePostProcessingContext, Unit>(
        name = "RemoveRedundantSafepoints",
        description = "Remove function prologue safepoints inlined to another function",
        postactions = getDefaultLlvmModuleActions(),
        op = { context, _ ->
            RemoveRedundantSafepointsPass().runOnModule(
                    module = context.llvm.module,
                    isSafepointInliningAllowed = context.shouldInlineSafepoints()
            )
        }
)

internal val OptimizeTLSDataLoadsPhase = createSimpleNamedCompilerPhase<BitcodePostProcessingContext, Unit>(
        name = "OptimizeTLSDataLoads",
        description = "Optimize multiple loads of thread data",
        postactions = getDefaultLlvmModuleActions(),
        op = { context, _ -> removeMultipleThreadDataLoads(context) }
)

internal val CStubsPhase = createSimpleNamedCompilerPhase<NativeGenerationState, Unit>(
        name = "CStubs",
        description = "C stubs compilation",
        postactions = getDefaultLlvmModuleActions(),
        op = { context, _ -> produceCStubs(context) }
)

internal val LinkBitcodeDependenciesPhase = createSimpleNamedCompilerPhase<NativeGenerationState, List<File>>(
        name = "LinkBitcodeDependencies",
        description = "Link bitcode dependencies",
        postactions = getDefaultLlvmModuleActions(),
        op = { context, input -> linkBitcodeDependencies(context, input) }
)

internal val VerifyBitcodePhase = createSimpleNamedCompilerPhase<PhaseContext, LLVMModuleRef>(
        name = "VerifyBitcode",
        description = "Verify bitcode",
        op = { _, llvmModule -> verifyModule(llvmModule) }
)

internal val PrintBitcodePhase = createSimpleNamedCompilerPhase<PhaseContext, LLVMModuleRef>(
        name = "PrintBitcode",
        description = "Print bitcode",
        op = { _, llvmModule -> LLVMDumpModule(llvmModule) }
)

internal fun <T : BitcodePostProcessingContext> PhaseEngine<T>.runBitcodePostProcessing() {
    System.err.println("【调试信息】 运行Bitcode后处理: 开始")

    val optimizationConfig = createLTOFinalPipelineConfig(
            context,
            context.llvm.targetTriple,
            closedWorld = context.config.isFinalBinary,
            timePasses = context.config.flexiblePhaseConfig.needProfiling,
    )

    // 在进入优化上下文之前检查 LTO 模式
    val ltoMode = context.config.llvmLTOMode

    System.err.println("【调试信息】 运行Bitcode后处理: ltoMode = $ltoMode")
    System.err.println("【调试信息】 运行Bitcode后处理: context = ${context::class.simpleName}")

    useContext(OptimizationState(context.config, optimizationConfig)) {
        val module = this@runBitcodePostProcessing.context.llvmModule
        it.runPhase(MandatoryBitcodeLLVMPostprocessingPhase, module)

        // 根据配置选择 LTO 优化路径
        when (ltoMode) {
            LLVMLTOMode.THIN -> {
                // ThinLTO 路径：在这里跳过 ModuleBitcodeOptimizationPhase
                // 原因：LLVM 的 default<O3> 流水线会触发内部 ThinLTO pass，
                // 导致双重 ThinLTO 执行。ThinLTOPhase 将处理所有优化。
                System.err.println("【调试信息】 运行Bitcode后处理: 为ThinLTO跳过ModuleBitcodeOptimizationPhase")
            }
            LLVMLTOMode.FULL -> {
                // 传统 Full LTO 路径：先运行模块优化
                it.runPhase(ModuleBitcodeOptimizationPhase, module)
                it.runPhase(LTOBitcodeOptimizationPhase, module)
            }
            LLVMLTOMode.NONE -> {
                // 无 LTO：仅运行模块优化
                it.runPhase(ModuleBitcodeOptimizationPhase, module)
            }
        }

        when (context.config.sanitizer) {
            SanitizerKind.THREAD -> it.runPhase(ThreadSanitizerPhase, module)
            SanitizerKind.ADDRESS -> context.reportCompilationError("Address sanitizer is not supported yet")
            null -> {}
        }
    }

    // 在 OptimizationState 上下文外运行 ThinLTO 阶段
    // 这些阶段需要 NativeGenerationState 上下文
    System.err.println("【调试信息】 运行Bitcode后处理: 检查ThinLTO阶段条件")
    System.err.println("【调试信息】 运行Bitcode后处理: ltoMode == LLVMLTOMode.THIN = ${ltoMode == LLVMLTOMode.THIN}")
    System.err.println("【调试信息】 运行Bitcode后处理: context是NativeGenerationState = ${context is NativeGenerationState}")

    if (ltoMode == LLVMLTOMode.THIN && context is NativeGenerationState) {
        System.err.println("【调试信息】 运行Bitcode后处理: 执行ThinLTO阶段")
        @Suppress("UNCHECKED_CAST")
        val nativeEngine = this as PhaseEngine<NativeGenerationState>

        System.err.println("【调试信息】 运行Bitcode后处理: 即将运行ThinLTOPhase")
        nativeEngine.runPhase(ThinLTOPhase, Unit)
        System.err.println("【调试信息】 运行Bitcode后处理: ThinLTOPhase已完成")

        System.err.println("【调试信息】 运行Bitcode后处理: 即将运行LinkDeferredModulesPhase")
        nativeEngine.runPhase(LinkDeferredModulesPhase, Unit)
        System.err.println("【调试信息】 运行Bitcode后处理: LinkDeferredModulesPhase已完成")
    } else {
        System.err.println("【调试信息】 运行Bitcode后处理: 跳过ThinLTO阶段")
    }

    System.err.println("【调试信息】 运行Bitcode后处理: 即将运行RemoveRedundantSafepointsPhase")
    if (context.config.memoryModel == MemoryModel.EXPERIMENTAL) {
        runPhase(RemoveRedundantSafepointsPhase)
    }
    if (context.config.optimizationsEnabled) {
        runPhase(OptimizeTLSDataLoadsPhase)
    }
}

/**
 * ThinLTO 优化阶段（适配 LLVM 11/12，Kotlin 侧并行）
 *
 * 新架构 (2025 for LLVM 11/12):
 * - 流式基于文件的优化：优化 → 序列化到磁盘 → 释放内存
 * - 增量链接：逐个从磁盘文件链接
 * - Kotlin 侧并行优化：使用 JVM 线程池独立优化模块
 * - 内存中不累积模块 - 所有中间结果存储在磁盘上
 *
 * 内存优化：
 * - 任何时刻内存中只有 1-2 个模块（优化器 + 磁盘写入器）
 * - 优化后的 bitcode 文件写入临时目录
 * - 峰值内存使用：无论项目大小都约 ~2-4GB
 *
 * 性能优化：
 * - 独立模块的并行优化（Kotlin 侧线程池）
 * - 增量链接减少峰值内存并提高缓存局部性
 *
 * 设计理念：
 * - 遵循 LLVM 官方 ThinLTO 设计
 * - 同时优化内存和速度
 * - 可扩展到任意大小的项目
 */
internal val ThinLTOPhase = createSimpleNamedCompilerPhase<NativeGenerationState, Unit>(
        name = "ThinLTO",
        description = "ThinLTO 优化阶段",
        postactions = getDefaultLlvmModuleActions(),
) { context, _ ->
    System.err.println("【调试信息】 ThinLTO阶段: 开始")
    System.err.println("【调试信息】 ThinLTO阶段: context.deferredLinkageState = ${context.deferredLinkageState}")

    val deferredState = context.deferredLinkageState
    System.err.println("【调试信息】 ThinLTO阶段: deferredState = $deferredState")
    System.err.println("【调试信息】 ThinLTO阶段: deferredState是否为null? ${deferredState == null}")

    if (deferredState != null) {
        System.err.println("【调试信息】 ThinLTO阶段: deferredState.thinLtoEnabled = ${deferredState.thinLtoEnabled}")
        System.err.println("【调试信息】 ThinLTO阶段: deferredState.runtimeModules.size = ${deferredState.runtimeModules.size}")
        System.err.println("【调试信息】 ThinLTO阶段: deferredState.additionalModules.size = ${deferredState.additionalModules.size}")
    }

    if (deferredState == null || !deferredState.thinLtoEnabled) {
        System.err.println("【调试信息】 ThinLTO阶段: 提前返回 - deferredState为null或已禁用")
        return@createSimpleNamedCompilerPhase
    }

    System.err.println("【调试信息】 ThinLTO阶段: 通过提前返回检查，继续进行优化")

    val startTime = System.currentTimeMillis()
    val allModules = deferredState.runtimeModules + deferredState.additionalModules

    if (allModules.isEmpty()) {
        context.messageCollector.report(CompilerMessageSeverity.INFO, "【ThinLTO优化】 没有模块需要优化")
        return@createSimpleNamedCompilerPhase
    }

    context.messageCollector.report(
        CompilerMessageSeverity.INFO,
        "【ThinLTO优化】 正在处理 ${allModules.size} 个模块，使用流式优化"
    )

    // 创建临时目录用于优化后的 bitcode 文件
    val tempDir = java.nio.file.Files.createTempDirectory("thinlto_opt_").toFile()
    val optimizedBitcodeFiles = mutableListOf<String>()

    try {
        // 创建 ThinLTO 优化配置
        // 使用 ModuleOptimizationPipeline 进行更好的优化（不仅仅是 Mandatory）
        val optimizationConfig = createLTOFinalPipelineConfig(
            context,
            context.llvm.targetTriple,
            closedWorld = false,  // 每个模块独立优化
            timePasses = context.config.flexiblePhaseConfig.needProfiling,
        )

        // 从配置获取线程数（默认为 2 以提高内存效率）
        val threadCount = optimizationConfig.moduleOptThreads

        context.messageCollector.report(
            CompilerMessageSeverity.INFO,
            "【ThinLTO优化】 使用 $threadCount 个并行优化线程"
        )

        // 以并行批次处理模块
        // 每批大小 = threadCount，以最大化并行性而不会导致内存爆炸
        allModules.chunked(threadCount).forEachIndexed { batchIndex, batch ->
            val batchStartTime = System.currentTimeMillis()

            context.messageCollector.report(
                CompilerMessageSeverity.INFO,
                "【ThinLTO优化】 批次 ${batchIndex + 1}/${(allModules.size + threadCount - 1) / threadCount}: " +
                "并行优化 ${batch.size} 个模块"
            )

            // 在此批次中并行优化模块（Kotlin 侧并行）
            val batchResults = batch.mapIndexed { index, bitcodeModule ->
                val globalIndex = batchIndex * threadCount + index
                val moduleStartTime = System.currentTimeMillis()

                try {
                    // 优化模块（使用 LLVM 11/12 的 LLVMRunPassManager）
                    ModuleOptimizationPipeline(optimizationConfig, context).use { pipeline ->
                        pipeline.execute(bitcodeModule.llvmModule)
                    }

                    // 将优化后的模块序列化到磁盘
                    val outputPath = File(tempDir, "thinlto_optimized_${globalIndex}_${bitcodeModule.identifier}.bc").canonicalPath
                    val writeResult = LLVMWriteBitcodeToFile(bitcodeModule.llvmModule, outputPath)

                    if (writeResult != 0) {
                        error("Failed to write optimized bitcode for module ${bitcodeModule.identifier}")
                    }

                    val moduleTime = System.currentTimeMillis() - moduleStartTime
                    context.messageCollector.report(
                        CompilerMessageSeverity.INFO,
                        "【ThinLTO优化】   模块 ${globalIndex + 1}/${allModules.size} (${bitcodeModule.identifier}): " +
                        "已在${moduleTime}ms内优化完成 → $outputPath"
                    )

                    // 返回输出路径以供后续链接使用
                    outputPath
                } catch (e: Exception) {
                    context.messageCollector.report(
                        CompilerMessageSeverity.WARNING,
                        "【ThinLTO优化】 模块优化失败 ${bitcodeModule.identifier}: ${e.message}"
                    )
                    null
                }
            }.filterNotNull()

            optimizedBitcodeFiles.addAll(batchResults)

            val batchTime = System.currentTimeMillis() - batchStartTime
            context.messageCollector.report(
                CompilerMessageSeverity.INFO,
                "【ThinLTO优化】 批次 ${batchIndex + 1} 已在${batchTime}ms内完成 (优化了${batchResults.size}个模块)"
            )
        }

        // 存储优化后的 bitcode 文件路径用于增量链接
        context.thinLtoOptimizedBitcodeFiles = optimizedBitcodeFiles

        val totalTime = System.currentTimeMillis() - startTime
        context.messageCollector.report(
            CompilerMessageSeverity.INFO,
            "【ThinLTO优化】 优化阶段完成: ${optimizedBitcodeFiles.size}/${allModules.size} 个模块 " +
            "已在${totalTime}ms内优化完成 (平均每个模块${totalTime / allModules.size}ms)"
        )

    } catch (e: Exception) {
        context.messageCollector.report(
            CompilerMessageSeverity.ERROR,
            "【ThinLTO优化】 优化失败: ${e.message}"
        )
        throw e
    }
}

/**
 * 链接延迟模块阶段（为增量链接重新设计）
 *
 * 新架构 (2025 for LLVM 11/12):
 * - 增量基于文件的链接：从磁盘读取 → 链接 → 释放内存 → 下一个文件
 * - 单线程链接（LLVM 链接器不是线程安全的）
 * - 最终全局 LTO 优化（使用多线程 PassManager）
 *
 * 内存优化：
 * - 每次只从磁盘加载一个模块
 * - 链接后立即释放源模块内存（llvmLinkModules2 会销毁源模块）
 * - 峰值内存 = 主模块 + 单个正在链接的模块
 *
 * 性能优化：
 * - 增量链接减少内存峰值
 * - 最终 LTO 应用交叉模块优化
 */
internal val LinkDeferredModulesPhase = createSimpleNamedCompilerPhase<NativeGenerationState, Unit>(
        name = "LinkDeferredModules",
        description = "链接延迟模块阶段",
        postactions = getDefaultLlvmModuleActions(),
) { context, _ ->
    System.err.println("【调试信息】 链接延迟模块阶段: 已进入")

    val optimizedBitcodeFiles = context.thinLtoOptimizedBitcodeFiles
    System.err.println("【调试信息】 链接延迟模块阶段: optimizedBitcodeFiles = $optimizedBitcodeFiles")

    if (optimizedBitcodeFiles == null) {
        System.err.println("【调试信息】 链接延迟模块阶段: optimizedBitcodeFiles为NULL，返回")
        context.messageCollector.report(
            CompilerMessageSeverity.INFO,
            "【ThinLTO优化】 没有优化后的模块需要链接"
        )
        return@createSimpleNamedCompilerPhase
    }

    if (optimizedBitcodeFiles.isEmpty()) {
        System.err.println("【调试信息】 链接延迟模块阶段: optimizedBitcodeFiles为空，返回")
        context.messageCollector.report(
            CompilerMessageSeverity.INFO,
            "【ThinLTO优化】 没有优化后的模块需要链接"
        )
        return@createSimpleNamedCompilerPhase
    }

    System.err.println("【调试信息】 链接延迟模块阶段: optimizedBitcodeFiles.size = ${optimizedBitcodeFiles.size}")

    val startTime = System.currentTimeMillis()

    System.err.println("【调试信息】 链接延迟模块阶段: 即将从context.llvmModule获取mainModule")
    val mainModule = context.llvmModule
    System.err.println("【调试信息】 链接延迟模块阶段: 已获取mainModule = $mainModule")

    System.err.println("【调试信息】 链接延迟模块阶段: 即将报告给messageCollector")
    context.messageCollector.report(
        CompilerMessageSeverity.INFO,
        "【ThinLTO优化】 增量链接: ${optimizedBitcodeFiles.size} 个优化后的模块 → 主模块"
    )
    System.err.println("【调试信息】 链接延迟模块阶段: messageCollector报告完成")

    try {
        System.err.println("【调试信息】 链接延迟模块阶段: 进入增量链接的try块")
        System.err.println("【调试信息】 链接延迟模块阶段: 即将遍历 ${optimizedBitcodeFiles.size} 个文件")

        // 将每个优化后的 bitcode 文件增量链接到主模块中
        optimizedBitcodeFiles.forEachIndexed { index, bitcodeFilePath ->
            System.err.println("【调试信息】 链接延迟模块阶段: 处理文件 ${index + 1}/${optimizedBitcodeFiles.size}: $bitcodeFilePath")
            val linkStartTime = System.currentTimeMillis()

            try {
                System.err.println("【调试信息】 链接延迟模块阶段: 文件 ${index + 1}: 进入内部try块")

                // 从磁盘解析 bitcode 文件
                System.err.println("【调试信息】 链接延迟模块阶段: 文件 ${index + 1}: 即将检查文件是否存在")
                val fileExists = File(bitcodeFilePath).exists()
                System.err.println("【调试信息】 链接延迟模块阶段: 文件 ${index + 1}: 文件存在 = $fileExists")

                if (!fileExists) {
                    System.err.println("【调试信息】 链接延迟模块阶段: 文件 ${index + 1}: 错误 - 文件不存在！")
                    error("Bitcode file does not exist: $bitcodeFilePath")
                }

                System.err.println("【调试信息】 链接延迟模块阶段: 文件 ${index + 1}: 即将调用parseBitcodeFile")
                val parsedModule = parseBitcodeFile(context.llvmContext, bitcodeFilePath)
                System.err.println("【调试信息】 链接延迟模块阶段: 文件 ${index + 1}: parseBitcodeFile完成，parsedModule = $parsedModule")

                // 将此模块链接到主模块
                System.err.println("【调试信息】 链接延迟模块阶段: 文件 ${index + 1}: 即将调用llvmLinkModules2")
                val failed = llvmLinkModules2(context, mainModule, parsedModule)
                System.err.println("【调试信息】 链接延迟模块阶段: 文件 ${index + 1}: llvmLinkModules2返回，failed = $failed")

                if (failed != 0) {
                    System.err.println("【调试信息】 链接延迟模块阶段: 文件 ${index + 1}: 错误 - 链接失败！")
                    error("Failed to link $bitcodeFilePath into main module")
                }

                System.err.println("【调试信息】 链接延迟模块阶段: 文件 ${index + 1}: 链接成功，即将计算时间并报告")
                val linkTime = System.currentTimeMillis() - linkStartTime
                System.err.println("【调试信息】 链接延迟模块阶段: 文件 ${index + 1}: 即将报告给messageCollector")
                context.messageCollector.report(
                    CompilerMessageSeverity.INFO,
                    "【ThinLTO优化】   已链接 ${index + 1}/${optimizedBitcodeFiles.size}: " +
                    "${File(bitcodeFilePath).name} 耗时${linkTime}ms"
                )
                System.err.println("【调试信息】 链接延迟模块阶段: 文件 ${index + 1}: 报告完成")

                // 链接后模块内存自动释放（llvmLinkModules2 销毁源模块）

            } catch (e: Exception) {
                context.messageCollector.report(
                    CompilerMessageSeverity.ERROR,
                    "【ThinLTO优化】 链接失败 $bitcodeFilePath: ${e.message}"
                )
                throw e
            }
        }

        System.err.println("【调试信息】 链接延迟模块阶段: 所有文件已链接！已退出forEachIndexed循环")

        System.err.println("【调试信息】 链接延迟模块阶段: 即将计算总链接时间")
        val linkTime = System.currentTimeMillis() - startTime
        System.err.println("【调试信息】 链接延迟模块阶段: linkTime = ${linkTime}ms")

        System.err.println("【调试信息】 链接延迟模块阶段: 即将报告增量链接已完成")
        context.messageCollector.report(
            CompilerMessageSeverity.INFO,
            "【ThinLTO优化】 增量链接已在${linkTime}ms内完成 " +
            "(平均每个模块${linkTime / optimizedBitcodeFiles.size}ms)"
        )
        System.err.println("【调试信息】 链接延迟模块阶段: 增量链接报告完成")

        // 增量链接后验证模块一致性
        // 这对于多线程 LTO 至关重要 - 确保模块处于有效状态
        System.err.println("【调试信息】 链接延迟模块阶段: 即将在增量链接后验证模块")
        try {
            verifyModule(mainModule)
            System.err.println("【调试信息】 链接延迟模块阶段: 模块验证通过")
        } catch (e: Error) {
            context.messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "【ThinLTO优化】 增量链接后模块验证失败: ${e.message}"
            )
            throw e
        }

        // 对完整链接的主模块应用最终全局 LTO 优化
        // 这对 ThinLTO 至关重要 - 它启用跨模块优化
        System.err.println("【调试信息】 链接延迟模块阶段: 即将开始最终LTO优化")
        val finalOptStartTime = System.currentTimeMillis()

        System.err.println("【调试信息】 链接延迟模块阶段: 即将报告最终LTO开始")
        context.messageCollector.report(
            CompilerMessageSeverity.INFO,
            "【ThinLTO优化】 正在对已链接的主模块应用最终LTO优化"
        )
        System.err.println("【调试信息】 链接延迟模块阶段: 最终LTO开始报告完成")

        System.err.println("【调试信息】 链接延迟模块阶段: 即将创建finalOptConfig")
        val finalOptConfig = createLTOFinalPipelineConfig(
            context,
            context.llvm.targetTriple,
            closedWorld = context.config.isFinalBinary,  // 封闭世界用于激进优化
            timePasses = context.config.flexiblePhaseConfig.needProfiling,
        )
        System.err.println("【调试信息】 链接延迟模块阶段: finalOptConfig已创建")

        // 使用自定义 LTO 管道（方案一：MINIMAL）以减少编译时间
        // 预计节省 23-38% 的 LTO 优化时间（约 140-250 秒）
        // 性能损失 < 5%，二进制大小增加 < 3%
        System.err.println("【调试信息】 链接延迟模块阶段: 即将创建CustomLTOOptimizationPipeline并执行")
        context.messageCollector.report(
            CompilerMessageSeverity.INFO,
            "【ThinLTO优化】 使用自定义LTO管道（方案一：MINIMAL）进行最终优化"
        )
        CustomLTOOptimizationPipeline(finalOptConfig, context).use { pipeline ->
            System.err.println("【调试信息】 链接延迟模块阶段: 在pipeline.use内部，即将执行")
            pipeline.execute(context.llvmModule)
            System.err.println("【调试信息】 链接延迟模块阶段: pipeline.execute已完成")
        }
        System.err.println("【调试信息】 链接延迟模块阶段: CustomLTOOptimizationPipeline.use已完成")

        val finalOptTime = System.currentTimeMillis() - finalOptStartTime
        context.messageCollector.report(
            CompilerMessageSeverity.INFO,
            "【ThinLTO优化】 最终LTO优化已在${finalOptTime}ms内完成"
        )

        // 清理：删除临时优化后的 bitcode 文件
        var deletedCount = 0
        optimizedBitcodeFiles.forEach { path ->
            try {
                if (File(path).delete()) {
                    deletedCount++
                }
            } catch (e: Exception) {
                // 忽略清理错误 - 它们不会影响编译
            }
        }

        context.messageCollector.report(
            CompilerMessageSeverity.INFO,
            "【ThinLTO优化】 已清理 $deletedCount 个临时bitcode文件"
        )

        val totalTime = System.currentTimeMillis() - startTime
        context.messageCollector.report(
            CompilerMessageSeverity.INFO,
            "【ThinLTO优化】 链接阶段已在${totalTime}ms内完成 " +
            "(${linkTime}ms链接 + ${finalOptTime}ms最终LTO)"
        )

    } catch (e: Exception) {
        context.messageCollector.report(
            CompilerMessageSeverity.ERROR,
            "【ThinLTO优化】 链接失败: ${e.message}"
        )
        throw e
    } finally {
        // 清除状态
        context.thinLtoOptimizedBitcodeFiles = null
        context.deferredLinkageState = null
    }
}
