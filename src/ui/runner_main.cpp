/**
 * @file        ui/runner_main.cpp
 * @brief       Default generic game runner application entrypoint
 *
 * @copyright   Copyright (c) 2026 Tom Clay <tomc@tctechstuff.com>
 *              All rights reserved.
 *
 * @license     BSD 3-Clause License
 *              See LICENSE file in the project root for full license text.
 */

#include <rex/rex_app.h>
#include <rex/ui/windowed_app.h>
#include <memory>

namespace {

class ReXRunnerApp : public rex::ReXApp {
public:
  using rex::ReXApp::ReXApp;

  static std::unique_ptr<rex::ui::WindowedApp> Create(rex::ui::WindowedAppContext& ctx) {
    rex::PPCImageInfo ppc_info{};
    return std::unique_ptr<ReXRunnerApp>(new ReXRunnerApp(ctx, "rexglue_game", ppc_info));
  }
};

}  // namespace

REX_DEFINE_APP(rexglue_game, ReXRunnerApp::Create)
