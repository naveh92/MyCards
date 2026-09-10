# Google Play store listing — MyGiftCards

Everything the Play Console asks for, with the answer already written out. Copy each field
across; nothing here needs inventing on the spot.

Assets in this folder:

| File | Play field | Requirement |
|---|---|---|
| `icon-512.png` | App icon | 512×512 PNG, no transparency |
| `feature-graphic-1024x500.png` | Feature graphic | 1024×500, no transparency — **hand-picked, not generated** |
| `screenshots/01…08` | Phone screenshots | 1080×1920, min 2, max 8 |
| `promo-video.mp4` | Promo video | upload to YouTube, paste the URL — see below |
| `alt-wallet.png`, `alt-detail.png` | *(spares)* | ready-framed swaps. Eight is Play's cap — see below |
| `top_bars/` | *(source art)* | the banner the screenshots are built on |
| `raw-captures/` | *(source art)* | the unframed device captures, kept so the set can be rebuilt |

**The art is generated, not hand-made.** A UI change costs one command, not an afternoon in
an image editor. Start a headless emulator (`-no-window`, so it cannot steal focus), install a
debug build, then:

```bash
node tools/seed-demo-db.js /tmp/seed.db           # the fixture the screens are shot against
tools/capture-shots.sh play/raw-captures          # drive the emulator, one still per screen
java tools/StoreShots.java play/top_bars play/raw-captures play/screenshots
tools/record-clips.sh   /tmp/clips                # the same screens, moving
java tools/VideoStage.java play/top_bars /tmp/stage play/icon-512.png
tools/make-video.sh     /tmp/stage /tmp/clips play/promo-video.mp4
```

Each file carries its judgement calls in its own header — read `tools/StoreShots.java` before
changing a crop anchor and `tools/capture-shots.sh` before changing the capture order. Both
are ports of, or built on, the `game-infra` skill's `references/store-listing-art.md`.

**The fixture is part of the art.** `tools/seed-demo-db.js` writes a database against the
newest exported Room schema (it reads the highest-numbered file in `app/schemas/`, so adding
a migration cannot leave it behind) with one card in each state -- active, used-up,
expired-with-money and archived -- and 11 purchases across three months, so the wallet shows its status badges and the Archive group, and the
history screen has more than one month heading. Its card *types* are chosen so that three
active cards all stock Castro — otherwise screenshot 1, the most-viewed image in the listing,
answers "which card works here?" with a single row. One card is also seeded already flagged by
the daily balance check, which is what screenshot 6 photographs.

**⚠️ The feature graphic is the one exception to "generated, not hand-made."** What is on the
listing is a hand-tuned variant — flatter field, different text placement — of what
`tools/IconGen.java` produces. The generator therefore writes to
`play/feature-graphic-generated.png` and **never** to the shipped filename; it used to write
over it, which would have replaced the live banner the next time anyone regenerated an icon.
Treat the generated file as a starting point and copy it across deliberately.

**The spares are swaps, not additions.** Play accepts at most eight. `alt-wallet.png` is the
wallet in English and light; `alt-detail.png` is one card in full. Drop either over whichever
numbered file it replaces -- both are already framed and captioned.

## Promo video

`promo-video.mp4` — **48.0s, 1920x1080, silent**. Play takes a YouTube URL rather than a file:
upload it as **public or unlisted**, leave **embedding on**, turn **ads off**, and paste the
clean `watch?v=` URL (no playlist or timestamp parameters).

Three things drove the shape of it:

- **Play requires 30–120s and autoplays only the first 30**, so the pitch is front-loaded: the
  two halves of the core idea are on screen by 0:14, and everything that has to land does so
  inside the autoplay window. Ten scenes. The search scene alone runs 8s, because a viewer
  has to read the query going in, watch the list narrow and then read the results -- at 5.5s
  the answer was on screen for about a second, which is not long enough to follow.
- **It autoplays muted, and most people never unmute.** Every claim is set as type on the
  stage, not spoken. That is also why there is no music: a silent track cannot arrive with a
  licensing problem attached.
