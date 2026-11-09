/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2020 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 *
 * @modified    Tom Clay, 2026 - Adapted for ReXGlue runtime
 */

#include <cinttypes>
#include <cstring>
#include <utility>

#include <fmt/format.h>

#include <rex/filesystem.h>
#include <rex/graphics/format/ucode.h>
#include <rex/graphics/pipeline/shader/shader.h>
#include <rex/math.h>
#include <rex/memory.h>
#include <rex/string.h>

namespace rex::graphics {
using namespace ucode;

Shader::Shader(xenos::ShaderType shader_type, uint64_t ucode_data_hash,
               const uint32_t* ucode_dwords, size_t ucode_dword_count,
               std::endian ucode_source_endian)
    : shader_type_(shader_type), ucode_data_hash_(ucode_data_hash) {
  // We keep ucode data in host native format so it's easier to work with.
  ucode_data_.resize(ucode_dword_count);
  if (std::endian::native != ucode_source_endian) {
    memory::copy_and_swap(ucode_data_.data(), ucode_dwords, ucode_dword_count);
  } else {
    std::memcpy(ucode_data_.data(), ucode_dwords, sizeof(uint32_t) * ucode_dword_count);
  }
}

Shader::~Shader() {
  for (auto it : translations_) {
    delete it.second;
  }
}

std::string Shader::Translation::GetTranslatedBinaryString() const {
  std::string result;
  result.resize(translated_binary_.size());
  std::memcpy(const_cast<char*>(result.data()), translated_binary_.data(),
              translated_binary_.size());
  return result;
}

std::pair<std::filesystem::path, std::filesystem::path> Shader::Translation::Dump(
    const std::filesystem::path& base_path, const char* path_prefix) const {
  if (!is_valid()) {
    return std::make_pair(std::filesystem::path(), std::filesystem::path());
  }

  std::filesystem::path path = base_path;
  // Ensure target path exists.
  std::filesystem::path target_path = base_path;
  if (!target_path.empty()) {
    target_path = std::filesystem::absolute(target_path);
    std::filesystem::create_directories(target_path);
  }

  const char* type_extension = shader().type() == xenos::ShaderType::kVertex ? "vert" : "frag";

  std::filesystem::path binary_path =
      target_path / fmt::format("shader_{:016X}_{:016X}.{}.bin.{}", shader().ucode_data_hash(),
                                modification(), path_prefix, type_extension);
  FILE* binary_file = filesystem::OpenFile(binary_path, "wb");
  if (binary_file) {
    fwrite(translated_binary_.data(), sizeof(*translated_binary_.data()), translated_binary_.size(),
           binary_file);
    fclose(binary_file);
  }

  std::filesystem::path disasm_path;
  if (!host_disassembly_.empty()) {
    disasm_path =
        target_path / fmt::format("shader_{:016X}_{:016X}.{}.{}", shader().ucode_data_hash(),
                                  modification(), path_prefix, type_extension);
    FILE* disasm_file = filesystem::OpenFile(disasm_path, "w");
    if (disasm_file) {
      fwrite(host_disassembly_.data(), sizeof(*host_disassembly_.data()), host_disassembly_.size(),
             disasm_file);
      fclose(disasm_file);
    }
  }

  return std::make_pair(std::move(binary_path), std::move(disasm_path));
}

Shader::Translation* Shader::GetOrCreateTranslation(uint64_t modification, bool* is_new) {
  auto it = translations_.find(modification);
  if (it != translations_.end()) {
    if (is_new) {
      *is_new = false;
    }
    return it->second;
  }
  Translation* translation = CreateTranslationInstance(modification);
  translations_.emplace(modification, translation);
  if (is_new) {
    *is_new = true;
  }
  return translation;
}

void Shader::DestroyTranslation(uint64_t modification) {
  auto it = translations_.find(modification);
  if (it == translations_.end()) {
    return;
  }
  delete it->second;
  translations_.erase(it);
}

std::pair<std::filesystem::path, std::filesystem::path> Shader::DumpUcode(
    const std::filesystem::path& base_path) const {
  // Ensure target path exists.
  std::filesystem::path target_path = base_path;
  if (!target_path.empty()) {
    target_path = std::filesystem::absolute(target_path);
    std::filesystem::create_directories(target_path);
  }

  const char* type_extension = type() == xenos::ShaderType::kVertex ? "vert" : "frag";

  std::filesystem::path binary_path =
      target_path / fmt::format("shader_{:016X}.ucode.bin.{}", ucode_data_hash(), type_extension);
  FILE* binary_file = filesystem::OpenFile(binary_path, "wb");
  if (binary_file) {
    fwrite(ucode_data().data(), sizeof(*ucode_data().data()), ucode_data().size(), binary_file);
    fclose(binary_file);
  }

  std::filesystem::path disasm_path;
  if (is_ucode_analyzed()) {
    disasm_path =
        target_path / fmt::format("shader_{:016X}.ucode.{}", ucode_data_hash(), type_extension);
    FILE* disasm_file = filesystem::OpenFile(disasm_path, "w");
    if (disasm_file) {
      fwrite(ucode_disassembly().data(), sizeof(*ucode_disassembly().data()),
             ucode_disassembly().size(), disasm_file);
      fclose(disasm_file);
    }
  }

  return std::make_pair(std::move(binary_path), std::move(disasm_path));
}

Shader::Translation* Shader::CreateTranslationInstance(uint64_t modification) {
  // Default implementation for simple cases like ucode disassembly.
  return new Translation(*this, modification);
}

}  // namespace rex::graphics

