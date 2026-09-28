# EMA
Native Android digital assistant.
This repository was cleared before the rebuild. Projects Continuity and Central Hub are separate and are not part of this repository.
Architecture: native Android; deterministic phone/app actions without AI; AI through the Central Hub shared AI tunnel; no LLM API key in EMA; Android assistant role for system invocation; accessibility reserved for explicit screen understanding and UI automation.
Central Hub exposes POST /api/ai-tunnel. The deployed MayoBuild Studio origin must be inserted into CentralHubClient.kt before a release build.
Public foundations reviewed: Orbit Assistant, DeVA and OpenDroid. Their provider layers were not copied into this initial commit.