- **The slot is 16:9.** A portrait film gets pillarboxed there and throws away two thirds of
  the frame, so the phone stands on a landscape stage with the copy beside it.

The AAB to upload is `app/build/outputs/bundle/release/app-release.aab`.

---

## Store listing — English (en-US)

**App name** (30 char limit)

```
MyGiftCards
```

The old title carried "Gift Card Wallet" as a keyword tail. The name now says "GiftCards"
itself, so repeating it reads as padding. If you want the keywords back for store search,
`MyGiftCards: Israeli Gift Cards` fits the 30-character limit exactly.

**Short description** (80 char limit — 76 used)

```
Find which of your gift cards works at the shop you are standing in right now.
```

**Full description** (4000 char limit)

```
You are at the checkout counter. Somewhere in your bag are four gift cards from work. One of them is
accepted here — but which? By the time you have opened three websites, the queue behind you
has grown, and you pay full price with your own credit card. Again. The gift cards sit in a
drawer until they expire.

MyGiftCards answers that question in about two seconds.

TYPE A SHOP, SEE WHICH CARD WORKS
Start typing the name of the shop you are standing in. MyGiftCards searches every merchant list
it knows about and shows you which of your cards is accepted there, how much is left on each
one, and when it expires. Each result names the merchant that matched, so you can tell at a
glance whether the app understood you.

BUILT FOR HOW ISRAELIS ACTUALLY TYPE
Search works in Hebrew and English, and it is deliberately forgiving:
• Partial words match — "cas" finds Castro
• Hebrew final letters are folded, so mid-word matches still work
• Niqqud and accents are ignored
• Typed on the wrong keyboard layout? "wsdrh" still finds the right shop
• Card names match too, so "buy-me", "buyme" and "Buy Me" all find the same card

EVERY CARD, SOONEST TO EXPIRE FIRST
Your cards are listed in the order you should spend them, with the closest expiry at the top
and a warning on anything about to lapse. Log a purchase and the balance drops. The point is
to spend the money before it evaporates.

SHOP LISTS THAT STAY CURRENT
MyGiftCards ships with merchant lists for BuyMe (All, Chef, Style, Together and more), All-inZone,
SuperZone, GiftZone, ChefZone, SpaZone and LOVE — over 1,200 shops on the widest card alone.
The lists refresh automatically in the background, so a card that gains a new chain does not
leave you guessing. The app works fully offline using the lists already on your phone.

YOUR CARD NUMBERS STAY ON YOUR PHONE
There is no account, no sign-in and no server holding your data. Everything lives on your
device. If you choose to save a payment card number and CVV, they are encrypted with a key
held in the Android Keystore and, on a phone with a screen lock, revealing them requires your
fingerprint, face or PIN — enforced by Android itself, not by the app.

BACKUP THAT SURVIVES A LOST PHONE
Because nothing is stored on a server, MyGiftCards lets you export an encrypted backup protected
by a passphrase only you know. Move it to a new phone and everything comes back. No cloud
account required, and no one else can read it.

HEBREW AND ENGLISH, LIGHT AND DARK
Full right-to-left support with a proper Hebrew interface, switchable in the app. The theme
follows your phone, or you can pin it to light or dark.

NO ADS, NO TRACKING, NO ANALYTICS
No advertising SDKs, no analytics, no crash reporting, no data collection of any kind.

MyGiftCards is an independent app. It is not affiliated with, endorsed by or operated by BuyMe,
HTZone, Castro, Max or any other gift-card issuer. Merchant lists are compiled from
information the issuers publish and may be incomplete or out of date — the app always shows
you how fresh a list is, so check with the shop before relying on it for a large purchase.
```

---

## Store listing — Hebrew (he-IL)

**App name**

```
MyGiftCards: כרטיסי המתנה שלי
```

**Short description**

```
גלו תוך שניות איזה מכרטיסי המתנה שלכם מתקבל בחנות שאתם עומדים בה עכשיו.
```

**Full description**

