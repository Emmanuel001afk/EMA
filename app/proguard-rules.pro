# Conservative EMA release shrinker rules.
# Manifest entry points are kept so Android services/receivers remain callable.
-keep class com.ema.assistant.MainActivity { *; }
-keep class com.ema.assistant.EmaVoiceInteractionService { *; }
-keep class com.ema.assistant.EmaVoiceInteractionSessionService { *; }
-keep class com.ema.assistant.EmaRecognitionService { *; }
-keep class com.ema.assistant.EmaNotificationListenerService { *; }
-keep class com.ema.assistant.EmaAccessibilityService { *; }
-keep class com.ema.assistant.EmaScheduledActionReceiver { *; }
-keep class com.ema.assistant.EmaReminderReceiver { *; }
-keep class com.ema.assistant.EmaRoutineReceiver { *; }

# ML Kit provides its own consumer rules. Do not broadly keep ML libraries.
