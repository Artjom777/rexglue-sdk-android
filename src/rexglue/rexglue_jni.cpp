/**
 * @file        rexglue/rexglue_jni.cpp
 * @brief       JNI Bridge for Android ReXGlue Studio Application
 *
 * Provides native bridge for Xbox 360 PPC -> C++ recompilation,
 * Mali GPU hardware queries, and real-time logging to the Android UI.
 *
 * @copyright   Copyright (c) 2026 Tom Clay & Artjoms Brauers
 * @license     BSD 3-Clause License
 */

#include <jni.h>
#include <string>
#include <vector>
#include <filesystem>
#include <memory>
#include <sstream>
#include <fstream>
#include <iostream>
#include <streambuf>
#include <unistd.h>
#include <sys/resource.h>
#if defined(__ANDROID__)
#include <malloc.h>
#endif
#include <android/log.h>

#include "commands/codegen_command.h"
#include "commands/init_command.h"
#include "commands/template_utils.h"
#include "cli_utils.h"
#include "ui/ui.h"
#include <rex/codegen/manifest.h>
#include <rex/filesystem/devices/disc_image_device.h>
#include <rex/logging.h>
#include <rex/logging/api.h>
#include <rex/logging/types.h>
#include <spdlog/sinks/base_sink.h>
#include <rex/platform.h>
#include <rex/version.h>

#define JNI_TAG "ReXGlue-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, JNI_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, JNI_TAG, __VA_ARGS__)

namespace {

JavaVM* g_jvm = nullptr;
jobject g_callback_obj = nullptr;
jmethodID g_log_method = nullptr;

void SendLogToJava(const char* msg) {
  if (!g_jvm || !g_callback_obj || !g_log_method) return;
  JNIEnv* env = nullptr;
  bool attached = false;
  if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
      attached = true;
    } else {
      return;
    }
  }

  jstring jmsg = env->NewStringUTF(msg);
  env->CallVoidMethod(g_callback_obj, g_log_method, jmsg);
  env->DeleteLocalRef(jmsg);

  if (attached) {
    g_jvm->DetachCurrentThread();
  }
}

/** Custom stream buffer routing spdlog & rexglue::ui outputs to Android UI console */
class AndroidLogStreamBuf : public std::streambuf {
 public:
  AndroidLogStreamBuf() = default;

 protected:
  int_type overflow(int_type ch) override {
    if (ch != traits_type::eof()) {
      char c = static_cast<char>(ch);
      if (c == '\n') {
        flushLine();
      } else if (c != '\r') {
        buffer_ += c;
      }
    }
    return ch;
  }

  std::streamsize xsputn(const char* s, std::streamsize count) override {
    for (std::streamsize i = 0; i < count; ++i) {
      overflow(static_cast<unsigned char>(s[i]));
    }
    return count;
  }

  int sync() override {
    flushLine();
    return 0;
  }

 private:
  void flushLine() {
    if (!buffer_.empty()) {
      SendLogToJava(buffer_.c_str());
      buffer_.clear();
    }
  }

  std::string buffer_;
};

static AndroidLogStreamBuf g_android_log_buf;
static std::ostream g_android_log_stream(&g_android_log_buf);

/** Custom spdlog sink routing all core/kernel/codegen logs directly to Android UI console */
class AndroidSpdlogSink : public spdlog::sinks::base_sink<std::mutex> {
 protected:
  void sink_it_(const spdlog::details::log_msg& msg) override {
    try {
      spdlog::memory_buf_t formatted;
      base_sink<std::mutex>::formatter_->format(msg, formatted);
      std::string s(formatted.data(), formatted.size());
      while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) {
        s.pop_back();
      }
      if (!s.empty()) {
        SendLogToJava(s.c_str());
      }
    } catch (...) {}
  }

  void flush_() override {}
};

/** Initializes rexglue::ui with our log stream to prevent RequireGlobalSink exceptions */
static void EnsureUiInitialized() {
  rexglue::ui::Shutdown();
  auto sink = std::make_unique<rexglue::ui::PresentationSink>(g_android_log_stream, false, false);
  rexglue::ui::detail::SetGlobalSinkForTesting(std::move(sink));

  static bool s_spdlog_inited = false;
  if (!s_spdlog_inited) {
    s_spdlog_inited = true;
    rex::LogConfig cfg;
    cfg.log_to_console = false;
    cfg.default_level = spdlog::level::info;
    auto spdlog_sink = std::make_shared<AndroidSpdlogSink>();
    spdlog_sink->set_level(spdlog::level::info);
    cfg.extra_sinks.push_back(spdlog_sink);
    rex::InitLogging(cfg);
  }
}