```
אתם בקופה. בתיק יש ארבעה כרטיסי מתנה מהעבודה. אחד מהם מתקבל כאן — אבל איזה? עד שפתחתם שלושה
אתרים, התור מאחוריכם התארך, ואתם משלמים מחיר מלא בכרטיס האשראי שלכם. שוב. וכרטיסי המתנה
נשארים במגירה עד שהתוקף שלהם פג.

MyGiftCards עונה על השאלה הזו בשתי שניות.

הקלידו שם של חנות, קבלו תשובה
התחילו להקליד את שם החנות שאתם נמצאים בה. האפליקציה סורקת את כל רשימות בתי העסק ומראה איזה
מהכרטיסים שלכם מתקבל שם, כמה נשאר בכל אחד ומתי הוא פג. כל תוצאה מציינת את בית העסק שהתאים,
כדי שתדעו מיד שהחיפוש הבין אתכם נכון.

חיפוש שמתאים לאיך שבאמת מקלידים
החיפוש עובד בעברית ובאנגלית, ובכוונה סלחני:
• התאמה חלקית — "קסט" ימצא את קסטרו
• אותיות סופיות מקופלות, כך שגם התאמה באמצע מילה עובדת
• ניקוד וסימנים דיאקריטיים לא מפריעים
• הקלדתם בפריסת מקלדת הפוכה? "wsdrh" עדיין ימצא את החנות הנכונה
• גם שמות הכרטיסים מתאימים, כך ש"ביימי", "buyme" ו-"buy me" מגיעים לאותו כרטיס

כל הכרטיסים, הקרוב לפוג ראשון
הכרטיסים מסודרים לפי הסדר שכדאי לנצל אותם — הקרוב ביותר לפוג בראש, עם אזהרה על כרטיס שעומד
לפוג. רושמים רכישה והיתרה מתעדכנת. כל המטרה היא לנצל את הכסף לפני שהוא נעלם.

רשימות חנויות שנשארות מעודכנות
האפליקציה כוללת רשימות בתי עסק עבור BuyMe (אול, שף, סטייל, טוגתר ועוד), All-inZone,
SuperZone, GiftZone, ChefZone, SpaZone ו-LOVE — יותר מ-1,200 בתי עסק בכרטיס הרחב ביותר.
הרשימות מתרעננות אוטומטית ברקע. האפליקציה עובדת גם ללא חיבור לאינטרנט.

מספרי הכרטיסים נשארים במכשיר שלכם
אין חשבון, אין הרשמה ואין שרת שמחזיק את המידע שלכם. הכול נשמר במכשיר. אם בחרתם לשמור מספר
כרטיס ו-CVV, הם מוצפנים במפתח שנשמר ב-Android Keystore, ובמכשיר עם נעילת מסך חשיפתם דורשת
טביעת אצבע, פנים או קוד — אכיפה של מערכת ההפעלה עצמה.

גיבוי ששורד החלפת מכשיר
מכיוון ששום דבר לא נשמר בשרת, אפשר לייצא גיבוי מוצפן המוגן בסיסמה שרק אתם יודעים. מעבירים
למכשיר חדש והכול חוזר. בלי חשבון ענן, ובלי שאף אחד אחר יוכל לקרוא אותו.

עברית ואנגלית, בהיר וכהה
תמיכה מלאה בימין-לשמאל עם ממשק עברי, ניתן להחלפה בתוך האפליקציה. ערכת הנושא עוקבת אחרי
המכשיר, או שאפשר לקבע אותה.

בלי פרסומות, בלי מעקב, בלי אנליטיקס
אין ערכות פרסום, אין אנליטיקס, אין דיווח קריסות ואין איסוף מידע מכל סוג.

MyGiftCards היא אפליקציה עצמאית. אין לה קשר, שיוך או חסות מטעם BuyMe, HTZone, קסטרו, מקס או כל
מנפיק אחר של כרטיסי מתנה. רשימות בתי העסק מבוססות על מידע שהמנפיקים מפרסמים ועשויות להיות
חלקיות או לא מעודכנות — האפליקציה תמיד מציגה עד כמה הרשימה עדכנית, אז כדאי לוודא מול החנות
לפני רכישה גדולה.
```

---

## Store settings

