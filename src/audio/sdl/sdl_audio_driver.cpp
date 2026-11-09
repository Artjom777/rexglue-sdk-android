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

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdio>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <rex/assert.h>
#include <rex/audio/conversion.h>
#include <cmath>

#include <rex/audio/downmix.h>
#include <rex/audio/flags.h>
#include <rex/audio/sdl/sdl_audio_driver.h>
#include <rex/cvar.h>
#include <rex/dbg.h>
#include <rex/logging.h>
#include <rex/perf/counter.h>
#include <SDL3/SDL.h>

#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
// WASAPI shared mode ignores the period hint and takes two guest frames per 10 ms callback.
#define REX_AUDIO_PACING_DEFAULT true
#else
#define REX_AUDIO_PACING_DEFAULT false
#endif

REXCVAR_DEFINE_BOOL(audio_mute, false, "Audio", "Mute audio output");
REXCVAR_DEFINE_BOOL(audio_release_pacing, REX_AUDIO_PACING_DEFAULT, "Audio",
                    "Hand consumed frames back to the guest one per 5.3 ms instead of in bursts per device callback");
// Android: phones are stereo, and a 6-channel stream costs the audio server ~40% of a core to fold.
#if defined(__APPLE__) || defined(__ANDROID__)
#define REX_AUDIO_FORCE_STEREO_DEFAULT true
#else
#define REX_AUDIO_FORCE_STEREO_DEFAULT false
#endif
REXCVAR_DEFINE_BOOL(audio_force_stereo, REX_AUDIO_FORCE_STEREO_DEFAULT, "Audio",
                    "Submit the stereo fold even when the device reports more channels (macOS reports 6 for "
                    "built-in stereo speakers and its own 5.1 fold drops the centre channel: no dialogue)");
REXCVAR_DEFINE_INT32(audio_device_frames, 256, "Audio",
                     "Audio device period in samples (0 = SDL default); one guest frame is 256");
REXCVAR_DEFINE_STRING(audio_dump_raw, "", "Audio",
                      "Append the device mix (float32, interleaved, device rate/channels) to this file, also when muted");