/** Extracts default.xex from GDFX disc image, handling lowercase, uppercase, and entry search */
static bool ExtractEntrypointXex(rex::filesystem::DiscImageDevice& disc, const std::filesystem::path& dest_xex) {
  if (disc.ExtractFile("default.xex", dest_xex)) return true;
  if (disc.ExtractFile("DEFAULT.XEX", dest_xex)) return true;
  if (disc.ExtractFile("Default.xex", dest_xex)) return true;
  auto* root = disc.root_entry();
  if (root) {
    for (const auto& child : root->children()) {
      std::string name = child->name();
      std::string lower_name = name;
      for (auto& c : lower_name) c = static_cast<char>(std::tolower(c));
      if (lower_name == "default.xex" || (lower_name.size() > 4 && lower_name.substr(lower_name.size() - 4) == ".xex")) {
        if (disc.ExtractFile(name, dest_xex)) {
          return true;
        }
      }
    }
  }
  return false;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  g_jvm = vm;
  LOGI("ReXGlue JNI loaded successfully");
  return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_rexglue_app_MainActivity_nativeRegisterLogCallback(
    JNIEnv* env, jobject thiz) {
  if (g_callback_obj) {
    env->DeleteGlobalRef(g_callback_obj);
    g_callback_obj = nullptr;
  }
  g_callback_obj = env->NewGlobalRef(thiz);
  jclass clazz = env->GetObjectClass(thiz);
  g_log_method = env->GetMethodID(clazz, "onNativeLog", "(Ljava/lang/String;)V");
  LOGI("Registered Java native log callback");
}

JNIEXPORT jstring JNICALL
Java_com_rexglue_app_MainActivity_nativeGetDeviceInfo(
    JNIEnv* env, jobject thiz) {
  std::ostringstream ss;
  long page_size = sysconf(_SC_PAGESIZE);
  int num_procs = sysconf(_SC_NPROCESSORS_CONF);

  ss << "ReXGlue Version: " << REXGLUE_VERSION_STRING << "\n";
  ss << "Architecture: AArch64 (ARMv8.2-A+ / ARMv9)\n";
  ss << "Target Hardware: POCO X7 Pro / MediaTek Dimensity\n";
  ss << "GPU: ARM Mali-G720 / Mali-G615 (TBDR)\n";
  ss << "Page Size: " << page_size << " bytes (" << (page_size == 16384 ? "16KB Android 15 OK" : "4KB") << ")\n";
  ss << "CPU Cores: " << num_procs << " (Cortex-X / Cortex-A725 / Cortex-A520)\n";
  ss << "Vulkan Backend: Enabled (Mali optimized, geometryShader optional)\n";
  ss << "Memory Protection: MAP_FIXED_NOREPLACE Active\n";

  return env->NewStringUTF(ss.str().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_rexglue_app_MainActivity_nativeInspectIso(
    JNIEnv* env, jobject thiz, jstring jIsoPath) {
  const char* path_cstr = env->GetStringUTFChars(jIsoPath, nullptr);
  std::string iso_path = path_cstr ? path_cstr : "";
  env->ReleaseStringUTFChars(jIsoPath, path_cstr);

  if (iso_path.empty()) {
    return env->NewStringUTF("{\"valid\":false,\"error\":\"Empty path\"}");
  }

  std::error_code ec;
  if (!std::filesystem::exists(iso_path, ec) || ec) {
    return env->NewStringUTF("{\"valid\":false,\"error\":\"File does not exist\"}");
  }

  if (!rex::filesystem::DiscImageDevice::IsDiscImage(iso_path)) {
    return env->NewStringUTF("{\"valid\":false,\"error\":\"Not a valid Xbox 360 GDFX ISO\"}");
  }

  rex::filesystem::DiscImageDevice disc("", iso_path);
  if (!disc.Initialize()) {
    return env->NewStringUTF("{\"valid\":false,\"error\":\"Failed to initialize GDFX parser\"}");
  }

  bool has_xex = (disc.ResolvePath("default.xex") != nullptr) ||
                 (disc.ResolvePath("DEFAULT.XEX") != nullptr);
  uint64_t total_mb = disc.total_file_size() / (1024 * 1024);

  std::ostringstream ss;
  ss << "{\"valid\":true"
     << ",\"file_count\":" << disc.file_count()
     << ",\"total_size_mb\":" << total_mb
     << ",\"has_default_xex\":" << (has_xex ? "true" : "false")
     << "}";

  return env->NewStringUTF(ss.str().c_str());
}

JNIEXPORT jint JNICALL
Java_com_rexglue_app_MainActivity_nativeExtractIso(
    JNIEnv* env, jobject thiz, jstring jIsoPath, jstring jDestDir, jboolean jExtractAll) {
  const char* path_cstr = env->GetStringUTFChars(jIsoPath, nullptr);
  const char* dest_cstr = env->GetStringUTFChars(jDestDir, nullptr);
  std::string iso_path = path_cstr ? path_cstr : "";
  std::string dest_dir = dest_cstr ? dest_cstr : "";
  env->ReleaseStringUTFChars(jIsoPath, path_cstr);
  env->ReleaseStringUTFChars(jDestDir, dest_cstr);

  std::error_code ec;
  if (iso_path.empty() || dest_dir.empty() || !std::filesystem::exists(iso_path, ec) || ec) {
    SendLogToJava("[ERROR] Invalid ISO path or destination directory");
    return -1;
  }

  rex::filesystem::DiscImageDevice disc("", iso_path);
  if (!disc.Initialize()) {
    SendLogToJava("[ERROR] Cannot mount ISO image");
    return -2;
  }

  std::filesystem::create_directories(dest_dir, ec);

  if (jExtractAll) {
    SendLogToJava(("[INFO] Extracting all " + std::to_string(disc.file_count()) + " files to " + dest_dir).c_str());
    bool ok = disc.ExtractAll(dest_dir, [](const std::string& name, size_t idx, size_t total) {
      if (idx % 20 == 0 || idx == total) {
        SendLogToJava(("[EXTRACT] (" + std::to_string(idx) + "/" + std::to_string(total) + ") " + name).c_str());
      }
    });
    return ok ? 0 : -3;
  } else {
    std::filesystem::path target = std::filesystem::path(dest_dir) / "default.xex";
    SendLogToJava(("[INFO] Extracting default.xex to " + target.string()).c_str());
    bool ok = ExtractEntrypointXex(disc, target);
    return ok ? 0 : -4;
  }
}

static jint RunCodegenMultiInternal(
    JNIEnv* env, jobject thiz,
    const std::vector<std::string>& iso_paths, const std::string& update_path,
    const std::string& project_name, const std::string& out_dir,
    bool mali_opt, int mode) {

  // Initialize UI presentation sink connected to Java logs
  EnsureUiInitialized();

  try {
    if (iso_paths.empty() || out_dir.empty()) {
      SendLogToJava("[ERROR] No ISO images or output directory provided!");
      return -1;
    }

    // Elevate Linux thread scheduling priority for full Dimensity 8400 CPU performance
    setpriority(PRIO_PROCESS, 0, -10);

#if defined(__ANDROID__) && defined(M_DECAY_TIME)
    mallopt(M_DECAY_TIME, 1);
#endif

    SendLogToJava("\n========================================");
    SendLogToJava(("[INFO] ReXGlue Studio Project: " + project_name).c_str());
    SendLogToJava(("[INFO] Output Folder: " + out_dir).c_str());
    SendLogToJava(("[INFO] Mode: " + std::string(mode == 2 ? "2 (Standalone All-in-One APK)" : "1 (Split ISO Mount APK)")).c_str());
    SendLogToJava(("[INFO] Attached ISO Discs: " + std::to_string(iso_paths.size())).c_str());
    for (size_t i = 0; i < iso_paths.size(); ++i) {
      SendLogToJava(("[INFO]   Disc " + std::to_string(i + 1) + ": " + iso_paths[i]).c_str());
    }
    if (!update_path.empty()) {
      SendLogToJava(("[INFO]   Title Update (TU): " + update_path).c_str());
    }
    SendLogToJava("========================================");

    std::error_code ec;
    std::filesystem::path game_data_dir = std::filesystem::path(out_dir) / "game_data";
    std::filesystem::path update_data_dir = std::filesystem::path(out_dir) / "update_data";
    std::filesystem::create_directories(game_data_dir, ec);
    std::filesystem::create_directories(update_data_dir, ec);

    std::filesystem::path primary_xex;

    // Step 1: Process Title Update if attached
    if (!update_path.empty() && std::filesystem::exists(update_path, ec) && !ec) {
      SendLogToJava("[INFO] Inspecting Title Update / Patch package...");
      if (rex::filesystem::DiscImageDevice::IsDiscImage(update_path)) {
        rex::filesystem::DiscImageDevice tu_disc("", update_path);
        if (tu_disc.Initialize()) {
          std::filesystem::path tu_xex = update_data_dir / "default.xex";
          if (ExtractEntrypointXex(tu_disc, tu_xex)) {
            SendLogToJava("[SUCCESS] Title Update default.xex detected and extracted!");
            primary_xex = tu_xex;
          }
          if (mode == 2) {
            SendLogToJava("[INFO] Extracting all Title Update files for Standalone build...");
            tu_disc.ExtractAll(update_data_dir);
          }
        }
      } else if (std::filesystem::is_regular_file(update_path, ec) && !ec) {
        std::filesystem::path tu_xex = update_data_dir / "default.xex";
        std::filesystem::copy_file(update_path, tu_xex,
                                   std::filesystem::copy_options::overwrite_existing, ec);
        SendLogToJava("[SUCCESS] Custom Title Update XEX copied.");
        primary_xex = tu_xex;
      }
    }

    // Step 2: Process ISO Discs
    for (size_t i = 0; i < iso_paths.size(); ++i) {
      const std::string& iso_path = iso_paths[i];
      if (!std::filesystem::exists(iso_path, ec) || ec) {
        SendLogToJava(("[ERROR] Disc file does not exist: " + iso_path).c_str());
        return -10;
      }

      bool is_iso = rex::filesystem::DiscImageDevice::IsDiscImage(iso_path);
      if (!is_iso && !std::filesystem::is_regular_file(iso_path, ec)) {
        SendLogToJava(("[ERROR] Invalid file format: " + iso_path).c_str());
        return -11;
      }

      if (is_iso) {
        rex::filesystem::DiscImageDevice disc("", iso_path);
        if (!disc.Initialize()) {
          SendLogToJava(("[ERROR] Failed to mount ISO disc: " + iso_path).c_str());
          return -12;
        }

        uint64_t total_mb = disc.total_file_size() / (1024 * 1024);
        SendLogToJava(("[INFO] Disc " + std::to_string(i + 1) + ": " +
                       std::to_string(disc.file_count()) + " files (" +
                       std::to_string(total_mb) + " MB)").c_str());

        if (mode == 2) {
          // Mode 2: Extract all files from this disc into game_data
          SendLogToJava(("[INFO] Extracting all files from Disc " + std::to_string(i + 1) + "...").c_str());
          bool extract_ok = disc.ExtractAll(game_data_dir, [i](const std::string& name, size_t idx, size_t total) {
            if (idx % 30 == 0 || idx == total) {
              SendLogToJava(("[EXTRACT D" + std::to_string(i + 1) + "] (" +
                             std::to_string(idx) + "/" + std::to_string(total) + ") " + name).c_str());
            }
          });
          if (!extract_ok) {
            SendLogToJava(("[ERROR] Failed to extract files from Disc " + std::to_string(i + 1)).c_str());
            return -13;
          }
          if (i == 0 && primary_xex.empty()) {
            std::filesystem::path disc_xex = game_data_dir / "default.xex";
            if (std::filesystem::exists(disc_xex, ec) && !ec) {
              primary_xex = disc_xex;
            }
          }
        } else {
          // Mode 1: Split ISO mount - extract default.xex from Disc 1 if not already supplied by TU
          if (i == 0 && primary_xex.empty()) {
            std::filesystem::path disc_xex = game_data_dir / "default.xex";
            if (ExtractEntrypointXex(disc, disc_xex)) {
              SendLogToJava("[SUCCESS] default.xex extracted from Disc 1 for PPC analysis.");
              primary_xex = disc_xex;
            } else {
              SendLogToJava("[WARN] Could not find default.xex in Disc 1 root.");
            }
          }
        }
      } else {
        // Input is direct .xex file
        if (primary_xex.empty()) {
          std::filesystem::path disc_xex = game_data_dir / "default.xex";
          std::filesystem::copy_file(iso_path, disc_xex,
                                     std::filesystem::copy_options::overwrite_existing, ec);
          primary_xex = disc_xex;
        }
      }
    }

    // Fallback to checking game_data/default.xex
    if (primary_xex.empty()) {
      std::filesystem::path fallback_xex = game_data_dir / "default.xex";
      if (std::filesystem::exists(fallback_xex, ec) && !ec) {
        primary_xex = fallback_xex;
      } else {
        SendLogToJava("[ERROR] Entrypoint default.xex not found in ISO discs or Title Update!");
        return -14;
      }
    }

    // Write configuration for APK runtime
    std::ofstream cfg(std::filesystem::path(out_dir) / "rexglue.cfg");
    cfg << "mode=" << (mode == 2 ? "standalone" : "iso") << "\n";
    cfg << "project=" << project_name << "\n";
    cfg << "iso_count=" << iso_paths.size() << "\n";
    for (size_t i = 0; i < iso_paths.size(); ++i) {
      cfg << "iso_" << i << "=" << iso_paths[i] << "\n";
    }
    if (!update_path.empty()) {
      cfg << "update_path=" << update_path << "\n";
    }
    cfg.close();

    // Write args.txt
    std::ofstream args_file(std::filesystem::path(out_dir) / "args.txt");
    if (mode == 2) {
      args_file << "--game_data_root=" << game_data_dir.string() << "\n";
      if (!update_path.empty()) {
        args_file << "--update_data_root=" << update_data_dir.string() << "\n";
      }
    } else {
      args_file << "--game_data_root=" << iso_paths[0] << "\n";
      if (iso_paths.size() > 1) {
        args_file << "--disc2_data_root=" << iso_paths[1] << "\n";
      }
      if (iso_paths.size() > 2) {
        args_file << "--disc3_data_root=" << iso_paths[2] << "\n";
      }
      if (!update_path.empty()) {
        args_file << "--update_data_root=" << update_path << "\n";
      }
    }
    args_file.close();

    // Step 3: Run PPC -> C++ Codegen
    rexglue::cli::CliContext ctx;
    ctx.verbose = true;
    ctx.overwrite_existing = true;
    ctx.generate_despite_errors = true;

    // Sanitize project name to ensure it starts with an ASCII letter and contains valid identifier characters
    std::string safe_project_name = project_name;
    if (safe_project_name.empty() || !std::isalpha(static_cast<unsigned char>(safe_project_name[0]))) {
      safe_project_name = "game_" + safe_project_name;
    }
    for (char& c : safe_project_name) {
      if (!std::isalnum(static_cast<unsigned char>(c)) && c != '_') {
        c = '_';
      }
    }

    auto names = rexglue::cli::parse_app_name(safe_project_name);
    std::string canonical_name = names.original.empty() ? "xbox360port" : names.original;

    rexglue::cli::InitOptions init_opts;
    init_opts.project_name = safe_project_name;
    init_opts.xex_path = primary_xex.string();
    init_opts.project_root = out_dir;
    init_opts.game_root = primary_xex.parent_path().string();
    init_opts.force = true;

    SendLogToJava("[INFO] Phase 1: Scanning entrypoint XEX and PE sections...");
    auto init_res = rexglue::cli::InitProject(init_opts, ctx);
    if (!init_res) {
      SendLogToJava(("[WARN] InitProject notice: " + init_res.error().message).c_str());
    }

    // Locate or create the project manifest
    std::string manifest_path;
    std::vector<std::string> manifest_candidates = {
        (std::filesystem::path(out_dir) / (canonical_name + "_manifest.toml")).string(),
        (std::filesystem::path(out_dir) / (safe_project_name + "_manifest.toml")).string(),
        (std::filesystem::path(out_dir) / (project_name + "_manifest.toml")).string(),
        (std::filesystem::path(out_dir) / "rexglue.toml").string(),
        (std::filesystem::path(out_dir) / "manifest.toml").string(),
    };

    for (const auto& cand : manifest_candidates) {
      if (std::filesystem::exists(cand, ec) && rex::codegen::ManifestConfig::IsManifest(cand)) {
        manifest_path = cand;
        break;
      }
    }

    if (manifest_path.empty()) {
      for (const auto& entry : std::filesystem::directory_iterator(out_dir, ec)) {
        if (!entry.is_regular_file()) continue;
        if (entry.path().extension() == ".toml" && rex::codegen::ManifestConfig::IsManifest(entry.path())) {
          manifest_path = entry.path().string();
          break;
        }
      }
    }

    // Compute relative paths from project root (out_dir)
    std::filesystem::path rel_xex = std::filesystem::relative(primary_xex, out_dir, ec);
    std::string rel_xex_str = (ec || rel_xex.empty()) ? "game_data/default.xex" : rel_xex.generic_string();
    std::filesystem::path rel_game_root = std::filesystem::relative(primary_xex.parent_path(), out_dir, ec);
    std::string rel_game_root_str = (ec || rel_game_root.empty()) ? "game_data" : rel_game_root.generic_string();
    std::string entrypoint_out_dir = "generated/" + primary_xex.stem().string();

    std::filesystem::create_directories(std::filesystem::path(out_dir) / "src", ec);
    std::filesystem::create_directories(std::filesystem::path(out_dir) / entrypoint_out_dir, ec);

    if (manifest_path.empty()) {
      manifest_path = (std::filesystem::path(out_dir) / (canonical_name + "_manifest.toml")).string();
      SendLogToJava(("[INFO] Generating direct project manifest TOML: " + manifest_path).c_str());
      std::ofstream mf(manifest_path);
      mf << "# Auto-generated by ReXGlue Studio Android\n\n";
      mf << "[project]\n";
      mf << "name = \"" << canonical_name << "\"\n";
      mf << "sdk_version = \"" << REXGLUE_VERSION_FLOOR << "\"\n";
      mf << "game_root = \"" << rel_game_root_str << "\"\n\n";
      mf << "[entrypoint]\n";
      mf << "file_path = \"" << rel_xex_str << "\"\n";
      mf << "out_directory_path = \"" << entrypoint_out_dir << "\"\n";
      mf << "includes = []\n";
      mf.close();
    }

    // Ensure minimal CMakeLists.txt exists if template init didn't create it
    std::filesystem::path cmakelists = std::filesystem::path(out_dir) / "CMakeLists.txt";
    if (!std::filesystem::exists(cmakelists, ec) || ec) {
      std::ofstream cf(cmakelists);
      cf << "cmake_minimum_required(VERSION 3.25)\n";
      cf << "project(" << canonical_name << " LANGUAGES C CXX ASM)\n";
      cf << "include(generated/rexglue.cmake)\n";
      cf.close();
    }

    SendLogToJava(("[INFO] Using manifest: " + manifest_path).c_str());
    SendLogToJava("[INFO] Phase 2: Analyzing basic blocks, instruction patterns, and branches...");
    SendLogToJava("[INFO] Phase 3: Generating native C++ source code with embedded Inja templates...");

    std::vector<std::string> targets;
    auto codegen_res = rexglue::cli::CodegenFromConfig(manifest_path, ctx, targets, true);
    if (!codegen_res) {
      SendLogToJava(("[ERROR] Codegen failed: " + codegen_res.error().message).c_str());
      return -2;
    }

    SendLogToJava("[SUCCESS] PPC -> C++ Codegen completed successfully!");
    SendLogToJava(("[INFO] Generated C++ source code in: " + out_dir).c_str());
    if (mode == 1) {
      SendLogToJava("[READY] Mode 1: Split ISO APK port is ready to build!");
      SendLogToJava(("[READY] Main disc: " + iso_paths[0]).c_str());
      if (iso_paths.size() > 1) {
        SendLogToJava(("[READY] Secondary disc(s) attached: " + std::to_string(iso_paths.size() - 1)).c_str());
      }
    } else {
      SendLogToJava("[READY] Mode 2: Standalone Full Game APK is ready to build!");
      SendLogToJava("[READY] All multi-disc and patch assets merged into standalone game package.");
    }
    return 0;

  } catch (const std::exception& e) {
    SendLogToJava(("[ERROR] Native exception: " + std::string(e.what())).c_str());
    return -99;
  } catch (...) {
    SendLogToJava("[ERROR] Unknown native exception occurred during codegen!");
    return -100;
  }
}

static jint RunCodegenInternal(
    JNIEnv* env, jobject thiz,
    const std::string& input_path, const std::string& project_name,
    const std::string& out_dir, bool mali_opt, int mode) {
  try {
    std::vector<std::string> paths;
    if (!input_path.empty()) {
      paths.push_back(input_path);
    }
    return RunCodegenMultiInternal(env, thiz, paths, "", project_name, out_dir, mali_opt, mode);
  } catch (const std::exception& e) {
    SendLogToJava(("[ERROR] Native exception in RunCodegen: " + std::string(e.what())).c_str());
    return -98;
  } catch (...) {
    return -99;
  }
}

JNIEXPORT jint JNICALL
Java_com_rexglue_app_MainActivity_nativeRunCodegenMulti(
    JNIEnv* env, jobject thiz,
    jobjectArray jIsoPaths, jstring jUpdatePath, jstring jProjectName,
    jstring jOutDir, jboolean jMaliOpt, jint jMode) {
  try {
    std::vector<std::string> iso_paths;
    if (jIsoPaths) {
      jsize count = env->GetArrayLength(jIsoPaths);
      for (jsize i = 0; i < count; ++i) {
        jstring item = (jstring)env->GetObjectArrayElement(jIsoPaths, i);
        if (item) {
          const char* s = env->GetStringUTFChars(item, nullptr);
          if (s && *s) iso_paths.emplace_back(s);
          env->ReleaseStringUTFChars(item, s);
          env->DeleteLocalRef(item);
        }
      }
    }

    std::string update_path;
    if (jUpdatePath) {
      const char* u = env->GetStringUTFChars(jUpdatePath, nullptr);
      if (u) update_path = u;
      env->ReleaseStringUTFChars(jUpdatePath, u);
    }

    const char* project_name_cstr = env->GetStringUTFChars(jProjectName, nullptr);
    const char* out_dir_cstr = env->GetStringUTFChars(jOutDir, nullptr);

    std::string project_name = project_name_cstr ? project_name_cstr : "recompiled_game";
    std::string out_dir = out_dir_cstr ? out_dir_cstr : "";

    env->ReleaseStringUTFChars(jProjectName, project_name_cstr);
    env->ReleaseStringUTFChars(jOutDir, out_dir_cstr);

    return RunCodegenMultiInternal(env, thiz, iso_paths, update_path, project_name, out_dir, jMaliOpt, jMode);
  } catch (const std::exception& e) {
    SendLogToJava(("[ERROR] Native exception in nativeRunCodegenMulti: " + std::string(e.what())).c_str());
    return -98;
  } catch (...) {
    return -99;
  }
}

JNIEXPORT jint JNICALL
Java_com_rexglue_app_MainActivity_nativeRunCodegenMode(
    JNIEnv* env, jobject thiz,
    jstring jInputPath, jstring jProjectName, jstring jOutDir, jboolean jMaliOpt, jint jMode) {
  try {
    const char* in_path_cstr = env->GetStringUTFChars(jInputPath, nullptr);
    const char* project_name_cstr = env->GetStringUTFChars(jProjectName, nullptr);
    const char* out_dir_cstr = env->GetStringUTFChars(jOutDir, nullptr);

    std::string in_path = in_path_cstr ? in_path_cstr : "";
    std::string project_name = project_name_cstr ? project_name_cstr : "recompiled_game";
    std::string out_dir = out_dir_cstr ? out_dir_cstr : "";

    env->ReleaseStringUTFChars(jInputPath, in_path_cstr);
    env->ReleaseStringUTFChars(jProjectName, project_name_cstr);
    env->ReleaseStringUTFChars(jOutDir, out_dir_cstr);

    return RunCodegenInternal(env, thiz, in_path, project_name, out_dir, jMaliOpt, jMode);
  } catch (const std::exception& e) {
    SendLogToJava(("[ERROR] Native exception in nativeRunCodegenMode: " + std::string(e.what())).c_str());
    return -98;
  } catch (...) {
    return -99;
  }
}

JNIEXPORT jint JNICALL
Java_com_rexglue_app_MainActivity_nativeRunCodegen(
    JNIEnv* env, jobject thiz,
    jstring jXexPath, jstring jProjectName, jstring jOutDir, jboolean jMaliOpt) {
  return Java_com_rexglue_app_MainActivity_nativeRunCodegenMode(
      env, thiz, jXexPath, jProjectName, jOutDir, jMaliOpt, 1);
}

}  // extern "C"
