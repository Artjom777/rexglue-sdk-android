// Dear ImGui build configuration for the runtime (thirdparty/CMakeLists.txt, IMGUI_USER_CONFIG).
// The current context is per thread: the SDK's ImGuiDrawer switches contexts on the UI thread for
// every input event while games can run their own context on a render thread (GTA V's native
// renderer menu); with one process-wide GImGui the two threads swapped contexts under each other.
#pragma once
struct ImGuiContext;
extern thread_local ImGuiContext* RexImGuiContextTLS;
#define GImGui RexImGuiContextTLS
