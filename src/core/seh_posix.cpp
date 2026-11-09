/**
 * @file        core/seh_posix.cpp
 * @brief       POSIX platform SEH implementations
 *
 * @copyright   Copyright (c) 2026 Tom Clay <tomc@tctechstuff.com>
 * @license     BSD 3-Clause License
 */

#include <rex/platform.h>
#include <rex/platform/seh.h>

static_assert(REX_PLATFORM_LINUX || REX_PLATFORM_MAC, "This file is POSIX-only");

#include <signal.h>
#include <cstdlib>  // std::abort()
#if defined(__ANDROID__)
#include <dlfcn.h>
#include <ucontext.h>
#include <unistd.h>
#include <cstdio>
#endif

#include <rex/platform/exceptions.h>

namespace rex::platform {

static thread_local SehThreadState tls_seh_state;

// Handlers installed before seh_initialize (the runtime's guest-memory exception handler, ART's
// signal chain and debuggerd on Android): faults outside SEH regions go to them.
static struct sigaction g_previous[NSIG];

static void ChainToPrevious(int sig, siginfo_t* info, void* ucontext) {
  const struct sigaction& prev = g_previous[sig];
  if (prev.sa_flags & SA_SIGINFO) {
    if (prev.sa_sigaction) {
      prev.sa_sigaction(sig, info, ucontext);
      return;
    }
  } else if (prev.sa_handler != SIG_DFL && prev.sa_handler != SIG_IGN && prev.sa_handler) {
    prev.sa_handler(sig);
    return;
  }
#if defined(__ANDROID__) && defined(__aarch64__)
  // Under ART the default action dies without a tombstone: report the fault on stderr first.
  {
    const mcontext_t& mc = static_cast<ucontext_t*>(ucontext)->uc_mcontext;
    auto frame = [](const char* tag, uint64_t pc) {
      Dl_info dl{};
      if (dladdr(reinterpret_cast<void*>(pc), &dl) && dl.dli_fname) {
        fprintf(stderr, "  %s %016llx %s+0x%llx %s\n", tag, (unsigned long long)pc, dl.dli_fname,
                (unsigned long long)(pc - uint64_t(dl.dli_fbase)), dl.dli_sname ? dl.dli_sname : "");
      } else {
        fprintf(stderr, "  %s %016llx ?\n", tag, (unsigned long long)pc);
      }
    };
    fprintf(stderr, "fatal signal %d, fault address %p, thread %d\n", sig, info ? info->si_addr : nullptr,
            int(gettid()));
    frame("pc", mc.pc);
    frame("lr", mc.regs[30]);
    for (int r = 0; r < 31; r += 4) {
      fprintf(stderr, "  x%d %016llx %016llx %016llx %016llx\n", r, (unsigned long long)mc.regs[r],
              (unsigned long long)mc.regs[r + 1], (unsigned long long)mc.regs[r + 2],
              r + 3 < 31 ? (unsigned long long)mc.regs[r + 3] : 0ull);
    }
    uint64_t fp = mc.regs[29];
    for (int i = 0; i < 40 && fp && (fp & 7) == 0; i++) {
      uint64_t next = reinterpret_cast<uint64_t*>(fp)[0];
      uint64_t ret = reinterpret_cast<uint64_t*>(fp)[1];
      if (!ret) break;
      frame("fp", ret);
      if (next <= fp) break;
      fp = next;
    }
    fflush(stderr);
  }
#endif
  signal(sig, SIG_DFL);
  raise(sig);
}
static thread_local bool tls_seh_active = false;

SehThreadState& seh_thread_state() {
  return tls_seh_state;
}

int seh_filter(uint32_t /*code*/, void* /*ep*/) {
  // Not used on POSIX - signal handlers throw directly
  return 0;
}

/// Signal handler for SIGSEGV/SIGBUS/SIGFPE/SIGILL
static void signal_handler(int sig, siginfo_t* info, void* ucontext) {
  // Only handle if we're in SEH-protected code
  if (!tls_seh_active) {
    // Not in an SEH region: the previous handler decides (it may be a guest-memory access the
    // runtime resolves). Resetting to SIG_DFL here killed the process silently, with no crash
    // report, and bypassed the runtime's own handler.
    ChainToPrevious(sig, info, ucontext);
    return;
  }

  // Determine exception code based on signal
  SehException::Code code;
  switch (sig) {
    case SIGSEGV:
      code = SehException::ACCESS_VIOLATION;
      break;
    case SIGBUS:
      code = SehException::IN_PAGE_ERROR;
      break;
    case SIGFPE:
      code = SehException::FLOAT_DIVIDE_BY_ZERO;
      break;
    case SIGILL:
      code = SehException::ILLEGAL_INSTRUCTION;
      break;
    default:
      code = SehException::UNKNOWN;
      break;
  }

  // Get fault address
  uintptr_t address = info ? reinterpret_cast<uintptr_t>(info->si_addr) : 0;

  // Store in thread state for potential rethrow
  tls_seh_state.code = static_cast<uint32_t>(code);
  tls_seh_state.info[0] = 0;
  tls_seh_state.info[1] = address;

  // Use libunwind to throw from signal context
  // This works because libunwind can unwind through signal frames
  throw SehException(code, address);
}

[[noreturn]] void seh_rethrow() {
  // Map stored code back to signal and re-raise
  uint32_t code = tls_seh_state.code;
  int sig;
  switch (code) {
    case SehException::ACCESS_VIOLATION:
      sig = SIGSEGV;
      break;
    case SehException::IN_PAGE_ERROR:
      sig = SIGBUS;
      break;
    case SehException::FLOAT_DIVIDE_BY_ZERO:
    case SehException::INTEGER_DIVIDE_BY_ZERO:
      sig = SIGFPE;
      break;
    case SehException::ILLEGAL_INSTRUCTION:
      sig = SIGILL;
      break;
    default:
      abort();
  }
  // Restore default handler and re-raise
  signal(sig, SIG_DFL);
  raise(sig);
  abort();  // Should not reach here
}

void seh_initialize() {
  if (g_seh_initialized.exchange(true)) {
    return;  // Already initialized
  }

  struct sigaction sa;
  sa.sa_sigaction = signal_handler;
  sigemptyset(&sa.sa_mask);
  sa.sa_flags = SA_SIGINFO | SA_NODEFER;  // SA_NODEFER allows re-entry for nested exceptions

  sigaction(SIGSEGV, &sa, &g_previous[SIGSEGV]);
  sigaction(SIGBUS, &sa, &g_previous[SIGBUS]);
  sigaction(SIGFPE, &sa, &g_previous[SIGFPE]);
  sigaction(SIGILL, &sa, &g_previous[SIGILL]);
}

bool& seh_active() {
  return tls_seh_active;
}

}  // namespace rex::platform
