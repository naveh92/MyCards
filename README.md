# MyGiftCards

An app to organize all gift-cards in one place.

An Android app for Israeli holiday gift cards, built around one question:

> **I'm at the till in this shop — which of my cards works here, and how much is on it?**

Type part of a store or card name and the matching cards appear, each stating *why* it
matched and how much is left.

---

## Running it

Open the project in Android Studio and press Run, or from a terminal:

```bash
./gradlew :app:assembleDebug && ./gradlew :app:installDebug
```

Requires an Android SDK with `platforms;android-35`. If Gradle tries and fails to download
build-tools (common behind a TLS-inspecting corporate proxy), `buildToolsVersion` in
[app/build.gradle](app/build.gradle) is pinned to a locally installed version — change it to
whatever `$ANDROID_HOME/build-tools` actually contains.

Run the tests with:

```bash
./gradlew :app:testDebugUnitTest
```

---

## How search works

This is the heart of the app, and the part most worth understanding.

**Normalization** ([SearchNormalizer](app/src/main/java/com/mycards/search/SearchNormalizer.java))
reduces every name and query to lowercase alphanumerics, strips Latin accents and Hebrew
niqqud, and folds Hebrew final letters (ך→כ, ם→מ …). So `"Buy Me All"`, `"BUY-ME-ALL"` and
`"buyme"` all collapse to forms where the shorter is a substring of the longer.

**Wrong-keyboard-layout matching**
([HebrewKeyboardMapper](app/src/main/java/com/mycards/search/HebrewKeyboardMapper.java))
handles the very common case of typing with the layout in the wrong language — `אדידס` typed
on an English keyboard comes out as `tshsx`, and still finds adidas. The layout table was
verified against BuyMe's own alias data, which ships exactly these manglings.

**Matching is infix**, not token-based: `"za"` has to find a store with `za` in the middle of
its name. That rules out an inverted index — no token map answers arbitrary mid-word
fragments — so names and aliases are normalized once at index-build time and scanned
directly. Ranking is exact > prefix > substring, with a card-name hit outranking a merchant
hit, so typing `buyme` means "my BuyMe card" rather than "every card covering a shop called
BuyMe".

Results are sorted by relevance, then **soonest-to-expire**, which nudges dying cards to get
spent first.

---

## Where store lists come from

There is **no public API** for any Israeli gift-card issuer. What exists:

**24 card types** ship in the catalog. 21 were discovered by probing every supplier id in
BuyMe's sitemap against `siteapi/brands/{id}` — a plain merchant returns ~400 bytes, a
multi-brand card returns megabytes, which makes them trivial to tell apart.

| Card | Merchants | Card | Merchants |
|---|---|---|---|
| BuyMe All | 1276 | BuyMe Home Design | 121 |
| BuyMe Baby | 948 | BuyMe Ramat-Gan | 109 |
| BuyMe Local | 709 | **BuyMe Chef** | 84 |
| BuyMe Yng | 403 | עוטפים עסקים | 83 |
| BuyMe Together | 390 | BuyMe Brunch | 61 |
| BuyMe Foody | 368 | BuyMe Cheers | 48 |
| BuyMe Fashion & Beauty | 303 | BuyMe Kosher | 42 |
| BuyMe Style | 230 | BuyMe Pets | 31 |
| BuyMe Vacation & Spa | 175 | BuyMe Live | 20 |
| BuyMe Wellness | 166 | **All-inZone** | **747** |
| Azrieli | 144 | **SuperZone** | **752** |
| BuyMe Rishon | 125 | **GiftZone** | **737** |
| | | **ChefZone** | **79** |
| | | **SpaZone** | **22** |
| | | **Love Gift Card** | **8** |
| | | Max Gift Card | *none found* |

**HTZone (the Zone family).** No API, but each `voucher-zone/{id}` page embeds its whole
merchant list as a `business_arr` literal in the markup — names, English names, addresses,
categories, regions and an online-redemption flag. [tools/HtZoneGen.java](tools/HtZoneGen.java)
reads it; ids are mapped in [tools/htzone-pages.txt](tools/htzone-pages.txt) by following each
product page to the list it links to. The same shop appears once per filter it matches, so
rows are deduplicated by id and their category/region labels merged into aliases — which is
why searching "אופנה" or "מרכז" also works. Five more ids carry large lists but could not be
attributed to a named voucher with confidence, so they were left out rather than guessed at.

**Love Gift Card.** Issued by Castro Model Ltd and accepted only at that group's own brands,
so it is a closed list of 8 rather than a shifting network. Transcribed from the issuer's own
published terms (`תקנון LOVE CARD`), with the three online-redeemable brands flagged.