namespace rex::audio::sdl {

SDLAudioDriver::SDLAudioDriver(memory::Memory* memory, rex::thread::Semaphore* semaphore)
    : AudioDriver(memory), semaphore_(semaphore) {}

SDLAudioDriver::~SDLAudioDriver() {
  assert_true(frames_queued_.empty());
  assert_true(frames_unused_.empty());
}

bool SDLAudioDriver::Initialize() {
  // Set audio category for proper OS audio handling
  SDL_SetHint(SDL_HINT_AUDIO_CATEGORY, "playback");
  // One guest frame (256 samples, 5.3 ms) per device callback: with the default ~1024-sample
  // period the callback took four frames at once, so the guest's audio engine ran four passes
  // back to back and then none for ~16 ms. Games that wait for an audio pass every frame (GTA V's
  // update thread) then ran at most two frames per period.
  if (REXCVAR_GET(audio_device_frames) > 0)
    SDL_SetHint(SDL_HINT_AUDIO_DEVICE_SAMPLE_FRAMES, std::to_string(REXCVAR_GET(audio_device_frames)).c_str());

  // Set app name for audio device identification
  SDL_SetAppMetadataProperty(SDL_PROP_APP_METADATA_NAME_STRING, "rexglue");

  if (!SDL_InitSubSystem(SDL_INIT_AUDIO)) {
    REXAPU_ERROR("SDL_InitSubSystem(SDL_INIT_AUDIO) failed: {}", SDL_GetError());
    return false;
  }
  sdl_initialized_ = true;

  // iOS refuses the audio session now and then (app launched while not in front, another app
  // holding the audio): without a device the game's audio objects stayed null and it crashed at
  // start (guest read of 0x6C). Without a device the frames are consumed silently at the device
  // rate (the guest's audio engine keeps its pace) and the device is retried every 2 s.
  pacing_ = REXCVAR_GET(audio_release_pacing);
  if (!OpenDevice()) {
    REXAPU_WARN("no audio device yet: running silent, retrying every 2 s");
    silent_stop_ = false;
    silent_ = std::thread([this] { SilentThread(); });
  }

  if (pacing_) {
    pacer_stop_ = false;
    pacer_ = std::thread([this] { PacerThread(); });
  }
  return true;
}

bool SDLAudioDriver::OpenDevice() {
  SDL_AudioSpec desired_spec = {};
  SDL_AudioSpec obtained_spec = {};
  desired_spec.freq = frame_frequency_;
  desired_spec.format = SDL_AUDIO_F32LE;
  desired_spec.channels = frame_channels_;
  sdl_device_channels_ = frame_channels_;
  sdl_stream_ = SDL_OpenAudioDeviceStream(SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK, &desired_spec,
                                          SDLCallback, this);
  if (!sdl_stream_) {
    REXAPU_ERROR("SDL_OpenAudioDeviceStream() failed: {}", SDL_GetError());
    CloseDevice();
    return false;
  }

  SDL_AudioDeviceID sdl_device = SDL_GetAudioStreamDevice(sdl_stream_);
  if (!sdl_device) {
    REXAPU_ERROR("SDL_GetAudioStreamDevice() failed: {}", SDL_GetError());
    CloseDevice();
    return false;
  }

  if (!SDL_GetAudioDeviceFormat(sdl_device, &obtained_spec, NULL)) {
    REXAPU_WARN("SDL_GetAudioDeviceFormat() failed: {}", SDL_GetError());
    obtained_spec = desired_spec;
  }

  // A 1-channel device gets the stereo fold too, then SDL collapses to mono.
  // Handing it a 6ch stream instead would use SDL's own downmix.
  if (obtained_spec.channels <= 2 || REXCVAR_GET(audio_force_stereo)) {
    SDL_DestroyAudioStream(sdl_stream_);
    sdl_stream_ = nullptr;
    desired_spec.channels = 2;
    sdl_device_channels_ = 2;
    sdl_stream_ = SDL_OpenAudioDeviceStream(SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK, &desired_spec,
                                            SDLCallback, this);
    if (!sdl_stream_) {
      REXAPU_ERROR("SDL_OpenAudioDeviceStream() stereo fallback failed: {}", SDL_GetError());
      CloseDevice();
    return false;
    }
    sdl_device = SDL_GetAudioStreamDevice(sdl_stream_);
    if (!sdl_device) {
      REXAPU_ERROR("SDL_GetAudioStreamDevice() failed after stereo fallback: {}", SDL_GetError());
      CloseDevice();
    return false;
    }
  }

  // The endpoint layout decides which mix the callback runs, and it is the
  // first thing worth knowing when a report says the balance is wrong on one
  // speaker setup and right on another.
  const char* device_name = SDL_GetAudioDeviceName(sdl_device);
  REXAPU_INFO("audio endpoint '{}': {} ch, {} Hz, format 0x{:04X}; submitting {} ch",
              device_name ? device_name : "?", obtained_spec.channels, obtained_spec.freq,
              static_cast<uint32_t>(obtained_spec.format), static_cast<int>(sdl_device_channels_));

  if (!SDL_ResumeAudioDevice(sdl_device)) {
    REXAPU_ERROR("SDL_ResumeAudioDevice() failed: {}", SDL_GetError());
    CloseDevice();
    return false;
  }

  return true;
}

void SDLAudioDriver::CloseDevice() {
  if (sdl_stream_) {
    SDL_DestroyAudioStream(sdl_stream_);
    sdl_stream_ = nullptr;
  }
}

// No device (see Initialize): one frame per device period is taken from the queue and released to
// the guest as the callback would, and the device is retried every 2 s; once it opens, the
// callback takes over.
void SDLAudioDriver::SilentThread() {
  using Clock = std::chrono::steady_clock;
  const auto period = std::chrono::nanoseconds(1000000000ll * channel_samples_ / frame_frequency_);
  auto next = Clock::now(), retry = next + std::chrono::seconds(2);
  while (!silent_stop_.load(std::memory_order_relaxed)) {
    next += period;
    auto now = Clock::now();
    if (next < now - 4 * period) next = now;
    if (next > now) std::this_thread::sleep_until(next);
    if (now >= retry) {
      retry = now + std::chrono::seconds(2);
      if (OpenDevice()) {
        REXAPU_INFO("audio device opened: sound on");
        return;
      }
    }
    bool released = false;
    {
      std::unique_lock<std::mutex> guard(frames_mutex_);
      if (!frames_queued_.empty()) {
        frames_unused_.push(frames_queued_.front());
        frames_queued_.pop();
        released = true;
      }
    }
    if (!released) continue;
    if (pacing_) {
      pending_releases_.fetch_add(1, std::memory_order_acq_rel);
    } else {
      semaphore_->Release(1, nullptr);
    }
  }
}

void SDLAudioDriver::PacerThread() {
  using Clock = std::chrono::steady_clock;
  const auto period = std::chrono::nanoseconds(1000000000ll * channel_samples_ / frame_frequency_);
#ifdef _WIN32
  SetThreadDescription(GetCurrentThread(), L"Audio pacer");
  SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_TIME_CRITICAL);
  HANDLE timer = CreateWaitableTimerExW(nullptr, nullptr, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS);
#endif
  auto next = Clock::now();
  while (!pacer_stop_.load(std::memory_order_relaxed)) {
    next += period;
    auto now = Clock::now();
    if (next < now - 4 * period) next = now;  // stalled (debugger, suspend): don't burst to catch up
    if (next > now) {
#ifdef _WIN32
      if (timer) {
        LARGE_INTEGER due;
        due.QuadPart = -std::max<int64_t>(1, std::chrono::duration_cast<std::chrono::nanoseconds>(next - now).count() / 100);
        SetWaitableTimerEx(timer, &due, 0, nullptr, nullptr, nullptr, 0);
        WaitForSingleObject(timer, INFINITE);
      } else
#endif
        std::this_thread::sleep_until(next);
    }
    // One frame per period; more when credits pile up (this clock slower than the device's).
    int32_t pending = pending_releases_.load(std::memory_order_acquire);
    if (pending <= 0) continue;
    int32_t n = pending > 2 ? pending - 1 : 1;
    pending_releases_.fetch_sub(n, std::memory_order_acq_rel);
    semaphore_->Release(n, nullptr);
  }
#ifdef _WIN32
  if (timer) CloseHandle(timer);
#endif
}

