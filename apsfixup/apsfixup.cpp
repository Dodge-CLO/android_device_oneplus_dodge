/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

// Loaded as a DT_NEEDED of libAlgoProcess.so. The quick camera bitmap is a
// 0x23 buffer with VU chroma that yuv420sp2rgbaNeon reads as UV (cyan
// preview), so hand it a UV copy. debug.apsfixup.rgbuv=0 disables this.

#define LOG_TAG "apsfixup"

#include <elf.h>
#include <link.h>
#include <log/log.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/system_properties.h>
#include <unistd.h>

namespace {

constexpr const char* kLibrary = "libAlgoProcess.so";
constexpr const char* kSymbol =
        "_ZN18APSFormatConverter17yuv420sp2rgbaNeonERK15ApsBufferPlanesRK13buffer_info_t";
constexpr int32_t kFormatYuv420Flex = 0x23;

// ApsBufferPlanes: format +0x0, width +0x4, height +0x8, Y +0x18, UV +0x48,
// UV row stride +0x54 (0 means the width). Checked against the COS17 blob.
constexpr size_t kFormat = 0x0;
constexpr size_t kWidth = 0x4;
constexpr size_t kHeight = 0x8;
constexpr size_t kUvPlane = 0x48;
constexpr size_t kUvStride = 0x54;

using Yuv2Rgba = long (*)(void*, void*);
Yuv2Rgba gReal = nullptr;

bool enabled() {
    char value[PROP_VALUE_MAX] = {};
    __system_property_get("debug.apsfixup.rgbuv", value);
    return strcmp(value, "0") != 0;
}

long wrapYuv2Rgba(void* planes, void* info) {
    auto* p = static_cast<uint8_t*>(planes);
    if (p == nullptr || !enabled()) return gReal(planes, info);
    const int32_t format = *reinterpret_cast<int32_t*>(p + kFormat);
    const int32_t width = *reinterpret_cast<int32_t*>(p + kWidth);
    const int32_t height = *reinterpret_cast<int32_t*>(p + kHeight);
    auto** uv = reinterpret_cast<uint8_t**>(p + kUvPlane);
    int32_t stride = *reinterpret_cast<int32_t*>(p + kUvStride);
    if (stride == 0) stride = width;
    if (format != kFormatYuv420Flex || *uv == nullptr || width <= 0 || height <= 0 ||
        ((width | height) & 1) || stride < width) {
        return gReal(planes, info);
    }

    const size_t rows = static_cast<size_t>(height) / 2;
    auto* copy = static_cast<uint8_t*>(malloc(static_cast<size_t>(stride) * rows));
    if (copy == nullptr) return gReal(planes, info);
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
    const long result = gReal(planes, info);
    *uv = source;
    free(copy);
    return result;
}

struct Target {
    ElfW(Addr) base = 0;
    const ElfW(Phdr)* phdr = nullptr;
    ElfW(Half) phnum = 0;
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

// Finds the R_AARCH64_JUMP_SLOT relocation of kSymbol in the target library.
ElfW(Addr) findSlot(const Target& target) {
    const ElfW(Dyn)* dynamic = nullptr;
    for (ElfW(Half) i = 0; i < target.phnum; i++) {
        if (target.phdr[i].p_type == PT_DYNAMIC) {
            dynamic = reinterpret_cast<const ElfW(Dyn)*>(target.base + target.phdr[i].p_vaddr);
        }
    }
    if (dynamic == nullptr) return 0;
    const ElfW(Rela)* jmprel = nullptr;
    size_t jmprelSize = 0;
    const ElfW(Sym)* symtab = nullptr;
    const char* strtab = nullptr;
    for (const ElfW(Dyn)* d = dynamic; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_JMPREL:
                jmprel = reinterpret_cast<const ElfW(Rela)*>(target.base + d->d_un.d_ptr);
                break;
            case DT_PLTRELSZ:
                jmprelSize = d->d_un.d_val;
                break;
            case DT_SYMTAB:
                symtab = reinterpret_cast<const ElfW(Sym)*>(target.base + d->d_un.d_ptr);
                break;
            case DT_STRTAB:
                strtab = reinterpret_cast<const char*>(target.base + d->d_un.d_ptr);
                break;
        }
    }
    if (jmprel == nullptr || symtab == nullptr || strtab == nullptr) return 0;
    for (size_t i = 0; i < jmprelSize / sizeof(ElfW(Rela)); i++) {
        if (ELF64_R_TYPE(jmprel[i].r_info) != R_AARCH64_JUMP_SLOT) continue;
        const ElfW(Sym)& sym = symtab[ELF64_R_SYM(jmprel[i].r_info)];
        if (strcmp(strtab + sym.st_name, kSymbol) == 0) return target.base + jmprel[i].r_offset;
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

// The linker relocates libAlgoProcess before running the constructors of its
// DT_NEEDED libraries, so its GOT already holds the real function here.
__attribute__((constructor)) void install() {
    Target target;
    if (dl_iterate_phdr(findLibrary, &target) == 0) return;
    const ElfW(Addr) slot = findSlot(target);
    if (slot == 0) {
        ALOGW("%s has no PLT slot for yuv420sp2rgbaNeon", kLibrary);
        return;
    }
    auto** entry = reinterpret_cast<void**>(slot);
    const long page = sysconf(_SC_PAGESIZE);
    void* start = reinterpret_cast<void*>(slot & ~static_cast<ElfW(Addr)>(page - 1));
    if (mprotect(start, page, PROT_READ | PROT_WRITE) != 0) {
        ALOGE("cannot unprotect the PLT slot");
        return;
    }
    gReal = reinterpret_cast<Yuv2Rgba>(*entry);
    *entry = reinterpret_cast<void*>(wrapYuv2Rgba);
    if (inRelro(target, slot)) mprotect(start, page, PROT_READ);
    ALOGI("quick bitmap chroma fix installed");
}

}  // namespace
