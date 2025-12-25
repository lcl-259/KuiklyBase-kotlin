//
// Created by Sergey.Bogolepov on 24.03.2022.
//

#include <CAPIExtensions.h>
#include <llvm/ProfileData/Coverage/CoverageMapping.h>
#include <llvm/ADT/Triple.h>
#include <llvm/Analysis/TargetLibraryInfo.h>
#include <llvm/IR/Constants.h>
#include <llvm/IR/Instructions.h>
#include <llvm/IR/LegacyPassManager.h>
#include <llvm/Transforms/ObjCARC.h>
#include <llvm/Transforms/Utils/Cloning.h>
#include <llvm/Transforms/Instrumentation/ThreadSanitizer.h>
#include <llvm/Support/Timer.h>
#include <llvm/Support/FileSystem.h>
#include <llvm/Support/raw_ostream.h>
#include <system_error>

using namespace llvm;

void LLVMAddObjCARCContractPass(LLVMPassManagerRef passManagerRef) {
    legacy::PassManagerBase *passManager = unwrap(passManagerRef);
    passManager->add(createObjCARCContractPass());
}

void LLVMKotlinInitializeTargets() {
#define INIT_LLVM_TARGET(TargetName) \
    LLVMInitialize##TargetName##TargetInfo();\
    LLVMInitialize##TargetName##Target();\
    LLVMInitialize##TargetName##TargetMC();
#if KONAN_MACOS
    INIT_LLVM_TARGET(AArch64)
    INIT_LLVM_TARGET(ARM)
    INIT_LLVM_TARGET(Mips)
    INIT_LLVM_TARGET(X86)
    INIT_LLVM_TARGET(WebAssembly)
#elif KONAN_LINUX
    INIT_LLVM_TARGET(AArch64)
    INIT_LLVM_TARGET(ARM)
    INIT_LLVM_TARGET(Mips)
    INIT_LLVM_TARGET(X86)
    INIT_LLVM_TARGET(WebAssembly)
#elif KONAN_OHOS
    INIT_LLVM_TARGET(AArch64)
    INIT_LLVM_TARGET(ARM)
    INIT_LLVM_TARGET(Mips)
    INIT_LLVM_TARGET(X86)
    INIT_LLVM_TARGET(WebAssembly)
#elif KONAN_WINDOWS
    INIT_LLVM_TARGET(AArch64)
    INIT_LLVM_TARGET(ARM)
    INIT_LLVM_TARGET(X86)
    INIT_LLVM_TARGET(WebAssembly)
#endif

#undef INIT_LLVM_TARGET
}

void LLVMSetNoTailCall(LLVMValueRef Call) {
    unwrap<CallInst>(Call)->setTailCallKind(CallInst::TCK_NoTail);
}

int LLVMInlineCall(LLVMValueRef call) {
  InlineFunctionInfo IFI;
  return InlineFunction(*unwrap<CallBase>(call), IFI).isSuccess();
}

void LLVMAddThreadSanitizerPass(LLVMPassManagerRef PM) {
  unwrap(PM)->add(createThreadSanitizerLegacyPassPass());
}

void LLVMSetTimePasses(int enabled) {
    llvm::TimePassesIsEnabled = static_cast<bool>(enabled);
}

void LLVMPrintAllTimersToStdOut() {
    llvm::TimerGroup::printAll(llvm::outs());
}

int LLVMPrintAllTimersToFile(const char* filePath) {
    std::error_code EC;
    llvm::raw_fd_ostream fileStream(filePath, EC, llvm::sys::fs::OF_None);

    if (EC) {
        return 1; // Error opening file
    }

    llvm::TimerGroup::printAll(fileStream);
    fileStream.flush();

    return 0; // Success
}

void LLVMClearAllTimers() {
    llvm::TimerGroup::clearAll();
}

void LLVMKotlinAddTargetLibraryInfoWrapperPass(LLVMPassManagerRef passManagerRef, const char* targetTriple) {
  legacy::PassManagerBase *passManager = unwrap(passManagerRef);
  passManager->add(new TargetLibraryInfoWrapperPass(Triple(targetTriple)));
}
