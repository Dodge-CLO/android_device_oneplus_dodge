/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

// Loaded as a DT_NEEDED of libAlgoProcess.so. Redirects some of its PLT slots:
//
// - yuv420sp2rgbaNeon: the quick camera bitmap is a 0x23 buffer with VU chroma
//   that the converter reads as UV (cyan preview), so hand it a UV copy.
//   Portrait frames are already UV and are converted as they are. The APS
//   mode of the capture is at +0x30 of the buffer_info_t (index into the
//   algo switch config: 0 common, 1 night, 2 portrait, ...).
//   debug.apsfixup.rgbuv=0 disables this; debug.apsfixup.rgbkeep lists modes
//   (comma-separated) whose bitmaps are already UV and are left as they are.
// - hwJpegEncodec + camApsBufferLockPlanes: the front portrait quick JPEG
//   (hwJpegEncodec with quick = 1) is encoded from a 3076x4096 frame whose
//   chroma is in the other order, so the photo first opens with Cb and Cr
//   swapped. When a quick encode in portrait locks a frame of a size in
//   debug.apsfixup.yuvswap (WxH,WxH; default 3076x4096,4096x3076; 0 = off),
//   swap its chroma in place before the encoder reads it. Photo mode runs
//   BasicTone, whose quick frames are already in order.
// - debug.apsfixup.trace=1 logs the APS converter and encoder calls and the
//   JPEG copies (read when the camera starts).

#define LOG_TAG "apsfixup"

#include <elf.h>
#include <link.h>
#include <fcntl.h>
#include <log/log.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/system_properties.h>
#include <time.h>
#include <unistd.h>

#include <atomic>

namespace {

constexpr const char* kLibrary = "libAlgoProcess.so";
constexpr int32_t kFormatYuv420Flex = 0x23;
constexpr const char* kDefaultYuvSwap = "3076x4096,4096x3076";

// buffer_info_t: APS capture mode at +0x30.
constexpr size_t kInfoMode = 0x30;
constexpr int32_t kModePortrait = 2;
// How long the mode of a quick bitmap applies to the quick JPEG that follows.
constexpr int64_t kModeWindowNs = 3'000'000'000;

// ApsBufferPlanes: format +0x0, width +0x4, height +0x8, Y +0x18, UV +0x48,
// UV row stride +0x54 (0 means the width). Checked against the COS17 blob.
constexpr size_t kFormat = 0x0;
constexpr size_t kWidth = 0x4;
constexpr size_t kHeight = 0x8;
constexpr size_t kUvPlane = 0x48;
constexpr size_t kUvStride = 0x54;
constexpr size_t kVPlane = 0x78;

bool propIsOff(const char* name) {
    char value[PROP_VALUE_MAX] = {};
    __system_property_get(name, value);
    return strcmp(value, "0") == 0;
}

bool gTrace = false;
bool swapListed(int32_t width, int32_t height);
bool listed(const char* prop, const char* fallback, const char* want);

std::atomic<int64_t> gPortraitNs{0};

int64_t nowNs() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1'000'000'000 + ts.tv_nsec;
}

bool inPortrait() {
    const int64_t at = gPortraitNs.load();
    return at != 0 && nowNs() - at < kModeWindowNs;
}

// ---- Quick bitmap ----------------------------------------------------------

using Yuv2Rgba = long (*)(void*, void*);
Yuv2Rgba gRealYuv2Rgba = nullptr;

