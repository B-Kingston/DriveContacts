# DriveContacts

**Unofficial.** This is a third-party application not officially supported by Proton. It is not made or endorsed by Proton AG.

DriveContacts is a contacts app for Android. It keeps all contacts in one end-to-end encrypted vCard file in your Proton Drive (`My files/DriveContacts/contacts.vcf`).

## Features

- Sign-in on the Proton web page. The app never sees your password.
- Sync through Drive events. Each change uploads a new revision of the file.
- Three-way merge per field. Conflicts show "This device" and "Proton Drive" side by side.
- Optional two-way mirror into the phone Contacts app.
- Import from phone accounts and from vCard files (2.1, 3.0, 4.0).

## Install

Download the APK from [Releases](https://github.com/B-Kingston/DriveContacts/releases). Android 10 or later.

## Build

JDK 17 or later, Android SDK with platform 35.

```
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

A signed release build needs `drivecontacts.storeFile`, `drivecontacts.storePassword`, `drivecontacts.keyAlias` and `drivecontacts.keyPassword` as Gradle properties (or `DRIVECONTACTS_STORE_FILE` and so on as environment variables). Then run `./gradlew :app:assembleRelease`.

`docs/spec.md` defines the file format, the merge and the sync. `docs/test-vectors/` holds the test cases for it.

## Limits

- The app uses the Proton Drive SDK (`me.proton.drive:sdk`). The SDK allows personal, non-commercial use only. Its Kotlin binding is incubating and can change.
- Proton plans a new Drive cryptographic model for end of 2026 / early 2027. Old SDK versions stop working after that.
- Contact photos are not synced.
- When the app and the phone Contacts app change the same field, the phone value wins.

## License

GPLv3. See `LICENSE`.
