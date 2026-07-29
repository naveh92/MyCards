# Play Console upload reference — MyCards

Every technical value the Console asks for, read off the built artifact rather than typed
from memory. Listing prose (descriptions, form answers, review risks) is in
[STORE-LISTING.md](STORE-LISTING.md).

Generated against the build of 29 July 2026.

---

## Before you can publish at all

Three things catch first-time publishers, in rough order of how much time they cost:

1. **Closed testing requirement.** A *personal* Play developer account created after
   13 November 2023 must run a closed test with **at least 12 testers opted in
   continuously for 14 days** before it can apply for production access. This is a
   calendar constraint, not a paperwork one — start the closed test as soon as the account
   exists. Organisation accounts (registered as a company, with a D-U-N-S number) are
   exempt. If you have a company you could register under, that decision is worth making
   *before* creating the account, because the account type cannot be changed afterwards.
2. **$25 one-time registration fee**, and identity verification — a government ID and
   address for a personal account.
3. **The package name is permanent.** `io.github.naveh92.mycards` can never be changed once
   anything is published under it, and the name can never be reused, even by you. Until that
   first publish it is a one-line edit in `app/build.gradle`, so change it now if you would
   rather have something else.

## Build identity

| Field | Value |
|---|---|
| **Package name / Application ID** | `io.github.naveh92.mycards` |
| Java package / `namespace` | `com.mycards` — a compile-time concern, deliberately left alone |
| Launcher activity | `com.mycards.ui.search.SearchActivity` |
| Version code | `1` |
| Version name | `1.0` |
| Min SDK | 26 — Android 8.0 Oreo |
| Target SDK | 36 — Android 16 |
| Compile SDK | 36 |
| App label (default) | MyCards |
| App label (Hebrew) | הכרטיסים שלי |
| Locales in the bundle | default (English) + `iw` (Hebrew) |
| Screen sizes | small, normal, large, xlarge |
| Native code | none — one universal bundle, no ABI splits |

## Artifacts

| What | Path | Size |
|---|---|---|
| **Upload this** | `app/build/outputs/bundle/release/app-release.aab` | 3,534,613 bytes |
| Sideload / manual testing | `app/build/outputs/apk/release/app-release.apk` | 2.1 MB |
| R8 mapping — **nothing to do** | `app/build/outputs/mapping/release/mapping.txt` | 16 MB |

The bundle's sha256 is **not** pinned here: an AAB embeds build timestamps, so it differs on
every rebuild even with identical sources, and a recorded hash would be stale immediately.
To check that the file you upload is the one you just built:

```
sha256sum app/build/outputs/bundle/release/app-release.aab
```

**The mapping needs no separate upload.** AGP embeds it in the bundle itself, at
`BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`, and Play extracts it on
upload — so crash reports are de-obfuscated automatically. Confirm it is there with:

```
unzip -l app/build/outputs/bundle/release/app-release.aab | grep proguard.map
```

The separate *"upload deobfuscation file"* control in the Console's App bundle explorer
exists for APK-based releases, and as a way to replace a mapping after the fact. It is not
part of the AAB flow.

The copy left in `app/build/outputs/mapping/release/` is still worth keeping for the release
you ship: it lets you decode a stack trace locally, with R8's `retrace`, without going
through the Console.

To rebuild from clean:

```
./gradlew clean test bundleRelease
```

If that fails with *"JAVA_HOME is not set and no 'java' command could be found in your
PATH"*, the environment has no JDK on it. Android Studio ships one, so nothing needs
installing — point at it once, in an ordinary (non-admin) PowerShell:

```
setx JAVA_HOME "C:\Program Files\Android\Android Studio\jbr"
```

Then open a **new** terminal: `setx` writes the variable for future sessions and does not
alter the one it runs in. Setting `org.gradle.java.home` in a `gradle.properties` does *not*
solve this — that only chooses the JDK the Gradle daemon compiles with, and the `gradlew`
launcher already needs a JVM to start Gradle and read that file.

## Signing

Play App Signing is on by default for new apps: Google holds the *app signing key* and you
hold the *upload key*. The values below are the upload key.

| Field | Value |
|---|---|
| Keystore | `C:\Users\naveh\keystores\mycards-upload.jks` (PKCS12) |
| Alias | `naveh` |
| Algorithm | 4096-bit RSA, SHA384withRSA |
| Valid until | 14 December 2053 |
| Certificate DN | `CN=Naveh, OU=MyCards, O=MyCards, L=Tel Aviv, C=IL` |
| SHA-1 | `51:10:51:E1:E9:6B:8F:B2:A6:4E:6A:60:DF:96:07:A6:3C:D2:80:67` |
| SHA-256 | `D0:F0:9E:8A:4C:3C:5A:C4:1C:68:C4:5E:00:96:2F:F7:68:C1:CF:DA:46:69:AB:2A:2E:2D:A7:09:B1:35:B6:F0` |

