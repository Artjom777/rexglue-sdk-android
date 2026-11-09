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

#include <rex/filesystem/devices/disc_image_device.h>
#include <rex/filesystem/devices/disc_image_entry.h>

#include <cstdio>
#include <cctype>
#include <algorithm>
#include <vector>
#include <filesystem>

#include <rex/literals.h>
#include <rex/logging.h>
#include <rex/math.h>
#include <rex/memory.h>

namespace rex::filesystem {

using namespace rex::literals;

const size_t kXESectorSize = 2_KiB;

DiscImageDevice::DiscImageDevice(const std::string_view mount_path,
                                 const std::filesystem::path& host_path)
    : Device(mount_path), name_("GDFX"), host_path_(host_path) {}

DiscImageDevice::~DiscImageDevice() = default;

bool DiscImageDevice::Initialize() {
  mmap_ = memory::MappedMemory::Open(host_path_, memory::MappedMemory::Mode::kRead);
  if (!mmap_) {
    REXFS_ERROR("Disc image could not be mapped");
    return false;
  }

  ParseState state = {};
  state.ptr = mmap_->data();
  state.size = mmap_->size();
  auto result = Verify(&state);
  if (result != Error::kSuccess) {
    REXFS_ERROR("Failed to verify disc image header: {}", static_cast<int>(result));
    return false;
  }

  result = ReadAllEntries(&state, state.ptr + state.root_offset);
  if (result != Error::kSuccess) {
    REXFS_ERROR("Failed to read all GDFX entries: {}", static_cast<int>(result));
    return false;
  }

  return true;
}

void DiscImageDevice::Dump(string::StringBuffer* string_buffer) {
  auto global_lock = global_critical_region_.Acquire();
  string_buffer->AppendFormat(
      "{}: {} files, {} bytes (game_offset={:#x}, root_sector={}, root_size={}, host_size={})\n",
      mount_path(), file_count_, total_file_size_, disc_info_.game_offset, disc_info_.root_sector,
      disc_info_.root_size, disc_info_.host_size);
}

Entry* DiscImageDevice::ResolvePath(const std::string_view path) {
  // The filesystem will have stripped our prefix off already, so the path will
  // be in the form:
  // some\PATH.foo
  REXFS_DEBUG("DiscImageDevice::ResolvePath({})", path);
  return root_entry_->ResolvePath(path);
}

DiscImageDevice::Error DiscImageDevice::Verify(ParseState* state) {
  // Find sector 32 of the game partition - try at a few points.
  static const size_t likely_offsets[] = {
      0x00000000, 0x0000FB20, 0x00020600, 0x02080000, 0x0FD90000,
  };
  bool magic_found = false;
  for (size_t n = 0; n < rex::countof(likely_offsets); n++) {
    state->game_offset = likely_offsets[n];
    if (VerifyMagic(state, state->game_offset + (32 * kXESectorSize))) {
      magic_found = true;
      break;
    }
  }
  if (!magic_found) {
    // File doesn't have the magic values - likely not a real GDFX source.
    return Error::kErrorFileMismatch;
  }

  // Read sector 32 to get FS state.
  if (state->size < state->game_offset + (32 * kXESectorSize)) {
    return Error::kErrorReadError;
  }
  uint8_t* fs_ptr = state->ptr + state->game_offset + (32 * kXESectorSize);
  state->root_sector = memory::load<uint32_t>(fs_ptr + 20);
  state->root_size = memory::load<uint32_t>(fs_ptr + 24);
  state->root_offset = state->game_offset + (state->root_sector * kXESectorSize);
  if (state->root_size < 13 || state->root_size > 32_MiB) {
    return Error::kErrorDamagedFile;
  }

  disc_info_.game_offset = state->game_offset;
  disc_info_.root_sector = state->root_sector;
  disc_info_.root_size = state->root_size;
  disc_info_.host_size = state->size;

  return Error::kSuccess;
}

bool DiscImageDevice::VerifyMagic(ParseState* state, size_t offset) {
  if (offset >= state->size) {
    return false;
  }

  // Simple check to see if the given offset contains the magic value.
  return std::memcmp(state->ptr + offset, "MICROSOFT*XBOX*MEDIA", 20) == 0;
}

DiscImageDevice::Error DiscImageDevice::ReadAllEntries(ParseState* state,
                                                       const uint8_t* root_buffer) {
  auto root_entry = new DiscImageEntry(this, nullptr, "", mmap_.get());
  root_entry->attributes_ = kFileAttributeDirectory;
  root_entry_ = std::unique_ptr<Entry>(root_entry);

  if (!ReadEntry(state, root_buffer, 0, root_entry)) {
    return Error::kErrorOutOfMemory;
  }

  return Error::kSuccess;
}

bool DiscImageDevice::ReadEntry(ParseState* state, const uint8_t* buffer, uint16_t entry_ordinal,
                                DiscImageEntry* parent) {
  const uint8_t* p = buffer + (entry_ordinal * 4);

  uint16_t node_l = memory::load<uint16_t>(p + 0);
  uint16_t node_r = memory::load<uint16_t>(p + 2);
  size_t sector = memory::load<uint32_t>(p + 4);
  size_t length = memory::load<uint32_t>(p + 8);
  uint8_t attributes = memory::load<uint8_t>(p + 12);
  uint8_t name_length = memory::load<uint8_t>(p + 13);
  auto name_buffer = reinterpret_cast<const char*>(p + 14);

  if (node_l && !ReadEntry(state, buffer, node_l, parent)) {
    return false;
  }

  auto name = std::string(name_buffer, name_length);

  auto entry = DiscImageEntry::Create(this, parent, name, mmap_.get());
  entry->attributes_ = attributes | kFileAttributeReadOnly;
  entry->size_ = length;
  entry->allocation_size_ = rex::round_up(length, bytes_per_sector());

  // Set to January 1, 1970 (UTC) in 100-nanosecond intervals
  entry->create_timestamp_ = 10000 * 11644473600000LL;
  entry->access_timestamp_ = 10000 * 11644473600000LL;
  entry->write_timestamp_ = 10000 * 11644473600000LL;

  if (attributes & kFileAttributeDirectory) {
    // Folder.
    entry->data_offset_ = 0;
    entry->data_size_ = 0;
    if (length) {
      // Not a leaf - read in children.
      if (state->size < state->game_offset + (sector * kXESectorSize)) {
        // Out of bounds read.
        return false;
      }
      // Read child list.
      uint8_t* folder_ptr = state->ptr + state->game_offset + (sector * kXESectorSize);
      if (!ReadEntry(state, folder_ptr, 0, entry.get())) {
        return false;
      }
    }
  } else {
    // File.
    entry->data_offset_ = state->game_offset + (sector * kXESectorSize);
    entry->data_size_ = length;
    ++file_count_;
    total_file_size_ += length;
  }

  // Add to parent.
  parent->children_.emplace_back(std::move(entry));

  // Read next file in the list.
  if (node_r && !ReadEntry(state, buffer, node_r, parent)) {
    return false;
  }

  return true;
}

bool DiscImageDevice::ExtractEntry(Entry* entry, const std::filesystem::path& dest_path) {
  if (!entry) {
    return false;
  }

  std::error_code ec;
  if (entry->attributes() & kFileAttributeDirectory) {
    std::filesystem::create_directories(dest_path, ec);
    for (const auto& child : entry->children()) {
      auto child_dest = dest_path / child->name();
      if (!ExtractEntry(child.get(), child_dest)) {
        return false;
      }
    }
    return true;
  }

  auto* disc_entry = static_cast<DiscImageEntry*>(entry);
  if (!disc_entry || !mmap_) {
    return false;
  }

  if (dest_path.has_parent_path()) {
    std::filesystem::create_directories(dest_path.parent_path(), ec);
  }

  FILE* fp = std::fopen(dest_path.string().c_str(), "wb");
  if (!fp) {
    REXFS_ERROR("Failed to create destination file: {}", dest_path.string());
    return false;
  }

  const uint8_t* src = mmap_->data() + disc_entry->data_offset();
  size_t remaining = disc_entry->data_size();
  constexpr size_t kChunkSize = 4 * 1024 * 1024;  // 4MB stream chunks

  while (remaining > 0) {
    size_t chunk = std::min(remaining, kChunkSize);
    size_t written = std::fwrite(src, 1, chunk, fp);
    if (written != chunk) {
      std::fclose(fp);
      REXFS_ERROR("Write error while extracting file: {}", dest_path.string());
      return false;
    }
    src += chunk;
    remaining -= chunk;
  }

  std::fclose(fp);
  return true;
}

bool DiscImageDevice::ExtractFile(const std::string_view guest_path,
                                  const std::filesystem::path& dest_file) {
  std::string clean{guest_path};
  while (!clean.empty() && (clean.front() == '/' || clean.front() == '\\')) {
    clean.erase(0, 1);
  }
  Entry* entry = ResolvePath(clean);
  if (!entry) {
    entry = ResolvePath(guest_path);
  }
  if (!entry) {
    return false;
  }
  return ExtractEntry(entry, dest_file);
}

bool DiscImageDevice::ExtractAll(
    const std::filesystem::path& dest_dir,
    std::function<void(const std::string& current_file, size_t file_index, size_t total_files)>
        progress_cb) {
  if (!root_entry_) {
    return false;
  }

  std::error_code ec;
  std::filesystem::create_directories(dest_dir, ec);

  size_t current_file_index = 0;
  size_t total_files = file_count_;

  std::function<bool(Entry*, const std::filesystem::path&)> extract_recursive;
  extract_recursive = [&](Entry* entry, const std::filesystem::path& current_dest) -> bool {
    if (entry->attributes() & kFileAttributeDirectory) {
      std::filesystem::create_directories(current_dest, ec);
      for (const auto& child : entry->children()) {
        auto child_dest = current_dest / child->name();
        if (!extract_recursive(child.get(), child_dest)) {
          return false;
        }
      }
      return true;
    }

    if (progress_cb) {
      progress_cb(entry->name(), current_file_index + 1, total_files);
    }
    bool ok = ExtractEntry(entry, current_dest);
    current_file_index++;
    return ok;
  };

  for (const auto& child : root_entry_->children()) {
    auto child_dest = dest_dir / child->name();
    if (!extract_recursive(child.get(), child_dest)) {
      return false;
    }
  }

  return true;
}

bool DiscImageDevice::IsDiscImage(const std::filesystem::path& path) {
  std::error_code ec;
  if (!std::filesystem::is_regular_file(path, ec)) {
    return false;
  }
  uint64_t size = std::filesystem::file_size(path, ec);
  if (ec || size < (32 * kXESectorSize + 20)) {
    return false;
  }

  static const size_t likely_offsets[] = {
      0x00000000, 0x0000FB20, 0x00020600, 0x02080000, 0x0FD90000,
  };

  FILE* fp = std::fopen(path.string().c_str(), "rb");
  if (!fp) {
    return false;
  }

  char magic[20] = {};
  for (size_t offset : likely_offsets) {
    size_t target_offset = offset + (32 * kXESectorSize);
    if (target_offset + sizeof(magic) <= size) {
      if (std::fseek(fp, static_cast<long>(target_offset), SEEK_SET) == 0) {
        if (std::fread(magic, 1, sizeof(magic), fp) == sizeof(magic)) {
          if (std::memcmp(magic, "MICROSOFT*XBOX*MEDIA", sizeof(magic)) == 0) {
            std::fclose(fp);
            return true;
          }
        }
      }
    }
  }

  std::fclose(fp);

  std::string ext = path.extension().string();
  for (auto& c : ext) c = static_cast<char>(std::tolower(c));
  return (ext == ".iso" || ext == ".xiso");
}

}  // namespace rex::filesystem
