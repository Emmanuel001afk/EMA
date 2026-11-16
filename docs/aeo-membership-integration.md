# EMA AEO membership adapter

This change adds a native Kotlin verifier for AEO Ed25519 membership credentials, UCCS `uccs/1` registration envelopes, and root-signed trust bundles.

## Security boundary

- Membership establishes ecosystem membership only.
- It does not authenticate a live device/session, authorize capabilities, or represent user consent.
- EMA's existing capability catalogue and Central Hub integration remain separate and unchanged.
- No traffic is required to pass through Central Hub.

## Cryptographic support

The verifier uses JCA Ed25519 and is enabled only on Android API 33+. EMA supports API 26+, so API 26–32 fail closed with `crypto_unavailable`; there is no algorithm downgrade. A separately reviewed maintained provider is required before supporting older API levels.

## Trust bundles

The adapter verifies compact JWS trust bundles against pinned Ed25519 root public keys, validates issuer-key records and credential revocation IDs, and enforces expiry and a caller-supplied minimum version. The caller must persist the highest accepted bundle version in EMA-owned secure storage and pass it as `minimumVersion`. The verifier itself does not persist state or fetch trust material. Root custody, rotation/recovery, live issuer/revocation distribution, and initial platform registration remain production prerequisites.

## Capability discovery

`EmaAeoCapabilityManifest` projects EMA's existing capability registry into short-lived UCCS discovery metadata. It does not expose remote command execution, grant capabilities, or bypass EMA's local permission and command-validation pipeline.

## Validation

JVM tests cover membership verification, registration identity binding, root-signed trust-bundle verification, rollback rejection, invalid roots/signatures, and API-level fail-closed behavior. CI must pass on the latest PR commit before merge.
