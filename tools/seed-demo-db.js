// Vendored into tools/ because tools/capture-shots.sh depends on it: a fixture that
// lives only in a session scratch directory makes the documented rebuild command a lie.
// Card types were tuned for the listing -- three ACTIVE cards that all stock Castro, so
// screenshot 1 shows a plural answer rather than a single row, while keeping one card in
// each of USED_UP / EXPIRED / ARCHIVED for the wallet badges and the Archive group.
//
// Builds a schema-v2 mycards.db covering every card state and a spread of purchases,
// so the new screens can be photographed with realistic content instead of one card.
//
// The schema and Room's identity hash are read from the exported 2.json rather than typed
// out here: if the entities change, this follows them, and a hand-copied hash would make
// Room reject the file with "cannot verify the data integrity" for no visible reason.
const fs = require('fs');
const path = require('path');
const { DatabaseSync } = require('node:sqlite');

// Derived from this file's own location rather than hardcoded, so the script works from any
// checkout and any working directory.
const REPO = path.resolve(__dirname, '..');
const SCHEMA = path.join(REPO, 'app/schemas/com.mycards.data.db.AppDatabase/2.json');

// ⚠️ 2.json IS AN EXPORTED ROOM SCHEMA, AND IT ONLY EXISTS ONCE THE v2 MIGRATION IS IN THE
// TREE. Against a checkout that predates it this fails with a bare ENOENT stack trace that
// says nothing about why -- so say it here instead.
if (!fs.existsSync(SCHEMA)) {
    console.error(
        'no exported schema at ' + SCHEMA + '\n' +
        'This fixture is schema v2. Build the app once on a tree that has the v2 migration\n' +
        '(./gradlew :app:assembleDebug) so Room exports the schema, then re-run.');
    process.exit(1);
}
const schema = JSON.parse(fs.readFileSync(SCHEMA, 'utf8'));

const out = process.argv[2];
if (!out) {
    console.error('usage: node tools/seed-demo-db.js <out.db>');
    process.exit(2);
}
if (fs.existsSync(out)) fs.unlinkSync(out);
const db = new DatabaseSync(out);

// Room's own bookkeeping: version in the file header, identity hash in a table it checks
// on every open.
db.exec(`PRAGMA user_version = ${schema.database.version}`);
for (const e of schema.database.entities) {
  db.exec(e.createSql.replace('${TABLE_NAME}', e.tableName));
  for (const idx of (e.indices || [])) db.exec(idx.createSql.replace('${TABLE_NAME}', e.tableName));
}
db.exec('CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)');
db.prepare('INSERT OR REPLACE INTO room_master_table VALUES (42, ?)')
  .run(schema.database.identityHash);

const DAY = 86400000;
const now = Date.now();
const ago = (d) => now - d * DAY;

// Expiries are relative to today so the fixture never rots into a different set of states.
const month = (offset) => {
  const d = new Date();
  d.setMonth(d.getMonth() + offset);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
};

const cards = [
  // id, uuid, type,               label,              expiry,      initial, archivedAt
  [1, 'c1', 'buyme_all',           'Holiday gift 2026', month(7),   500, 0],
  [2, 'buyme_style',               null,                null,       null, null, null], // placeholder, replaced below
];

// Written out plainly: each row is a state the wallet has to render.
const rows = [
  { id: 1, type: 'buyme_all',            label: 'Holiday gift 2026', expiry: month(7),   initial: 700, archived: 0 },
  { id: 2, type: 'buyme_style',          label: null,                expiry: month(14),  initial: 200, archived: 0 },
  { id: 3, type: 'buyme_foody',          label: 'Anniversary meal',  expiry: month(-4),  initial: 300, archived: 0 },
  { id: 4, type: 'buyme_fashion_beauty', label: 'Old birthday card', expiry: month(20),  initial: 250, archived: ago(30) },
  { id: 5, type: 'all_in_zone',          label: 'Dinner voucher',    expiry: month(1),   initial: 180, archived: 0 },
  { id: 6, type: 'love_gift_card',       label: 'Small change',      expiry: null,       initial: 260, archived: 0 },
];

const insertCard = db.prepare(`INSERT INTO cards
  (id, uuid, updatedAt, cardTypeId, label, expiryDate, initialAmount, currency,
   enc_pan, enc_cvv, enc_card_expiry, enc_gift_url, notes, createdAt,
   lastBalanceCheckAt, lastFetchedBalance, hasUnreconciledMismatch, archivedAt)
  VALUES (?,?,?,?,?,?,?,'ILS',NULL,NULL,NULL,NULL,NULL,?,0,NULL,0,?)`);

for (const c of rows) {
  insertCard.run(c.id, 'card-' + c.id, now, c.type, c.label, c.expiry,
      c.initial, ago(200), c.archived);
}

// Purchases spread over several months so the history screen has more than one heading,
// and concentrated enough that some months hold several.
const spends = [
  // cardId, title,            amount, store,        daysAgo, source
  [1, 'Running shoes',           220, 'Castro',            3,  'MANUAL'],
  [1, 'Coffee and cake',          48, 'Cafe Cezar',        9,  'MANUAL'],
  [1, 'Birthday present',        180, 'Fox Home',         38,  'MANUAL'],
  [2, 'Winter coat',             200, 'Castro',           64,  'MANUAL'],
  [3, 'Anniversary dinner',      120, 'Messa',           130,  'MANUAL'],
  [4, 'Perfume',                 150, 'Sephora',          75,  'MANUAL'],
  [4, 'Face cream',               60, null,               96,  'MANUAL'],
  [5, 'Chef tasting menu',       120, 'Herbert Samuel',   12,  'MANUAL'],
  [5, 'Unlogged transaction',     15, null,                5,  'RECONCILIATION'],
  [6, 'Bed linen',                90, 'Fox Home',         21,  'MANUAL'],
  [6, 'Kitchen scales',           26, 'Azrieli Mall',     44,  'MANUAL'],
];

const insertSpend = db.prepare(`INSERT INTO spends
  (id, uuid, cardId, cardUuid, title, amount, storeName, spentAt, source, createdAt)
  VALUES (?,?,?,?,?,?,?,?,?,?)`);

spends.forEach((s, i) => {
  insertSpend.run(i + 1, 'spend-' + (i + 1), s[0], 'card-' + s[0], s[1], s[2], s[3],
      ago(s[4]), s[5], ago(s[4]));
});

// Report what each card ends up as, so the fixture is checked rather than assumed.
const check = db.prepare(`SELECT c.id, c.label, c.cardTypeId, c.initialAmount, c.expiryDate,
    c.archivedAt, COALESCE((SELECT SUM(amount) FROM spends s WHERE s.cardId = c.id), 0) AS spent
  FROM cards c ORDER BY c.id`).all();

console.log('card | label                | left  | expiry   | expected state');
for (const c of check) {
  const left = c.initialAmount - c.spent;
  let state = 'ACTIVE';
  if (c.archivedAt > 0) state = 'ARCHIVED';
  else if (left < 0.005) state = 'USED_UP';
  else if (c.expiryDate && c.expiryDate < month(0)) state = 'EXPIRED';
  console.log(
      String(c.id).padEnd(4), '|',
      String(c.label || '(' + c.cardTypeId + ')').padEnd(20), '|',
      String(left).padStart(5), '|',
      String(c.expiryDate || 'none').padEnd(8), '|', state);
}
console.log('\npurchases:', db.prepare('SELECT COUNT(*) n FROM spends').get().n);
db.close();
console.log('written:', out);
