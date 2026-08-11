# Third-party code in `util/crypto`

## Google Tink — pure-Java X25519 (Apache License 2.0)

`X25519.java`, `Curve25519.java` and `Field25519.java` in this directory are copied from
[tink-crypto/tink-java](https://github.com/tink-crypto/tink-java), release tag **v1.19.0**:

| File | Upstream path (tag `v1.19.0`) |
|---|---|
| `X25519.java` | `src/main/java/com/google/crypto/tink/subtle/X25519.java` |
| `Curve25519.java` | `src/main/java/com/google/crypto/tink/internal/Curve25519.java` |
| `Field25519.java` | `src/main/java/com/google/crypto/tink/internal/Field25519.java` |

Copyright 2017 Google Inc., licensed under the Apache License, Version 2.0. The full license text
is at <http://www.apache.org/licenses/LICENSE-2.0>; each file keeps its original license header.
The implementation derives from [curve25519-donna](https://github.com/agl/curve25519-donna).

### Why vendored rather than a dependency

PC Link pairing needs X25519 (RFC 7748) on every device the app runs on. Android's native XDH
(`KeyAgreement.getInstance("XDH")`, `XECPublicKeySpec`) only exists from **API 33**, and this app's
`minSdk` is **29** — so API 29–32 devices have no platform X25519 at all, and Conscrypt's internal
one has no stable public API there. Taking the whole `tink-android` artifact would add megabytes
for a single primitive; BouncyCastle would add size plus JCE-provider conflict risk; and a
hand-rolled RFC 7748 would put *us* on the hook for its constant-time correctness. Three
self-contained, audited files, used on all API levels (one code path), is the cheapest correct
answer. See `docs/pairing-design.md` §12.2 in the `xplayer-link-server` repo.

### Local modifications

Mechanical only — the field/curve arithmetic is byte-for-byte upstream:

1. `package` changed to `com.teleteh.xplayer2.util.crypto` (Java requires package == directory).
   `Curve25519` and `Field25519` therefore lose their `com.google.crypto.tink.internal` imports in
   `X25519.java` — they are now same-package.
2. Dropped the `@Alpha` annotation and its import (a Tink-internal API-stability marker; nothing
   about the code changes with it gone).
3. `Curve25519`: `Bytes.equal(a, b)` → `java.security.MessageDigest.isEqual(a, b)`. Same
   constant-time contract, avoids vendoring Tink's `Bytes`.
4. `Curve25519`: `Hex.encode(...)` → a private `hexEncode(...)` added at the bottom of the file.
   Only used to render a key inside an exception message.
5. `X25519`: `Random.randBytes(...)` → a private `randomBytes(...)` backed by
   `java.security.SecureRandom`. (XPlayer2 does not actually call `generatePrivateKey()` — identity
   keys are 32 raw `SecureRandom` bytes, clamped by `computeSharedSecret` at use time, matching how
   the Rust server's `x25519-dalek` stores a `StaticSecret` — but the method is kept working rather
   than deleted, so the file stays diffable against upstream.)

To re-sync with a newer Tink release: re-download the three files at the new tag and re-apply the
five edits above (they are all one-liners except the two small helper methods).

Verified after vendoring: `publicFromPrivate` / `computeSharedSecret` reproduce the RFC 7748 §6.1
known-answer test, which is also `PcLinkPairingCryptoTest`'s vector 1 (`sharedSecret` =
`4a5d9d5b…161742`).