long wrapYuv2Rgba(void* planes, void* info) {
    auto* p = static_cast<uint8_t*>(planes);
    if (p == nullptr || propIsOff("debug.apsfixup.rgbuv")) return gRealYuv2Rgba(planes, info);
    const int32_t format = *reinterpret_cast<int32_t*>(p + kFormat);
    const int32_t width = *reinterpret_cast<int32_t*>(p + kWidth);
    const int32_t height = *reinterpret_cast<int32_t*>(p + kHeight);
    auto** uv = reinterpret_cast<uint8_t**>(p + kUvPlane);
    int32_t stride = *reinterpret_cast<int32_t*>(p + kUvStride);
    if (stride == 0) stride = width;
    const int32_t mode =
            info != nullptr ? *reinterpret_cast<const int32_t*>(static_cast<uint8_t*>(info) + kInfoMode)
                            : -1;
    gPortraitNs.store(mode == kModePortrait ? nowNs() : 0);
    if (format != kFormatYuv420Flex || *uv == nullptr || width <= 0 || height <= 0 ||
        ((width | height) & 1) || stride < width) {
        return gRealYuv2Rgba(planes, info);
    }
    if (mode == kModePortrait && ((width == 962 && height == 1280) || (width == 1280 && height == 962))) {
        if (gTrace) ALOGI("quick bitmap: portrait %dx%d kept", width, height);
        return gRealYuv2Rgba(planes, info);
    }
    char modeName[16];
    snprintf(modeName, sizeof(modeName), "%d", mode);
    if (listed("debug.apsfixup.rgbkeep", "", modeName)) {
        if (gTrace) ALOGI("quick bitmap: mode %d %dx%d kept", mode, width, height);
        return gRealYuv2Rgba(planes, info);
    }
    if (gTrace) ALOGI("quick bitmap: mode %d, swapping the chroma of a %dx%d frame", mode, width, height);

    const size_t rows = static_cast<size_t>(height) / 2;
    auto* copy = static_cast<uint8_t*>(malloc(static_cast<size_t>(stride) * rows));
    if (copy == nullptr) return gRealYuv2Rgba(planes, info);
    uint8_t* source = *uv;
    for (size_t r = 0; r < rows; r++) {
        const uint8_t* s = source + r * stride;
        uint8_t* d = copy + r * stride;
        for (int32_t x = 0; x + 1 < width; x += 2) {
            d[x] = s[x + 1];
            d[x + 1] = s[x];
        }
    }
    *uv = copy;
    const long result = gRealYuv2Rgba(planes, info);
    *uv = source;
    free(copy);
    return result;
}

// ---- Quick JPEG ------------------------------------------------------------

// Whether want is an item of the comma-separated list in prop (or fallback).
bool listed(const char* prop, const char* fallback, const char* want) {
    char value[PROP_VALUE_MAX] = {};
    __system_property_get(prop, value);
    const char* list = value[0] != '\0' ? value : fallback;
    if (strcmp(list, "0") == 0) return false;
    const size_t len = strlen(want);
    for (const char* s = strstr(list, want); s != nullptr; s = strstr(s + 1, want)) {
        const bool start = s == list || s[-1] == ',';
        const bool end = s[len] == '\0' || s[len] == ',';
        if (start && end) return true;
    }
    return false;
}

bool swapListed(int32_t width, int32_t height) {
    char want[32];
    snprintf(want, sizeof(want), "%dx%d", width, height);
    return listed("debug.apsfixup.yuvswap", kDefaultYuvSwap, want);
}

// Swaps the chroma bytes of a semi-planar frame of a listed size in place.
void swapChroma(const char* by, uint8_t* planes) {
    const int32_t format = *reinterpret_cast<int32_t*>(planes + kFormat);
    const int32_t width = *reinterpret_cast<int32_t*>(planes + kWidth);
    const int32_t height = *reinterpret_cast<int32_t*>(planes + kHeight);
    uint8_t* uv = *reinterpret_cast<uint8_t**>(planes + kUvPlane);
    // A locked frame may also give the second chroma plane, one byte off the first.
    uint8_t* v = *reinterpret_cast<uint8_t**>(planes + kVPlane);
    if (v != nullptr && v + 1 == uv) uv = v;
    int32_t stride = *reinterpret_cast<int32_t*>(planes + kUvStride);
    if (stride == 0) stride = width;
    if (uv == nullptr || width <= 0 || height <= 0 || ((width | height) & 1) || stride < width ||
        !swapListed(width, height)) {
        return;
    }
    for (int32_t r = 0; r < height / 2; r++) {
        uint8_t* row = uv + static_cast<size_t>(r) * stride;
        for (int32_t x = 0; x + 1 < width; x += 2) {
            const uint8_t t = row[x];
            row[x] = row[x + 1];
            row[x + 1] = t;
        }
    }
    ALOGI("%s: swapped the chroma of a 0x%x %dx%d frame", by, format, width, height);
}

