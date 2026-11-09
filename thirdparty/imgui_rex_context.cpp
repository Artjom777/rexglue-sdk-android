// Storage of the per-thread ImGui context (see imgui_rex_config.h).
#include "imgui_rex_config.h"

thread_local ImGuiContext* RexImGuiContextTLS = nullptr;