**Back the keystore up off this machine.** Losing it is recoverable — Play can reset an
upload key — but only through a support request that takes days. The passwords live in
`keystore.properties`, which is git-ignored and exists only locally; the keystore itself is
outside the repo entirely, so neither can be committed by accident.

## Permissions Play will display

Four are declared by the app. Five more are merged in from AndroidX libraries and cannot be
removed without dropping the feature that pulls them.

| Permission | Source | Why |
|---|---|---|
| `INTERNET` | app | Downloading merchant lists |
| `ACCESS_NETWORK_STATE` | app | Waiting for a connection before syncing |
| `USE_BIOMETRIC` | app | Confirming identity before revealing a card number |
| `POST_NOTIFICATIONS` | app | The single unlogged-transaction alert |
| `USE_FINGERPRINT` | `androidx.biometric:1.1.0` | Legacy fallback below API 28 |
| `WAKE_LOCK` | `androidx.work:2.9.1` | Holding the CPU during a background sync |
| `RECEIVE_BOOT_COMPLETED` | `androidx.work:2.9.1` | Restoring scheduled work after a reboot |
| `FOREGROUND_SERVICE` | `androidx.work:2.9.1` | WorkManager's expedited-work fallback below API 31 |
| `io.github.naveh92.mycards.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | `androidx.work:2.9.1` | Internal, self-scoped; prefixed with the application ID |

**No `foregroundServiceType` is declared anywhere in the merged manifest**, so the Console's
foreground-service declaration form does not apply. If it is shown anyway, the honest answer
is that the app starts no foreground service of its own; the permission arrives with
WorkManager.

## Assets — all verified against Play's limits

| File | Dimensions | Play requirement | Status |
|---|---|---|---|
| `play/icon-512.png` | 512×512 | exactly 512×512, 32-bit PNG, no alpha | ok |
| `play/feature-graphic-1024x500.png` | 1024×500 | exactly 1024×500 | ok |
| `play/screenshots/01-search.png` | 1080×1920 | 320–3840px per side, long side ≤ 2× short | ok |
| `play/screenshots/02-list.png` | 1080×1920 | " | ok |
| `play/screenshots/03-detail.png` | 1080×1920 | " | ok |
| `play/screenshots/04-hebrew.png` | 1080×1920 | " | ok |
| `play/screenshots/05-light.png` | 1080×1920 | " | ok |

Raw device captures are 1080×2400, which is 2.22:1 and **would be rejected**. These are
composited onto a 16:9 canvas by `tools/ShotFramer.java`; nothing is cropped.

Minimum 2 phone screenshots, maximum 8. Upload them in the numbered order — Play shows the
first few most prominently, and `01-search` is the one that explains the app.

## Short fields, ready to paste

| Field | English | Hebrew |
|---|---|---|
| App name (≤30) | `MyCards: Gift Card Wallet` | `MyCards: הכרטיסים שלי` |
| Short description (≤80) | `Find which of your gift cards works at the shop you are standing in right now.` | `גלו תוך שניות איזה מכרטיסי המתנה שלכם מתקבל בחנות שאתם עומדים בה עכשיו.` |

Full descriptions (≤4000) are in [STORE-LISTING.md](STORE-LISTING.md).

## URLs and contact

| Field | Value |
|---|---|
| **Privacy policy** | `https://naveh92.github.io/MyCards/privacy.html` |
| Website | `https://naveh92.github.io/MyCards` |
| Contact email | `navehohana@gmail.com` |
| Source | `https://github.com/naveh92/MyCards` |

The privacy policy URL is served from `docs/` on GitHub Pages. The weekly refresh workflow
excludes `privacy.html` from its `rsync --delete`, so a store-list refresh cannot remove it.
Play rechecks that URL periodically and a 404 puts the listing at risk.

## Suggested first release

- **Track:** closed testing (also the 12-tester requirement above), then production.
- **Countries:** Israel only to begin with. The merchant lists are Israeli and the app is
  useless elsewhere; a narrow launch also keeps the first reviews relevant.
- **Rollout:** 100% — with no server and no backend to overload, a staged rollout buys
  nothing at this scale.
- **Release name:** `1.0 (1)`.
- **Release notes:** first release.

For every subsequent upload, `versionCode` must increase. Play rejects a bundle whose
version code has already been used, even in a different track.