// Copies of JPEGs out of APS, for the trace.
void logJpeg(const uint8_t* p, size_t n) {
    size_t i = 2;
    while (i + 9 < n && p[i] == 0xFF) {
        const uint8_t marker = p[i + 1];
        if (marker == 0xD9 || marker == 0xDA) break;
        const size_t length = (static_cast<size_t>(p[i + 2]) << 8) | p[i + 3];
        if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
            ALOGI("jpeg copy %dx%d, %zu bytes", (p[i + 7] << 8) | p[i + 8],
                  (p[i + 5] << 8) | p[i + 6], n);
            return;
        }
        i += 2 + length;
    }
}

using Memcpy = void* (*)(void*, const void*, size_t);
Memcpy gRealMemcpy = nullptr;

void* wrapMemcpy(void* dst, const void* src, size_t n) {
    const auto* s = static_cast<const uint8_t*>(src);
    if (n >= 64 * 1024 && s[0] == 0xFF && s[1] == 0xD8 && s[2] == 0xFF) logJpeg(s, n);
    return gRealMemcpy(dst, src, n);
}

// ---- Wrapped calls ---------------------------------------------------------

int gProbe[2] = {-1, -1};

// Reads memory that may not be mapped: write() fails with EFAULT instead of
// faulting. Only used while tracing.
bool safeRead(const void* address, void* out, size_t size) {
    if (gProbe[0] < 0 || write(gProbe[1], address, size) != static_cast<ssize_t>(size)) {
        return false;
    }
    return read(gProbe[0], out, size) == static_cast<ssize_t>(size);
}

enum Args {
    kPlanes,       // (const ApsBufferPlanes&, ApsBufferPlanes&, ...)
    kJpegIn,       // (APSJpegInImage*, ...), which starts with format, width, height
    kQuickEncode,  // hwJpegEncodec(AlgoProcessData*, void*, int, uchar, int, int quick)
    kLock,         // camApsBufferLockPlanes(handle, ApsBufferPlanes* out, usage, fence, ...)
    kOther,
};

struct Traced {
    const char* symbol;
    const char* name;
    Args args;
    void* real;
    std::atomic<int> logged;
};

Traced gTraced[] = {
        {"_ZN18APSFormatConverter5scaleER15ApsBufferPlanesS1_P7APSRECTb", "scale", kPlanes,
         nullptr, {0}},
        {"_ZN18APSFormatConverter12rotateMirrorER15ApsBufferPlanesS1_ib", "rotateMirror", kPlanes,
         nullptr, {0}},
        {"_ZN18APSFormatConverter11hoaResizeNNER15ApsBufferPlanesS1_", "hoaResizeNN", kPlanes,
         nullptr, {0}},
        {"_ZN18APSFormatConverter17hoaResizeBilinearER15ApsBufferPlanesS1_",
         "hoaResizeBilinear", kPlanes, nullptr, {0}},
        {"_ZN18APSFormatConverter13hoaResizeAreaER15ApsBufferPlanesS1_", "hoaResizeArea", kPlanes,
         nullptr, {0}},
        {"_ZN18APSFormatConverter13nv122NV21NeonERK15ApsBufferPlanesS2_", "nv122NV21Neon", kPlanes,
         nullptr, {0}},
        {"_ZN18APSFormatConverter13yv122NV21NeonERK15ApsBufferPlanesS2_", "yv122NV21Neon", kPlanes,
         nullptr, {0}},
        {"_ZN7android13hwJpegEncodecEPNS_15AlgoProcessDataEPvihii", "hwJpegEncodec",
         kQuickEncode, nullptr, {0}},
        {"_ZN7android14sw_encode_dataEPNS_14APSJpegInImageEPhiPmP11APSExifData13ApsColorSpace"
         "13J_COLOR_SPACEm",
         "sw_encode_data", kJpegIn, nullptr, {0}},
        {"_ZN7android12swJpegEncodeEPvPNS_7APSDataEi13ApsColorSpace", "swJpegEncode", kOther,
         nullptr, {0}},
        {"camApsBufferLockPlanes", "camApsBufferLockPlanes", kLock, nullptr, {0}},
};

