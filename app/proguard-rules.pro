# Conservative EMA release shrinker rules.
# Manifest entry points are kept so Android services/receivers remain callable.
#
# Kept in sync with app/src/main/AndroidManifest.xml. AGP also derives keep rules
# from the manifest; these are explicit so a rule change cannot silently drop an
# entry point that Android instantiates by name.
-keep class com.ema.assistant.MainActivity { *; }
-keep class com.ema.assistant.EmaVoiceInteractionService { *; }
-keep class com.ema.assistant.EmaVoiceInteractionSessionService { *; }
-keep class com.ema.assistant.EmaNotificationListenerService { *; }
-keep class com.ema.assistant.EmaAccessibilityService { *; }
-keep class com.ema.assistant.EmaScheduledActionReceiver { *; }
-keep class com.ema.assistant.EmaReminderReceiver { *; }
-keep class com.ema.assistant.EmaRoutineReceiver { *; }
-keep class com.ema.assistant.EmaBootReceiver { *; }

# EmaReminderReceiver delegates to this nested receiver class.
-keep class com.ema.assistant.EmaReminderScheduler$Receiver { *; }

# BuildConfig carries the Central Hub endpoint as a compile-time constant.
-keep class com.ema.assistant.BuildConfig { *; }

# ML Kit provides its own consumer rules. Do not broadly keep ML libraries.
