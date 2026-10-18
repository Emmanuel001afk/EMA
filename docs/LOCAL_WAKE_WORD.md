# EMA local wake-word architecture

EMA will own the wake-word pipeline: no Picovoice key, no cloud audio, and no AI request while idle.

## Current implementation milestone

- `EmaWakeWordEngine` is a dependency-free Kotlin DSP baseline: mono PCM16/16 kHz input, log-spaced Goertzel band features, normalized frame vectors, and subsequence DTW matching.
- The engine accepts a local enrolled phrase template and returns a distance/match result. Audio and templates can remain on-device.
- JVM tests cover input validation, template creation, and deterministic self-match.
- This is **not yet an always-listening feature**. It is not connected to a microphone service, enrollment screen, persisted templates, or assistant-session activation. Do not expose an "always listening" toggle as working until those pieces and device tests are complete.

## Build sequence

1. Add guided enrollment: capture several user-spoken "Hey EMA" samples, trim silence, reject clipped/noisy samples, and store templates locally.
2. Add a microphone capture component with explicit user opt-in, visible persistent microphone notification, pause/stop controls, audio focus and lifecycle handling. Never start microphone foreground work silently from boot.
3. Run bounded rolling-window matching and calibrate thresholds against negative speech/noise. Multiple enrolled samples should be combined; rate-limit triggers and require a cooldown.
4. On a match, ask Android to show EMA's voice session; do not bypass keyguard. If the device/OEM denies background microphone access, surface a diagnostic and fall back to tap/assistant gesture.
5. Add diagnostics for permission, selected assistant, microphone service state, last match distance, false triggers, battery restrictions and session launch failures.

## Important constraints

This first matcher is an engineering baseline, not a production-grade trained acoustic model. Spectral template matching can confuse similar phrases and environmental sounds. It needs a proper negative-audio evaluation and target-device calibration before enabling unattended wake-up. The selected Android `VoiceInteractionService` is useful infrastructure but does not grant ordinary apps unrestricted access to privileged hardware hotword DSP APIs.
