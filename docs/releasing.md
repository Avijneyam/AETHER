# Publishing a release

The way PODIUM's are published.

## The key

A PKCS12 keystore (RSA 4096, alias `aether`, valid 10,000 days) lives outside the repo, in
`%USERPROFILE%\.aether-release\`, with its passwords in `keystore.properties` beside it. A copy of that
file sits in the project root, which git ignores. `app/build.gradle.kts` reads it, or the
`AETHER_KEYSTORE_FILE`, `AETHER_KEYSTORE_PASSWORD`, `AETHER_KEY_ALIAS` and `AETHER_KEY_PASSWORD`
environment variables. With neither, the release APK is built unsigned.

**Back the keystore up** (a password manager and an offline copy). Every release must be signed with
it: an APK signed with a different key can't install over an existing AETHER.

## Steps

1. Raise `versionName`, and `versionCode` as `major * 1,000,000 + minor * 1,000 + patch`.
2. Add the release to `CHANGELOG.md`, and write its notes in `docs/releases/v<versionName>.md`.
3. `./gradlew :app:assembleRelease`, then check it: `apksigner verify --print-certs` names `CN=AETHER`.
   Release builds aren't shrunk, so they run the same code as the debug build tested on the phone. To
   try one beside the phone's own without an uninstall, add `-Paether.applicationIdSuffix=.rc`.
4. Publish a GitHub Release tagged `v<versionName>`, with the APK attached and the notes from step 2
   followed by the APK's size and SHA-256. The README's download link names the file, so update it if
   the file's name changes. Never commit APKs.
