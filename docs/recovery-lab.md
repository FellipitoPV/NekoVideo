# Recovery Lab: embedded recovery fallback experiment

This describes the separately installed laboratory, **not a migration of existing private videos**.
Its tested core is now shared with the normal Debug app. See [Debug integration](private-recovery-debug.md)
for the current application behavior; the earlier reports document the isolated stages.

## Isolation

- Application ID: `com.nkls.nekovideo.recoverylab` (different from release and debug).
- Launcher: **NekoVideo Recovery Lab**. Its manifest removes the normal `MainActivity`.
- All experiment reads/writes are restricted to the canonical workspace below this app's own
  `getExternalFilesDir(null)/recovery-lab`. Its input directory contains disposable copies.
- Use copies of ordinary memes in the input directory. Legacy compatibility tests use a separate,
  explicitly copied `legacy-fixture-20261026` directory, never the real private folder.
- The experiment never traverses `/storage/emulated/0/NekoVideo`, changes the real vault password,
  or starts the normal library scanner. No all-files permission is needed for its own workspace.
- Password: `NekoVideo-lab-only-2026`, disposable and unrelated to the real vault password.
- No files from `src/recoveryLab` ship in the debug/release variants.

## What is being tested

The payload retains the existing **XOR protection on the first 8 KiB**. This is not full video
encryption. A new, independently password-protected recovery record is appended to each copy:

```
protected media bytes | AES-GCM recovery record | authenticated 64-byte footer
```

The record includes a random file ID, original basename/size, original and protected SHA-256,
and a random per-file XOR key. The footer contains format/version, fixed KDF parameters, group
salt, nonce and lengths; the entire footer is authenticated as associated data. Names and the
content key are encrypted. Mutable filesystem paths and external names are not cryptographic
dependencies. Salt/nonce/length fields are public; credentials and the open key are not.

PBKDF2-HMAC-SHA256 (210,000 iterations) derives a wrapping key cached once per group per session.
This iteration count is an experiment setting, not a completed password-hardening review.
AES-GCM uses fresh random 96-bit nonces on each record/index write. A group shares its wrapping
key, not its per-file content keys. Large-scale nonce/key lifecycle policy remains production work.

The authenticated `.neko_recovery_index.enc` duplicates the records. A matching filename,
payload size and fingerprint of the actual embedded record allow the manifest fast path.
Missing, damaged, incomplete or stale indexes fall back to the authenticated embedded record.
Reading the short trailer is still required to avoid blindly trusting stale cached entries.
Explicit rebuild validates candidates before replacing the index; reading does not silently repair it.
The decrypted index is bound to the exact unlocked keyring session. A different/closed session cannot
reuse it to bypass password verification. Filename lookup is indexed in memory rather than a linear
search. Indexes are bounded to 10,000 entries and 16 MiB; per-file recovery records to 16 KiB.

Restore creates a new file, checks both full-file hashes and only then publishes it. It never
changes/deletes the protected input. Metadata corruption/wrong password is rejected by AES-GCM;
payload corruption is checked on **export**, not on every playback segment. The latter limitation
comes from retaining legacy partial protection and is not equivalent to authenticated video encryption.

The laboratory media reader excludes the trailer and supports reads at arbitrary media offsets.
It is used by `MediaMetadataRetriever` and a separate Media3 demo player.

## Build and test

```powershell
.\gradlew.bat :app:testRecoveryLabUnitTest :app:assembleRecoveryLab :app:assembleRecoveryLabAndroidTest -PnekoRecoveryLab=true
```

`-PnekoRecoveryLab=true` selects the test target only; normal builds still target debug by default.
Unit tests cover byte-exact restore, absent/corrupt/stale/incomplete indexes, move/rename in a new
session, mixed groups, wrong password, altered metadata/payload, malformed/truncated footer,
workspace escape, existing-output preservation, small files and KDF caching.

Install only the generated Recovery Lab APK and its matching instrumentation APK. Launch it once
to create its external workspace. Place two or three **copies** of ordinary MP4 memes in:

