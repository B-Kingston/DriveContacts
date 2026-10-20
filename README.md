# Cardvault

Cardvault is a contacts app. It keeps all contacts in one end-to-end encrypted vCard file in your Proton Drive (`My files/Cardvault/contacts.vcf`).

This is a third-party application not officially supported by Proton.

Status: Android first. iOS follows and uses the same format, merge rules and test vectors (`docs/`).

## What it does

- Sign-in through the Proton web page (session fork, the same flow as the Proton Drive SDK CLI). The app never sees your password.
- Sync with Proton Drive. The app reads Drive events and downloads the file only after a change. Each change uploads a new revision.
- Three-way merge per contact and per field. A field that two devices change at the same time becomes a conflict. The conflict screen shows "This device" and "Proton Drive" side by side. "Compare files" shows all differences between the two files of that merge.
- Two-way mirror into the phone Contacts app through a Cardvault account.
- Import from the accounts on the phone (Google, device, others) and from vCard files (2.1, 3.0, 4.0; for example a Google Contacts export).

## How it uses Proton Drive

The app uses the official Proton Drive SDK Kotlin binding `me.proton.drive:sdk` (Maven Central). The binding wraps the C# SDK, compiled to a native library with .NET NativeAOT. The SDK does all Drive cryptography. The app adds:

- the sign-in, the token refresh and the key unlock (`auth/`),
- the HTTP bridge to the SDK (ProtonCore `ApiProvider`, `drive/`),
- the contacts file logic (`core/`, `sync/`).

The app follows the SDK usage rules: it sends `x-pm-appversion: external-drive-cardvault@<version>-<channel>`, uses only the official API host, syncs through Drive events, and does not walk the folder tree.

## Layout

| Path | Content |
| --- | --- |
| `docs/spec.md` | File format, merge, sync and mirror contract for both platforms |
| `docs/test-vectors/` | vCard and merge vectors that both platforms must pass |
| `android/core` | Platform-neutral Kotlin: model, vCard codec, merge, diff, import rules |
| `android/app` | Android app |

## Build (Android)

Requirements: JDK 17 or later, Android SDK with platform 35. No .NET toolchain is necessary; the native SDK comes in the Maven artifact.

```
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

The APK is `android/app/build/outputs/apk/debug/app-debug.apk`.

## Limits

- The Kotlin and Swift bindings are "incubating" upstream. Their API can change between releases.
- Proton plans a new Drive cryptographic model for end of 2026 / early 2027. Clients on old SDK releases stop working after it. Upgrade `proton-drive-sdk` in `android/gradle/libs.versions.toml` when Proton publishes the migration release.
- The SDK may be used for personal, non-commercial projects only (see the SDK README).
- The Android binding depends on ProtonCore Android, which is GPLv3. Distribute builds of this app under GPLv3 terms.
- Contact photos are not synced. The parser drops embedded photos and reports the count.
- Field conflicts between the app and the phone Contacts app on the same device take the phone value.
