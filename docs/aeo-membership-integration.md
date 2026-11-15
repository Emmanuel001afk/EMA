# EMA AEO membership adapter

This change adds a native Kotlin verifier for the AEO membership credential profile and the UCCS `uccs/1` registration envelope.

## Security boundary

- A valid membership credential establishes that the platform is an AEO ecosystem member.
- It does **not** authenticate a live device or session.
- It does **not** grant access to any EMA capability or another platform's capabilities.
- It does **not** replace peer authentication, provider-controlled grants, scopes, expiry, replay protection, or user consent.
- EMA's existing capability catalogue and its existing Hub integration remain unchanged.

## Cryptographic support

The verifier uses JCA Ed25519 and is enabled only on Android API 33+. EMA's minimum supported API is 26, so API 26–32 devices receive a fail-closed `crypto_unavailable` result. No weaker algorithm or silent fallback is used. A separately reviewed, maintained crypto provider would be required before supporting those older API levels.

The raw 32-byte Ed25519 public key is wrapped in the standard X.509 SubjectPublicKeyInfo prefix for JCA import. Credential signature, issuer, audience, ecosystem, credential type/version, platform identity, publisher claims, UUIDv4 credential ID, time bounds, membership scope, and revocation callback are checked.

## Trust provisioning

The caller must supply trusted issuer public keys and a revocation checker. The verifier does not fetch keys, contact Central Hub, or write to a database. This PR does not yet implement the root-signed trust-bundle bootstrap and monotonic bundle-version persistence available in the Central Hub verifier branch. Do not embed private keys or treat placeholder keys as production trust anchors.

## Validation

JVM tests cover valid credentials, tampered signatures, unknown keys, revoked credentials, wrong audience, API-level fail-closed behavior, and registration identity binding. GitHub Actions results must be checked on this PR before calling the change build-verified.