| Field | Answer |
|---|---|
| App or game | App |
| Category | Finance *(alternative: Shopping — see note below)* |
| Tags | Gift cards, Personal finance, Shopping |
| Contact email | navehohana@gmail.com |
| Website | https://naveh92.github.io/MyCards |
| Privacy policy URL | **https://naveh92.github.io/MyCards/privacy.html** |
| Contains ads | No |
| In-app purchases | No |
| Default language | English (United States), with Hebrew added |

**On the category.** Finance matches what the app is for, but it draws a stricter review and
Play asks a battery of financial-features questions. Shopping is defensible — the app helps
you spend gift cards at shops — and attracts less scrutiny. Finance is the more honest label;
pick it unless the review stalls.

## App content declarations

| Question | Answer |
|---|---|
| Privacy policy | The URL above |
| App access | All functionality is available without restrictions. No login, no special access. |
| Ads | No ads |
| Content rating | See questionnaire below |
| Target audience | 18+ (the app is about spending money; there is no reason to target minors) |
| News app | No |
| COVID-19 apps | No |
| Data safety | See below |
| Government app | No |
| Financial features | **No financial features.** The app does not process payments, lend money, trade securities or handle cryptocurrency. It is a personal reference tool that displays details you typed in yourself. |

## Content rating questionnaire

Category: **Utility, Productivity, Communication or Other**

Every content question is **No**: no violence, no sexual content, no profanity, no controlled
substances, no gambling or simulated gambling, no user-generated content, no user interaction,
no location sharing, no personal information shared with third parties.

One question needs care:

> *Does the app allow users to purchase digital or physical goods?*

**No.** The app opens a gift-card link in the browser if you saved one, but no purchase
happens inside the app and there is no in-app payment flow.

Expected outcome: rated for everyone (PEGI 3 / ESRB Everyone / IARC equivalent).

## Data safety form

The honest answer is that this app collects nothing. Fill it in as:

**Does your app collect or share any of the required user data types?** → **No**

That single answer resolves the whole section, and it is accurate: no data leaves the device
for the developer or any third party. Be ready to justify it, because the app clearly *holds*
sensitive data — the distinction Play draws is between **collected** (transmitted off the
device) and **stored locally**, and only the former is declarable.

If a reviewer questions it, the supporting facts are:

- No backend exists. There is no server, no account system and no developer-controlled
  endpoint anywhere in the app.
- The only outbound requests are anonymous HTTPS GETs for public JSON files on GitHub Pages,
  and — only when the user saves a gift-card link — a request to that issuer's own site.
- Card numbers and CVVs are encrypted with an Android Keystore key and never transmitted.
- Backups are user-initiated, user-encrypted and written to a location the user picks.

**Security practices to tick:**

- Data is encrypted in transit — Yes (all requests are HTTPS)
- Users can request data deletion — the app has no account; uninstalling deletes everything
- Committed to the Play Families Policy — No (not a children's app)
- Independent security review — No

---

## Known review risks, stated plainly

**1. The app stores full card numbers and CVVs.** This is the one thing likely to attract a
reviewer's attention. It is legitimate — the values are the user's own, entered by hand, held
encrypted on their own device and never transmitted — but "stores CVV" reads badly out of
context, because PCI-DSS forbids *merchants* from retaining CVVs. That rule governs businesses
processing card payments, not a personal wallet holding your own card, and MyGiftCards processes
no payments at all.

If a rejection cites it, the fallback is to drop CVV storage: it is the field with the least
practical value, since most gift cards need only the number.

**2. Merchant lists are scraped, not licensed.** The lists come from data the issuers publish
openly. The listing states plainly that the app is unaffiliated and the lists may be
incomplete. An issuer could still object.

**3. ~~Two card types ship without a merchant list.~~ No longer true.** Partial Max lists
landed in `85cdf83`, so all **32** card types now carry one: `max_gift` has 84 shops and
`max_super_gift` 9. Counted from `docs/stores/`, not remembered — the widest single card is
`buyme_all` at **1,303**, and the union across all 32 lists is **2,179 unique shop names**,
which is where the screenshots' "2,100+ shops" comes from. Recount before quoting a number in
the listing; an inflated figure is the one kind of copy a reviewer can trivially check.