void SDLAudioDriver::SubmitFrame(uint32_t frame_ptr) {
  const auto input_frame = memory_->TranslateVirtual<float*>(frame_ptr);
  if (!input_frame) {
    if (pacing_) {
      pending_releases_.fetch_add(1, std::memory_order_acq_rel);
    } else if (semaphore_) {
      semaphore_->Release(1, nullptr);
    }
    return;
  }
  float* output_frame;
  {
    std::unique_lock<std::mutex> guard(frames_mutex_);
    if (frames_unused_.empty()) {
      output_frame = new float[frame_samples_];
    } else {
      output_frame = frames_unused_.top();
      frames_unused_.pop();
    }
  }

  std::memcpy(output_frame, input_frame, frame_samples_ * sizeof(float));

  static uint32_t sdl_submit_count = 0;
  if (sdl_submit_count < 10) {
    REXAPU_DEBUG("SDLAudioDriver::SubmitFrame: frame_ptr={:08X} queued_count={}", frame_ptr,
                 frames_queued_.size() + 1);
    sdl_submit_count++;
  }

  {
    std::unique_lock<std::mutex> guard(frames_mutex_);
    frames_queued_.push(output_frame);
    PROFILE_BUFFER_QUEUE_DEPTH(static_cast<int64_t>(frames_queued_.size()));
  }
}

void SDLAudioDriver::Shutdown() {
  if (silent_.joinable()) {
    silent_stop_ = true;
    silent_.join();
  }
  if (pacer_.joinable()) {
    pacer_stop_ = true;
    pacer_.join();
  }
  if (sdl_stream_) {
    SDL_DestroyAudioStream(sdl_stream_);
    sdl_stream_ = nullptr;
  }
  if (sdl_initialized_) {
    SDL_QuitSubSystem(SDL_INIT_AUDIO);
    sdl_initialized_ = false;
  }
  std::unique_lock<std::mutex> guard(frames_mutex_);
  while (!frames_unused_.empty()) {
    delete[] frames_unused_.top();
    frames_unused_.pop();
  }
  while (!frames_queued_.empty()) {
    delete[] frames_queued_.front();
    frames_queued_.pop();
  }
}

namespace {
// --audio_dump_raw: the callback only appends to memory; a writer thread does the file I/O
// (fwrite on a busy disk inside the callback stalled the device to ~0.4x real time).
class RawDump {
 public:
  static RawDump* Get() {
    static RawDump* dump = [] {
      const std::string path = REXCVAR_GET(audio_dump_raw);
      return path.empty() ? nullptr : new RawDump(path);
    }();
    return dump;
  }
  void Append(const void* data, size_t len) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!started_) {
      started_ = true;
      REXAPU_INFO("audio dump starts at steady ms {}",
                  uint32_t(std::chrono::duration_cast<std::chrono::milliseconds>(
                               std::chrono::steady_clock::now().time_since_epoch())
                               .count()));
    }
    auto p = static_cast<const uint8_t*>(data);
    pending_.insert(pending_.end(), p, p + len);
    cv_.notify_one();
  }

 private:
  explicit RawDump(const std::string& path) : file_(std::fopen(path.c_str(), "wb")) {
    std::thread([this] {
      std::vector<uint8_t> out;
      for (;;) {
        {
          std::unique_lock<std::mutex> lock(mutex_);
          cv_.wait(lock, [this] { return pending_.size() >= (64u << 10); });
          out.swap(pending_);
        }
        if (file_) {
          std::fwrite(out.data(), 1, out.size(), file_);
          std::fflush(file_);
        }
        out.clear();
      }
    }).detach();
  }
  FILE* file_;
  std::mutex mutex_;
  std::condition_variable cv_;
  std::vector<uint8_t> pending_;
  bool started_ = false;
};
}  // namespace