```
/sdcard/Android/data/com.nkls.nekovideo.recoverylab/files/recovery-lab/input/
```

Run instrumentation methods individually using `adb shell am instrument -w -e class` and the
runner `com.nkls.nekovideo.recoverylab.test/androidx.test.runner.AndroidJUnitRunner`:

1. `com.nkls.nekovideo.recoverylab.RecoveryLabDeviceTest#prepareBeforeExternalMove`
2. Outside the app, move/rename only the generated `.nekolab` files from the active run's
   `protected` directory to `externally-moved/renamed-0`, `renamed-1`, etc. Move the lab manifest
   to `withheld-metadata` to make it unavailable, preserving it for inspection.
3. `com.nkls.nekovideo.recoverylab.RecoveryLabDeviceTest#recoverAfterExternalMoveInNewProcess`
4. `com.nkls.nekovideo.recoverylab.RecoveryLabDeviceTest#media3PlaysAndSeeksUsingEmbeddedFallback`
5. `com.nkls.nekovideo.recoverylab.RecoveryLabDeviceTest#legacyFolderStillLocksAndRestoresWithoutPortableFormat`

The active run is recorded as a validated relative path in `recovery-lab/active-run.txt`.
The second test starts a fresh instrumentation process, verifies embedded fallback, compares
restored SHA-256 with the copied inputs, checks random reads and identical decoded frames at
the beginning/middle, rejects a wrong password and rebuilds the index. The legacy test invokes
the unchanged production lock/unlock functions only on another disposable copy in the lab workspace.
Reports remain under the run directory. Source memes should also have ADB SHA-256 checked
before/after independently of this application.

The Media3 device test opens a real `PlayerView`, waits for a rendered video frame, seeks to
the middle, and waits for another rendered frame. Playback is muted during automation only.

The app's test button performs the same local sequence without ADB intervention; the playback
button opens the first moved file using embedded recovery even after the index has been rebuilt.

## Not implemented / not implied

- No automatic migration, normal-library integration or recovery of old files with lost metadata.
- No full-video authenticated encryption, production password rotation/revocation or recovery-key UX.
  Laboratory password-change tests rewrap **new copies**, preserve media bytes and can resume a copy
  batch by validating already completed outputs. They do not change the actual app/vault password.
- No automatic search of arbitrary device folders, including the real private vault.
- No full throughput/battery benchmark; timings are small-sample laboratory observations.
- App-specific laboratory copies are removed by Android if this separate app is uninstalled.

## Extended compatibility and failure tests

`attachLegacyCopy` can append recovery to a new copy of legacy ciphertext after the original legacy
manifest has been authenticated. It computes independent plaintext/protected hashes in streaming
memory; it does not export plaintext as an intermediate step or alter the legacy source.
This is an experimental bridge, not an automatic library migration.

`AdvancedRecoveryLabDeviceTest` adds two separately orchestrated legacy prepare/recover methods,
250 physical protected copies, a synthetic 10,000-entry index, a complete synthetic 256 MiB round-trip,
sparse reads beyond 2/4 GiB, thumbnail comparison and loopback-only HTTP Range validation.
The legacy methods accept the user-provided password only as the `legacyPassword` instrumentation
argument. Do not put real passwords in test source, committed scripts, reports or configuration.

The loopback server adapts the new reader to full/range responses and excludes the recovery trailer.
It is a transport experiment; the production DLNA server is unchanged and an actual TV/receiver has
not been validated by these tests.

`scripts/test-recovery-lab-crashes.ps1` orchestrates a real `am force-stop` of **only** the lab package
after file fsync but before publication, separately for protect/export/index/password-copy batches.
Each verification runs in a new process, confirms unchanged inputs and the previously committed
index, retries/resumes the operation, then removes only the known orphaned temporary files of that
test run. This validates process termination, not physical power loss or an actual full disk. Graceful
write exceptions are injected by unit tests. The normal UI does not automatically recover staged
temporary files; production journaling/cleanup remains separate integration work.