// Title-side renderers (gpu_skip_rendering) upload a shader's float constants themselves: how many
// of the 256 registers of its bank (c0 up to the highest one it reads) the shader can read, from
// the same ucode analysis that packs constants for the emulator's renderers. 256 when the shader
// indexes constants dynamically. ucode_dwords is the raw (big-endian) microcode.
#if defined(_WIN32)
#define REX_SHADER_EXPORT __declspec(dllexport)
#else
#define REX_SHADER_EXPORT __attribute__((visibility("default")))
#endif
extern "C" REX_SHADER_EXPORT uint32_t rex_shader_float_constant_count(bool pixel, const uint32_t* ucode_dwords,
                                                                       uint32_t dword_count) {
  using namespace rex::graphics;
  if (!ucode_dwords || !dword_count) return 256;
  Shader shader(pixel ? xenos::ShaderType::kPixel : xenos::ShaderType::kVertex, 0, ucode_dwords, dword_count);
  rex::string::StringBuffer disasm;
  shader.AnalyzeUcode(disasm);
  const auto& map = shader.constant_register_map();
  if (map.float_dynamic_addressing) return 256;
  for (int word = 3; word >= 0; --word)
    if (map.float_bitmap[word]) return uint32_t(word * 64 + 64 - __builtin_clzll(map.float_bitmap[word]));
  return 0;
}

// The same analysis as a bitmap of the float constant registers the shader reads (bit i of
// out[i / 64] = register i of its bank). Returns false (all bits set) when it indexes them
// dynamically.
extern "C" REX_SHADER_EXPORT bool rex_shader_float_constant_bitmap(bool pixel, const uint32_t* ucode_dwords,
                                                                    uint32_t dword_count, uint64_t out[4]) {
  using namespace rex::graphics;
  for (int i = 0; i < 4; ++i) out[i] = UINT64_MAX;
  if (!ucode_dwords || !dword_count) return false;
  Shader shader(pixel ? xenos::ShaderType::kPixel : xenos::ShaderType::kVertex, 0, ucode_dwords, dword_count);
  rex::string::StringBuffer disasm;
  shader.AnalyzeUcode(disasm);
  const auto& map = shader.constant_register_map();
  if (map.float_dynamic_addressing) return false;
  for (int i = 0; i < 4; ++i) out[i] = map.float_bitmap[i];
  return true;
}