// Set while this thread runs a quick hwJpegEncodec.
thread_local bool tQuickEncode = false;
constexpr int kTracedCount = sizeof(gTraced) / sizeof(gTraced[0]);

void describePlanes(const void* planes, char* out, size_t size) {
    int32_t head[3];
    if (planes == nullptr || !safeRead(planes, head, sizeof(head))) {
        snprintf(out, size, "%p", planes);
        return;
    }
    snprintf(out, size, "%p{fmt 0x%x %dx%d}", planes, head[0], head[1], head[2]);
}

void describe(const Traced& t, const char* when, const uint64_t* regs) {
    if (t.args == kPlanes || t.args == kJpegIn) {
        char a[64], b[64];
        describePlanes(reinterpret_cast<void*>(regs[0]), a, sizeof(a));
        describePlanes(reinterpret_cast<void*>(regs[1]), b, sizeof(b));
        ALOGI("%s %s(%s, %s, 0x%llx, 0x%llx)", when, t.name, a, b,
              static_cast<unsigned long long>(regs[2]), static_cast<unsigned long long>(regs[3]));
    } else {
        ALOGI("%s %s(0x%llx, 0x%llx, %lld, %lld, %lld, %lld)", when, t.name,
              static_cast<unsigned long long>(regs[0]), static_cast<unsigned long long>(regs[1]),
              static_cast<long long>(regs[2]), static_cast<long long>(regs[3]),
              static_cast<long long>(regs[4]), static_cast<long long>(regs[5]));
    }
}

}  // namespace

// The trampolines below call these with the saved argument registers x0-x7.
extern "C" __attribute__((visibility("hidden"))) void* apsfixup_before(int index,
                                                                       const uint64_t* regs) {
    Traced& t = gTraced[index];
    if (t.args == kQuickEncode) tQuickEncode = regs[5] == 1;
    if (gTrace && t.args != kLock && t.logged.load() < 60) describe(t, ">", regs);
    return t.real;
}

extern "C" __attribute__((visibility("hidden"))) void apsfixup_after(int index,
                                                                     const uint64_t* regs) {
    Traced& t = gTraced[index];
    if (t.args == kQuickEncode) tQuickEncode = false;
    if (t.args == kLock) {
        if (!tQuickEncode || regs[1] == 0) return;
        auto* planes = reinterpret_cast<uint8_t*>(regs[1]);
        if (gTrace) {
            ALOGI("quick encode locked fmt 0x%x %dx%d", *reinterpret_cast<int32_t*>(planes + kFormat),
                  *reinterpret_cast<int32_t*>(planes + kWidth),
                  *reinterpret_cast<int32_t*>(planes + kHeight));
        }
        if (inPortrait()) swapChroma("quick jpeg", planes);
        return;
    }
    if (gTrace && t.logged.fetch_add(1) < 60) describe(t, "<", regs);
}

