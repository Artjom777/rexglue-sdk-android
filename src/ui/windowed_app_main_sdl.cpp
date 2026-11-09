/**
 * @file        ui/windowed_app_main_sdl.cpp
 * @brief       Entry point for windowed applications (SDL3 windowing)
 *
 * @copyright   Copyright (c) 2026 Tom Clay <tomc@tctechstuff.com>
 *              All rights reserved.
 *
 * @license     BSD 3-Clause License
 *              See LICENSE file in the project root for full license text.
 */

#include <algorithm>
#include <cstdlib>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include <rex/cvar.h>
#include <rex/logging.h>
#include <rex/platform.h>
#include <rex/ui/windowed_app.h>
#include <rex/ui/windowed_app_context_sdl.h>

#if REX_PLATFORM_ANDROID
// Renames main to SDL_main, which SDLActivity looks up in libmain.so and calls on its thread.
#include <SDL3/SDL_main.h>
#endif

#if REX_PLATFORM_WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <objbase.h>
#include <shellapi.h>
#endif

namespace {

int RunWindowedApp(int argc, char** argv) {
  auto remaining = rex::cvar::Init(argc, argv);
  rex::cvar::ApplyEnvironment();
  rex::InitLoggingEarly();

  int result;
  {
    rex::ui::SDLWindowedAppContext app_context;
    if (!app_context.Initialize()) {
      return EXIT_FAILURE;
    }

#if REX_PLATFORM_WIN32
    // Apartment-threaded COM for shell dialogs.
    if (FAILED(CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED))) {
      return EXIT_FAILURE;
    }
#endif

    auto creator = rex::ui::GetWindowedAppCreator();
    if (!creator) {
      REXLOG_ERROR("No WindowedApp creator registered");
      return EXIT_FAILURE;
    }

    std::unique_ptr<rex::ui::WindowedApp> app = creator(app_context);
    if (!app) {
      REXLOG_ERROR("WindowedApp creator returned null");
      return EXIT_FAILURE;
    }

    // Match remaining positional args to the app's expected options.
    const auto& option_names = app->GetPositionalOptions();
    std::map<std::string, std::string> parsed;
    size_t count = std::min(remaining.size(), option_names.size());
    for (size_t i = 0; i < count; ++i) {
      parsed[option_names[i]] = remaining[i];
    }
    app->SetParsedArguments(std::move(parsed));

    result = app->OnInitialize() ? app_context.RunMainMessageLoop() : EXIT_FAILURE;

    app->InvokeOnDestroy();
  }

#if REX_PLATFORM_WIN32
  CoUninitialize();
#endif

  return result;
}

#if REX_PLATFORM_WIN32
// Convert wide argv from CommandLineToArgvW to UTF-8 for cvar::Init.
std::vector<std::string> WideArgsToUtf8(int argc, wchar_t** wargv) {
  std::vector<std::string> args;
  args.reserve(static_cast<size_t>(argc));
  for (int i = 0; i < argc; ++i) {
    std::wstring wide(wargv[i]);
    if (wide.empty()) {
      args.emplace_back();
      continue;
    }
    int size = WideCharToMultiByte(CP_UTF8, 0, wide.data(), static_cast<int>(wide.size()), nullptr,
                                   0, nullptr, nullptr);
    std::string utf8(static_cast<size_t>(size), '\0');
    WideCharToMultiByte(CP_UTF8, 0, wide.data(), static_cast<int>(wide.size()), utf8.data(), size,
                        nullptr, nullptr);
    args.push_back(std::move(utf8));
  }
  return args;
}
#endif

}  // namespace

#if REX_PLATFORM_WIN32

int WINAPI wWinMain(HINSTANCE hinstance, HINSTANCE hinstance_prev, LPWSTR command_line,
                    int show_cmd) {
  (void)hinstance;
  (void)hinstance_prev;
  (void)command_line;
  (void)show_cmd;

  int wargc = 0;
  wchar_t** wargv = CommandLineToArgvW(GetCommandLineW(), &wargc);
  auto utf8_args = WideArgsToUtf8(wargc, wargv);
  LocalFree(wargv);

  std::vector<char*> argv_ptrs;
  argv_ptrs.reserve(utf8_args.size());
  for (auto& s : utf8_args) {
    argv_ptrs.push_back(s.data());
  }
  return RunWindowedApp(static_cast<int>(argv_ptrs.size()), argv_ptrs.data());
}

#elif REX_PLATFORM_IOS

// iOS: UIKit must own the main thread, so main() hands over to SDL_RunApp (UIApplicationMain),
// which calls back into RunWindowedApp once the app has finished launching. An app gets no
// command line from the home screen: arguments come from Documents/args.txt (one per line or
// space separated, # comments), appended after any that were passed (devicectl --  args).
#define SDL_MAIN_HANDLED
#include <SDL3/SDL_main.h>
#include <fstream>
#include <sstream>
#include <unistd.h>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <filesystem>

namespace {
std::vector<std::string> g_ios_args;
std::vector<char*> g_ios_argv;

int IosMain(int argc, char* argv[]) {
  // Again here: SDL's app delegate changes to the (read-only) bundle directory before this runs.
  if (const char* home = std::getenv("HOME")) {
    std::string docs = std::string(home) + "/Documents";
    int rc = chdir(docs.c_str());
    char cwd[1024] = {};
    getcwd(cwd, sizeof(cwd));
    std::error_code ec;
    std::filesystem::create_directories("logs", ec);
    FILE* probe = std::fopen("logs/.probe", "w");
    std::fprintf(stderr, "[ios] chdir(%s)=%d cwd=%s logs:%s probe:%s (%s)\n", docs.c_str(), rc, cwd,
                 ec ? ec.message().c_str() : "ok", probe ? "ok" : "fail", probe ? "" : std::strerror(errno));
    if (probe) std::fclose(probe);
  }
  return RunWindowedApp(argc, argv);
}
}  // namespace

int main(int argc, char* argv[]) {
  for (int i = 0; i < argc; ++i) {
    g_ios_args.emplace_back(argv[i]);
  }
  auto read_args = [](const std::string& path) {
    std::ifstream file(path);
    std::string line;
    while (std::getline(file, line)) {
      if (auto hash = line.find('#'); hash != std::string::npos) {
        line.resize(hash);
      }
      std::istringstream words(line);
      std::string word;
      while (words >> word) {
        g_ios_args.push_back(word);
      }
    }
  };
  // The app's own defaults (args.txt inside the bundle, next to the executable) first, then the
  // player's Documents/args.txt, whose settings come later and so win.
  if (argc > 0) {
    read_args((std::filesystem::path(argv[0]).parent_path() / "args.txt").string());
  }
  if (const char* home = std::getenv("HOME")) {
    // Relative paths in args.txt (game data, logs) are relative to Documents: the container's
    // absolute path changes with every install.
    chdir((std::string(home) + "/Documents").c_str());
    read_args(std::string(home) + "/Documents/args.txt");
  }
  for (auto& arg : g_ios_args) {
    g_ios_argv.push_back(arg.data());
  }
  g_ios_argv.push_back(nullptr);
  return SDL_RunApp(static_cast<int>(g_ios_args.size()), g_ios_argv.data(), IosMain, nullptr);
}

#elif REX_PLATFORM_ANDROID

extern "C" __attribute__((visibility("default"))) int SDL_main(int argc, char* argv[]) {
  return RunWindowedApp(argc, argv);
}

#else

int main(int argc, char* argv[]) {
  return RunWindowedApp(argc, argv);
}

#endif
