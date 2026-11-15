# UCCS integration audit — EMA

**Audit status:** source-inspected; documentation only  
**Audited base:** `main` at `16b1701917af826435db6b0737335ef0caed4749`  
**UCCS target:** AEO Universal Cross-Platform Coordination System, draft 0.1  
**Important:** This records compatibility findings. It does not claim EMA is UCCS-conformant and does not change runtime behavior.

## 1. Existing EMA foundations

The inspected EMA source contains a useful local capability catalogue:

- `EmaCapabilityRegistry.kt` defines EMA's own operations, prerequisites, mechanisms, verification descriptions and consequential-action flag.
- The registry is used for local command-plan validation and honest self-description based on permissions available on the phone.
- `EmaAssistantRegistration.kt` reports whether EMA holds Android's assistant role and has active voice/recognition services. This is Android assistant-role registration, not AEO ecosystem membership.
- `CentralHubClient.kt` identifies EMA as `com.ema.assistant`, persists a stable device ID and a Central Hub token, registers the device with the configured Hub endpoint, and sends AI-question requests.
- EMA's README states that native deterministic execution remains primary and Central Hub is an optional AI layer. EMA's device actions do not depend on the AI tunnel.

These components must retain their current responsibilities. UCCS should not turn the local capability registry into a remote command executor or make EMA dependent on Central Hub.

## 2. UCCS compatibility mapping

| UCCS concern | Existing EMA support | Audit result |
|---|---|---|
| Local capability catalogue | `EmaCapabilityRegistry` with IDs, prerequisites, mechanisms and verification | Reusable local source of capability metadata |
| Capability schemas and versions | Registry entries do not expose versioned JSON input/output schemas or network invocation contracts | Missing in inspected contract |
| AEO membership | No signed AEO membership assertion found in inspected files | Missing |
| Publisher identity / trust proof | Package ID and device ID are used for Hub registration; no publisher-key membership proof found | Missing |
| Device identity | Stable locally persisted device UUID used for Hub registration | Partial; not a publisher/platform membership identity |
| Android assistant registration | Role/service/recognition-service status | Separate concern; not UCCS membership |
| Peer discovery | No UCCS peer-discovery mechanism found in inspected files | Not established |
| Capability invocation by other platforms | Local command routing exists; no general authenticated external capability endpoint found in inspected files | Missing in inspected scope |
| Authorization and consent | Local Android permission prerequisites and consequential command gating | Useful local safety model; not a cross-platform peer-grant model |
| Central Hub connection | Token-based registration and AI-question tunnel | Existing AI service integration, not UCCS registry or universal platform coordinator |
| Offline operation | Core native assistant functionality remains available without AI service | Must be preserved; UCCS discovery must not become a dependency for local EMA actions |
| Conformance tests | Local capability and command tests exist; no UCCS cross-platform conformance vectors found in inspected scope | Needs shared tests |

## 3. Required EMA integration approach

1. Keep `EmaCapabilityRegistry` as the authoritative local catalogue of what EMA can actually execute and verify.
2. Add a UCCS adapter that exports only capabilities intentionally approved for external discovery. Do not automatically publish every local command.
3. For each externally exposed capability, define a stable UCCS capability ID/version, JSON input/output schemas, execution locality, required scopes, consent policy, timeout and data-handling description.
4. Provider-side execution must re-check the current Android permissions, local policy and user consent at invocation time. A manifest is descriptive, not an authorization grant.
5. Use authenticated peer identity, scoped grants, expiry, revocation, nonce/replay protection and request signatures before accepting remote invocations.
6. Keep consequential actions (messages, calls, file transfer or other actions with external effects) behind explicit policy and user confirmation as required. AEO membership alone must never authorize them.
7. Keep Central Hub's AI tunnel separate. It may later discover or coordinate through UCCS if deliberately designed to do so, but it is not the UCCS authority merely because EMA already connects to it.
8. Preserve native-first execution and full local operation when the network, Central Hub or any future UCCS registry is unavailable.

## 4. Security and compatibility gates

EMA must not be marked UCCS-conformant until tests demonstrate:

- Membership assertions and publisher trust are cryptographically verified.
- Unknown, expired, revoked or invalidly signed members are rejected.
- Only explicitly exported capabilities are discoverable.
- A caller cannot invoke a capability by guessing its ID or reading its manifest.
- Requests are authenticated, scoped, unexpired, non-replayed and addressed to the intended EMA installation.
- Current Android prerequisites and local consent are checked immediately before execution.
- Input and output are validated against the advertised schemas.
- Consequential actions cannot bypass EMA's existing local confirmation and verification rules.
- Remote requests cannot bypass EMA's deterministic command/action safety gates.
- Local EMA commands continue to work with airplane mode enabled and without Central Hub or registry access.

## 5. Scope and limitations

This audit inspected:

- `app/src/main/java/com/ema/assistant/EmaCapabilityRegistry.kt`
- `app/src/main/java/com/ema/assistant/EmaAssistantRegistration.kt`
- `app/src/main/java/com/ema/assistant/CentralHubClient.kt`
- `app/src/main/AndroidManifest.xml`
- `README.md`

This is not a complete audit of every Android component, network route, database or test. No runtime files were changed by this audit.