**Max.** Still nothing. Its merchant list exists only as logo *images* in a PDF brochure and
inside the MyGift app; the web store page redirects to the homepage, and the
`onlinelcapi.max.co.il/api/giftcard/stores` endpoint answers `405` to GET and `500` to every
POST shape tried. The PDF's extractable text is only fine-print exceptions, which would name
just those brands that *have* exceptions — a partial list presented as complete is exactly
the failure this app exists to prevent, so Max ships with no store list.

Only the four largest carry a `bundled_asset` (775 KB total). The rest fetch on first use,
which keeps the APK small — the app only ever fetches merchant lists for card types you
actually hold.

Regenerate the catalog with `tools/CatalogGen.java`; it reads the live payloads and emits
both the catalog entries and the snapshots, so names and counts are never hand-typed.

The BuyMe endpoint returns a card's whole merchant list in one call, and each brand carries a
curated `searchTerms` alias array — Hebrew, English, transliterations and keyboard-layout
manglings. That array is why search feels forgiving; it is hand-maintained data we get for
free. Note the site sits behind Cloudflare and rejects a default HTTP client, so requests
carry a browser `User-Agent` ([Http.java](app/src/main/java/com/mycards/data/source/Http.java)).

**All-inZone, Max and Love ship with no store list on purpose.** Inventing merchants would be
worse than showing none: a card that claims to be accepted somewhere it isn't fails you at
exactly the moment this app exists to help. They still track balance and expiry, and the UI
says plainly that no list is available.

### The fallback chain

Each card type declares an ordered list of sources in
[assets/catalog.json](app/src/main/assets/catalog.json). The first that yields data wins:

```
static_list (your GitHub Pages copy)  →  bundled_asset (in the APK)  →  buyme_brands (issuer)
```

The order is deliberate. If every install went to BuyMe first, a few hundred users
refreshing weekly would each pull ~5.8 MB directly from a third party that never agreed to
serve them. Instead one CI run per week fetches from BuyMe, and every phone reads the
published copy. The issuer endpoint stays last purely as a safety net.

Rules that matter, all covered by tests:

- A source that responds but returns **zero** stores counts as a failure — that almost always
  means the format moved, not that the card covers no shops.
- A source that returns a **suspiciously small** list (under half the count the manifest or
  the existing cache expects) is also rejected and the chain continues. Parsing cleanly is
  not the same as being correct: a half-finished publish or a truncated download yields a
  perfectly valid file with a handful of merchants, which would otherwise overwrite good
  data. But if *every* source comes back small, the largest is accepted anyway — when they
  all agree the list shrank, it shrank, and refusing them all would freeze that card on
  stale data forever.
- An **unknown** source type is skipped, not fatal, so a catalog published later can introduce
  a new source type and older installs simply fall through.
- If **every** source fails, the previous cache is left untouched. Stale merchants beat none
  when there's a queue behind you, and the card detail screen always shows the list's age.

### Publishing: CI writes, the app only reads

[.github/workflows/refresh-store-lists.yml](.github/workflows/refresh-store-lists.yml) runs
weekly, regenerates every list with [tools/CatalogGen.java](tools/CatalogGen.java), writes a
manifest, and commits to `docs/` for GitHub Pages. It only commits when a list actually
changed, so an uneventful week produces no commit and no client downloads.

**The publisher is CI, not a special build of the app.** A "moderator APK" that could push
updates would have to carry a GitHub token, and an APK is trivially decompiled — that token
would only need to leak once. Keeping publishing in CI means there is exactly one APK, it
contains no credential, and it has no write code path that could be abused. It also runs
when your phone is off.

Set `CATALOG_BASE_URL` in
[RemoteConfig.java](app/src/main/java/com/mycards/data/RemoteConfig.java) to your Pages URL.
Until you do, remote fetches fail and the bundled snapshots are used — a fully working
offline state.

Published layout:

```
docs/
  catalog.json
  manifest.json          # sha256 + size + merchant count per list
  stores/
    buyme_all.json       # {"stores":[{"n":"Castro","a":["קסטרו"],"o":false}, ...]}
```

**The manifest is what makes weekly sync cheap.** The app fetches it first (~3 KB for all 21
lists) and compares hashes against what it already has. Unchanged lists are skipped without
being downloaded, so a typical sync transfers a few kilobytes instead of ~2 MB. A hash is
recorded *only* when the data genuinely came from the published list — tagging an issuer or
bundled fetch with it would wrongly suppress the next sync and strand the phone on stale
data. If the manifest is unreachable the sync still works, just without the shortcut.

Run [tools/check-sources.sh](tools/check-sources.sh) periodically — CI runs it before every
refresh. It's the canary that tells you the undocumented endpoint changed shape, rather than
finding out at a checkout counter.

### What happens when a site changes

These sources are scraped, not licensed, so they *will* break eventually. The design assumes
that rather than hoping otherwise:

| | Handled how |
|---|---|
| Endpoint down, 403, timeout | Chain falls through to the published copy, then the bundled snapshot |
| Format changed → zero results | Treated as failure, chain continues, existing cache untouched |
| Format changed → truncated results | Rejected below half the expected count, chain continues |
| You need to know | CI runs the canary before every refresh and fails loudly |
| Endpoint moved / renamed | Catalog edit — no app release |
| Different JSON shape | Catalog edit — `generic_json` / `embedded_json` paths are config |
| Data moved into the page HTML | Catalog edit — `embedded_json` with a `varName` |
| Site drops JSON entirely for HTML | **Needs code**: a new provider |

Only the last row requires a developer. Everything above it is either automatic or a change
to a JSON file you host. And because the fallback chain ends at a snapshot compiled into the
APK, even total breakage degrades to *stale but working* — never an empty screen at a till.

The honest limit: nothing here rewrites a parser by itself. If BuyMe abandons JSON for
server-rendered HTML, someone has to write that provider. What the design buys you is time —
users keep working from cached and bundled data while that happens, and CI tells you within
a week rather than a customer telling you at a checkout counter.

---

## Security model

| Data | Key | Protection |
|---|---|---|
| Card number, CVV, card expiry | Keystore key with `setUserAuthenticationRequired(true)` | Decryption is **refused by the OS** without a recent unlock |
| Gift-card link | Keystore key, not auth-bound | Encrypted at rest; UI-gated |
| Balance, expiry, spend log | Plain columns | Not sensitive |

The biometric gate is enforced by the Keystore, not by the UI — an activity that forgot to
show a prompt gets an exception, not a leaked card number. Revealed numbers auto-hide after
60 seconds and are cleared from the view hierarchy on `onPause`.

The gift link deliberately uses the **non**-auth-bound key: the background balance check runs
unattended and cannot satisfy a biometric prompt. That is a conscious trade — the link is
encrypted at rest but is not behind the OS gate.

If the device has no secure lock screen, an auth-bound key cannot exist at all. The app falls
back to an unbound key and *says so* on the add-card screen rather than implying a protection
that isn't there.

`allowBackup` is **off**. Android's auto-backup would upload the encrypted blobs while the
Keystore key stayed on the device, restoring card numbers that can never be decrypted —
silent corruption.

### Backup and restore

Settings → **Export encrypted backup** writes a passphrase-encrypted file you can keep
anywhere. The format is `AES-256-GCM` with the key derived by `PBKDF2WithHmacSHA256`
(210,000 rounds), salt and IV fresh per export and stored in the header, which is itself
authenticated as AAD so it cannot be edited to weaken the file.

The passphrase exists because the Keystore key **cannot leave the device it was created
on**. Card numbers are unwrapped from it and immediately rewrapped under something you know;
on import they are rewrapped again under the new phone's own Keystore key.

Import **merges**, it does not replace. Rows are matched on `uuid` — row ids are only unique
within one database, so restoring by id onto a second phone would collide unrelated cards.
A card already present and newer than the backup is left alone, because overwriting it would
discard everything logged since the backup was taken. Spends are immutable once written, so
a known `uuid` is skipped; one whose card is missing is dropped rather than silently
attached to the wrong card's balance.

Ten tests cover the format, including wrong passphrases, tampered ciphertext, a tampered
header, a non-backup file, and a hostile iteration count that would otherwise hang the app.

---

## Balances

Balances are always **derived** (`initialAmount - Σ spends`), never stored as a running total
that could drift when an entry is edited.

Where a card has a public, unauthenticated gift link, a daily worker compares the issuer's
reported balance against the log. On a shortfall it raises **"Detected an unlogged
transaction"**, opening a screen with the difference pre-filled — but never records anything
automatically, because only you know what it was spent on.

[GiftPageBalanceProvider](app/src/main/java/com/mycards/data/source/providers/GiftPageBalanceProvider.java)
reports a balance **only** when the page contains exactly one plausible amount near balance
wording. Any ambiguity returns null. A wrong number here would raise a false alert and push
you to record a purchase that never happened, so silence is the correct failure mode.

---

## Layout

```
search/          Normalizer, keyboard mapper, index, engine — pure Java, no Android imports
data/catalog/    Catalog model
data/source/     Provider interface, registry, fallback chain, parsers
data/db/         Room entities and DAOs (local SQLite — nothing leaves the device)
data/crypto/     Keystore vault
sync/            WorkManager: weekly catalog refresh, daily balance check
ui/              Search, detail, add/edit, reconcile, settings
```

The `search` package has no Android dependencies at all, which is what lets the matching
rules be tested on a plain JVM.

---

## Known gaps

- No store lists for All-inZone, Max or Love (see above).
- Auto balance-fetch only works for issuers whose gift link is public and unauthenticated;
  the manual log remains the source of truth.
- The BuyMe endpoint is undocumented and unsupported. It can change without notice — the
  fallback chain limits the damage to stale data, not an empty app.
- No cross-device sync yet.