void SDLAudioDriver::SDLCallback(void* userdata, SDL_AudioStream* stream, int additional_amount,
                                 [[maybe_unused]] int total_amount) {
  SCOPE_profile_cpu_f("apu");
  if (!userdata || !stream) {
    REXAPU_ERROR("SDLAudioDriver::SDLCallback called with nullptr.");
    return;
  }
  const auto driver = static_cast<SDLAudioDriver*>(userdata);
  const int sample_count =
      static_cast<int>(channel_samples_ * std::max<uint8_t>(driver->sdl_device_channels_, 1));
  const int len = static_cast<int>(sizeof(float) * sample_count);
  float* data = SDL_stack_alloc(float, sample_count);
  if (!data) {
    REXAPU_ERROR("SDLAudioDriver::SDLCallback failed to allocate {} samples", sample_count);
    return;
  }
  // Snapshot once. A change mid-callback would split the frame across two mixes.
  const StereoFold fold = GetStereoFold();
  const SurroundMix mix = GetSurroundMix();
  const float gain = GetOutputGain();
  // gtav: audio health, once per ~10 s of output: silence fills mean the guest delivered late
  // (dropouts), a deep queue means it delivered in bursts (latency).
  static uint32_t stat_frames = 0, stat_silence = 0, stat_max_queued = 0;
  static float stat_peak[6] = {};
  while (additional_amount > 0) {
    static uint32_t sdl_callback_count = 0;
    std::unique_lock<std::mutex> guard(driver->frames_mutex_);
    stat_max_queued = std::max(stat_max_queued, uint32_t(driver->frames_queued_.size()));
    if (++stat_frames >= 1875) {  // 256-sample frames at 48 kHz
      // Per-channel peaks of the guest's 5.1 render (before the fold), dBFS: shows a channel the
      // game leaves empty (FL FR FC LFE BL BR).
      auto db = [](float p) { return p > 1e-6f ? 20.0f * std::log10(p) : -120.0f; };
      REXAPU_INFO("audio: {} of {} frames were silence (guest late), max {} queued; peak dB "
                  "FL {:.0f} FR {:.0f} FC {:.0f} LFE {:.0f} BL {:.0f} BR {:.0f}",
                  stat_silence, stat_frames, stat_max_queued, db(stat_peak[0]), db(stat_peak[1]),
                  db(stat_peak[2]), db(stat_peak[3]), db(stat_peak[4]), db(stat_peak[5]));
      stat_frames = stat_silence = stat_max_queued = 0;
      for (float& p : stat_peak) p = 0;
    }
    if (driver->frames_queued_.empty()) {
      stat_silence++;
      if (sdl_callback_count < 10) {
        REXAPU_DEBUG("SDLCallback: no frames queued (silence)");
        sdl_callback_count++;
      }
      std::memset(data, 0, len);
      if (RawDump* dump = RawDump::Get()) dump->Append(data, size_t(len));
      if (!SDL_PutAudioStreamData(stream, data, len)) {
        REXAPU_ERROR("SDL_PutAudioStreamData() failed while filling silence: {}", SDL_GetError());
        break;
      }
      additional_amount -= len;
    } else {
      auto buffer = driver->frames_queued_.front();
      driver->frames_queued_.pop();
      for (uint32_t c = 0; c < 6; ++c) {
        const float* ch = buffer + c * channel_samples_;
        for (uint32_t i = 0; i < channel_samples_; ++i)
          stat_peak[c] = std::max(stat_peak[c], std::fabs(rex::byte_swap(ch[i])));
      }
      {
        switch (driver->sdl_device_channels_) {
          case 2:
            conversion::sequential_6_BE_to_interleaved_2_LE(data, buffer, channel_samples_, fold,
                                                            gain);
            break;
          case 6:
            conversion::sequential_6_BE_to_interleaved_6_LE(data, buffer, channel_samples_, mix,
                                                            gain);
            break;
          default:
            assert_unhandled_case(driver->sdl_device_channels_);
            break;
        }
        if (RawDump* dump = RawDump::Get()) dump->Append(data, size_t(len));
        if (REXCVAR_GET(audio_mute)) std::memset(data, 0, len);
      }
      if (!SDL_PutAudioStreamData(stream, data, len)) {
        REXAPU_ERROR("SDL_PutAudioStreamData() failed: {}", SDL_GetError());
        driver->frames_unused_.push(buffer);
        break;
      }
      driver->frames_unused_.push(buffer);

      if (driver->pacing_) {
        driver->pending_releases_.fetch_add(1, std::memory_order_acq_rel);
      } else if (driver->semaphore_) {
        auto ret = driver->semaphore_->Release(1, nullptr);
        assert_true(ret);
      }
      additional_amount -= len;
    }
  }
  SDL_stack_free(data);
}

}  // namespace rex::audio::sdl