// Wraps a call whatever its signature (up to eight integer arguments): saves
// the argument registers (x0-x8, q0-q7), calls apsfixup_before, calls the real
// function with them, keeps its return registers and calls apsfixup_after.
#define WRAP_TRAMPOLINE(n)                                 \
    extern "C" void apsfixup_tramp_##n();                  \
    __asm__(".text\n"                                      \
            ".balign 4\n"                                  \
            ".globl apsfixup_tramp_" #n "\n"               \
            ".hidden apsfixup_tramp_" #n "\n"              \
            ".type apsfixup_tramp_" #n ", %function\n"     \
            "apsfixup_tramp_" #n ":\n"                     \
            "    hint #34\n"                               \
            "    stp x29, x30, [sp, #-0x120]!\n"           \
            "    mov x29, sp\n"                            \
            "    stp x0, x1, [sp, #0x10]\n"                \
            "    stp x2, x3, [sp, #0x20]\n"                \
            "    stp x4, x5, [sp, #0x30]\n"                \
            "    stp x6, x7, [sp, #0x40]\n"                \
            "    str x8, [sp, #0x50]\n"                    \
            "    stp q0, q1, [sp, #0x60]\n"                \
            "    stp q2, q3, [sp, #0x80]\n"                \
            "    stp q4, q5, [sp, #0xa0]\n"                \
            "    stp q6, q7, [sp, #0xc0]\n"                \
            "    mov w0, #" #n "\n"                        \
            "    add x1, sp, #0x10\n"                      \
            "    bl apsfixup_before\n"                     \
            "    mov x16, x0\n"                            \
            "    ldp q6, q7, [sp, #0xc0]\n"                \
            "    ldp q4, q5, [sp, #0xa0]\n"                \
            "    ldp q2, q3, [sp, #0x80]\n"                \
            "    ldp q0, q1, [sp, #0x60]\n"                \
            "    ldr x8, [sp, #0x50]\n"                    \
            "    ldp x6, x7, [sp, #0x40]\n"                \
            "    ldp x4, x5, [sp, #0x30]\n"                \
            "    ldp x2, x3, [sp, #0x20]\n"                \
            "    ldp x0, x1, [sp, #0x10]\n"                \
            "    blr x16\n"                                \
            "    stp x0, x1, [sp, #0xe0]\n"                \
            "    stp q0, q1, [sp, #0xf0]\n"                \
            "    mov w0, #" #n "\n"                        \
            "    add x1, sp, #0x10\n"                      \
            "    bl apsfixup_after\n"                      \
            "    ldp q0, q1, [sp, #0xf0]\n"                \
            "    ldp x0, x1, [sp, #0xe0]\n"                \
            "    ldp x29, x30, [sp], #0x120\n"             \
            "    ret\n"                                    \
            ".size apsfixup_tramp_" #n ", .-apsfixup_tramp_" #n "\n")

WRAP_TRAMPOLINE(0);
WRAP_TRAMPOLINE(1);
WRAP_TRAMPOLINE(2);
WRAP_TRAMPOLINE(3);
WRAP_TRAMPOLINE(4);
WRAP_TRAMPOLINE(5);
WRAP_TRAMPOLINE(6);
WRAP_TRAMPOLINE(7);
WRAP_TRAMPOLINE(8);
WRAP_TRAMPOLINE(9);
WRAP_TRAMPOLINE(10);

namespace {

void (*const kTrampolines[])() = {
        apsfixup_tramp_0, apsfixup_tramp_1, apsfixup_tramp_2, apsfixup_tramp_3,
        apsfixup_tramp_4, apsfixup_tramp_5, apsfixup_tramp_6, apsfixup_tramp_7,
        apsfixup_tramp_8, apsfixup_tramp_9, apsfixup_tramp_10,
};
static_assert(sizeof(kTrampolines) / sizeof(kTrampolines[0]) == kTracedCount);

// ---- PLT redirection -------------------------------------------------------

struct Target {
    ElfW(Addr) base = 0;
    const ElfW(Phdr)* phdr = nullptr;
    ElfW(Half) phnum = 0;
    const ElfW(Rela)* jmprel = nullptr;
    size_t jmprelSize = 0;
    const ElfW(Sym)* symtab = nullptr;
    const char* strtab = nullptr;
};

int findLibrary(dl_phdr_info* info, size_t, void* data) {
    const char* name = info->dlpi_name;
    if (name == nullptr) return 0;
    const char* slash = strrchr(name, '/');
    if (strcmp(slash != nullptr ? slash + 1 : name, kLibrary) != 0) return 0;
    auto* target = static_cast<Target*>(data);
    target->base = info->dlpi_addr;
    target->phdr = info->dlpi_phdr;
    target->phnum = info->dlpi_phnum;
    return 1;
}

bool readDynamic(Target& target) {
    const ElfW(Dyn)* dynamic = nullptr;
    for (ElfW(Half) i = 0; i < target.phnum; i++) {
        if (target.phdr[i].p_type == PT_DYNAMIC) {
            dynamic = reinterpret_cast<const ElfW(Dyn)*>(target.base + target.phdr[i].p_vaddr);
        }
    }
    if (dynamic == nullptr) return false;
    for (const ElfW(Dyn)* d = dynamic; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_JMPREL:
                target.jmprel = reinterpret_cast<const ElfW(Rela)*>(target.base + d->d_un.d_ptr);
                break;
            case DT_PLTRELSZ:
                target.jmprelSize = d->d_un.d_val;
                break;
            case DT_SYMTAB:
                target.symtab = reinterpret_cast<const ElfW(Sym)*>(target.base + d->d_un.d_ptr);
                break;
            case DT_STRTAB:
                target.strtab = reinterpret_cast<const char*>(target.base + d->d_un.d_ptr);
                break;
        }
    }
    return target.jmprel != nullptr && target.symtab != nullptr && target.strtab != nullptr;
}

// Finds the R_AARCH64_JUMP_SLOT relocation of symbol in the target library.
ElfW(Addr) findSlot(const Target& target, const char* symbol) {
    for (size_t i = 0; i < target.jmprelSize / sizeof(ElfW(Rela)); i++) {
        const ElfW(Rela)& rela = target.jmprel[i];
        if (ELF64_R_TYPE(rela.r_info) != R_AARCH64_JUMP_SLOT) continue;
        const ElfW(Sym)& sym = target.symtab[ELF64_R_SYM(rela.r_info)];
        if (strcmp(target.strtab + sym.st_name, symbol) == 0) return target.base + rela.r_offset;
    }
    return 0;
}

bool inRelro(const Target& target, ElfW(Addr) address) {
    for (ElfW(Half) i = 0; i < target.phnum; i++) {
        const ElfW(Phdr)& ph = target.phdr[i];
        if (ph.p_type != PT_GNU_RELRO) continue;
        const ElfW(Addr) start = target.base + ph.p_vaddr;
        if (address >= start && address < start + ph.p_memsz) return true;
    }
    return false;
}

// Points the PLT slot of symbol at replacement and returns what it held.
void* redirect(const Target& target, const char* symbol, void* replacement) {
    const ElfW(Addr) slot = findSlot(target, symbol);
    if (slot == 0) {
        ALOGW("%s has no PLT slot for %s", kLibrary, symbol);
        return nullptr;
    }
    auto** entry = reinterpret_cast<void**>(slot);
    const long page = sysconf(_SC_PAGESIZE);
    void* start = reinterpret_cast<void*>(slot & ~static_cast<ElfW(Addr)>(page - 1));
    if (mprotect(start, page, PROT_READ | PROT_WRITE) != 0) {
        ALOGE("cannot unprotect the PLT slot of %s", symbol);
        return nullptr;
    }
    void* real = *entry;
    *entry = replacement;
    if (inRelro(target, slot)) mprotect(start, page, PROT_READ);
    return real;
}

// The linker relocates libAlgoProcess before running the constructors of its
// DT_NEEDED libraries, so its GOT already holds the real functions here.
__attribute__((constructor)) void install() {
    Target target;
    if (dl_iterate_phdr(findLibrary, &target) == 0 || !readDynamic(target)) return;

    gRealYuv2Rgba = reinterpret_cast<Yuv2Rgba>(redirect(
            target,
            "_ZN18APSFormatConverter17yuv420sp2rgbaNeonERK15ApsBufferPlanesRK13buffer_info_t",
            reinterpret_cast<void*>(wrapYuv2Rgba)));
    char trace[PROP_VALUE_MAX] = {};
    __system_property_get("debug.apsfixup.trace", trace);
    gTrace = strcmp(trace, "1") == 0 && pipe2(gProbe, O_CLOEXEC) == 0;
    if (gTrace) {
        gRealMemcpy = reinterpret_cast<Memcpy>(
                redirect(target, "memcpy", reinterpret_cast<void*>(wrapMemcpy)));
    }
    int wrapped = 0;
    for (int i = 0; i < kTracedCount; i++) {
        const bool fix = gTraced[i].args == kQuickEncode || gTraced[i].args == kLock;
        if (!fix && !gTrace) continue;
        // Set the real pointer before the slot can reach the trampoline.
        const ElfW(Addr) slot = findSlot(target, gTraced[i].symbol);
        if (slot == 0) continue;
        gTraced[i].real = *reinterpret_cast<void**>(slot);
        if (redirect(target, gTraced[i].symbol, reinterpret_cast<void*>(kTrampolines[i]))) {
            wrapped++;
        }
    }
    ALOGI("installed (quick bitmap %d, wrapped %d, trace %d)", gRealYuv2Rgba != nullptr, wrapped,
          gTrace);
}

}  // namespace
