# EMA local wake-word architecture

EMA owns this wake-word pipeline. It requires no Picovoice key, third-party wake-word SDK, cloud audio upload, or AI request while idle.

## Implemented in this branch

- `EmaWakeWordEngine`: dependency-free Kotlin DSP baseline for mono PCM16 at 16 kHz, using log-spaced Goertzel band features and dynamic time warping.
- `EmaWakeWordSetupActivity`: guided capture of three local “Hey EMA” samples, local persistence, and explicit start/stop controls.
- `EmaWakeWordService`: visible microphone foreground service with a rolling audio window, local template matching, cooldown, and service status stored on-device.
- `EmaVoiceInteractionService`: opens EMA's voice session after the in-app wake event, subject to Android's restrictions and keyguard.
- JVM tests cover engine input validation, template creation, and deterministic self-match.

## Setup and use

1. Install a build containing this branch and open EMA.
2. Grant microphone permission.
3. Choose **Set up Hey EMA (local wake word)**.
4. In a quiet room, record “Hey EMA” three times, then save enrollment.
5. Tap **Start Hey EMA listening**. Keep the persistent notification visible. Use **Stop listening** to turn it off.
6. Make EMA the selected Android digital assistant if you want its voice-interaction session to be launched.

The service starts only after a user action; it does not silently turn on the microphone at boot. Enrollment samples remain local. The microphone is released before the assistant session attempts to listen for a command.

## Important limitations before calling this production-ready

This is an engineering prototype, not a trained neural wake-word model. The distance threshold is provisional and has not yet been calibrated against hours of negative speech/noise or tested on physical devices. It can miss “Hey EMA” or false-trigger on similar sounds. Do not rely on it for safety-critical commands or claim production-grade accuracy until a recorded evaluation suite and target-device calibration pass.

Android and OEM power/microphone policies can stop background capture even when a foreground service is used. Starting the listener requires the app to be in a permitted foreground state and microphone permission to be granted. A wake event does not unlock the phone or bypass keyguard. Hardware-assisted hotword APIs remain restricted to privileged/system integrations.
