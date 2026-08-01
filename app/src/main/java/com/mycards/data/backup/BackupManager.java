package com.mycards.data.backup;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.mycards.data.crypto.SecretVault;
import com.mycards.data.db.AppDatabase;
import com.mycards.data.db.CardEntity;
import com.mycards.data.db.SpendEntity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds and restores passphrase-encrypted backups.
 *
 * <p>Import <em>merges</em> rather than replacing. Wiping the device's data to accept a file
 * would destroy anything logged since that file was written, which is precisely the loss a
 * backup is supposed to prevent.
 */
public class BackupManager {

    private static final String TAG = "BackupManager";

    private static final int MAX_BACKUP_VERSION = 1;

    private final AppDatabase db;
    private final SecretVault vault;
    private final Gson gson = new Gson();

    public BackupManager(Context context) {
        this.db = AppDatabase.get(context);
        this.vault = new SecretVault(context);
    }

    /** The finished backup, plus what had to be left out of it. */
    public static class ExportResult {
        public final byte[] bytes;
        public final int cards;
        public final int spends;
        /** Cards whose secrets could not be read at all, and so were saved without them. */
        public final int cardsMissingSecrets;

        ExportResult(byte[] bytes, int cards, int spends, int cardsMissingSecrets) {
            this.bytes = bytes;
            this.cards = cards;
            this.spends = spends;
            this.cardsMissingSecrets = cardsMissingSecrets;
        }
    }

    /**
     * What an import found and what it changed — deliberately two separate things.
     *
     * <p>Reporting only the changes made a restore that did nothing at all read as a success.
     * Importing a file onto the phone it came from is a no-op by design, because the merge
     * keeps whatever is already there; "0 added, 5 unchanged" therefore says nothing
     * whatsoever about whether the file is any good. It prints the same for a complete backup,
     * for one holding a single card, and for one holding cards that are pure noise. Someone
     * checking that their backup works reads that as confirmation, and it is not.
     *
     * <p>So the counts of what the <em>file</em> holds are reported alongside, because that is
     * the question actually being asked.
     */
    public static class ImportResult {
        public int cardsInFile;
        public int spendsInFile;
        public int cardsAdded;
        public int cardsUpdated;
        public int cardsSkipped;
        /** Rejected by the vault, or unreadable in the file. Neither is the user's doing. */
        public int cardsFailed;
        public int spendsAdded;
        /** Purchases dropped because the card they belong to is not here. */
        public int spendsDropped;

        /** True when the file was read and not one thing in it was applied. */
        public boolean changedNothing() {
            return cardsAdded == 0 && cardsUpdated == 0 && spendsAdded == 0;
        }

        /**
         * True when something in the file did not make it in.
         *
         * <p>Every entry has to land in exactly one bucket, and the buckets have to add back
         * up to what the file held. Entries used to be dropped by a bare {@code continue} that
         * incremented nothing, so a restore could discard cards and purchases and still report
         * a tidy set of counts with no hint that anything was missing.
         */
        public boolean lostSomething() {
            return cardsFailed > 0
                    || spendsDropped > 0
                    || cardsAdded + cardsUpdated + cardsSkipped + cardsFailed != cardsInFile;
        }
    }

    /** The file decrypted cleanly but holds nothing — it was created and never filled in. */
    public static class EmptyBackupException extends Exception {
        public EmptyBackupException(String message) {
            super(message);
        }
    }

    /**
     * What a backup file actually contains, measured against the wallet as it stands.
     *
     * <p>Answers the only question worth asking of a backup: <em>if this phone died right
     * now, would this file bring everything back?</em> Parsing is not enough — a file can
     * decrypt perfectly and still be missing half the wallet because it was taken before
     * those cards existed.
     */
    public static class CheckResult {
        public String exportedAt;
        public int cardsInFile;
        public int spendsInFile;
        /** Cards in the file that carry a card number, CVV or gift link. */
        public int cardsWithSecrets;

        public int cardsOnPhone;
        /** Labels of cards held on this phone that the file does not contain at all. */
        public final List<String> missingFromFile = new ArrayList<>();
        /** Cards present in both, but recorded in the file as they were before later edits. */
        public int olderInFile;
        /** Cards on this phone that carry secrets the file has no copy of. */
        public int secretsMissingFromFile;

        /** True when restoring this file onto an empty phone would bring the wallet back. */
        public boolean coversEverything() {
            return missingFromFile.isEmpty() && secretsMissingFromFile == 0;
        }
    }

    // --- export ---

    /**
     * @throws SecretVault.AuthRequiredException when card numbers are stored and the user
     *         has not authenticated recently — the caller must prompt and retry rather than
     *         write the file, because the result would otherwise be missing every secret
     */
    public ExportResult export(char[] passphrase) throws Exception {
        Snapshot snapshot = snapshot(db.cardDao().getAll(), db.spendDao().getAll(), vaultReader());
        byte[] bytes = BackupCodec.encrypt(gson.toJson(snapshot.payload), passphrase);
        return new ExportResult(bytes,
                snapshot.payload.cards.size(),
                snapshot.payload.spends.size(),
                snapshot.cardsMissingSecrets);
    }

    /**
     * Re-reads a backup that has just been written and decrypts it.
     *
     * <p>Called on the bytes actually read back off disk, so "Backup saved" reports a file a
     * restore can genuinely read, rather than a write that merely returned without an error.
     *
     * @return how many cards the saved file contains
     */
    public int verifySaved(byte[] onDisk, char[] passphrase) throws Exception {
        BackupPayload payload = gson.fromJson(BackupCodec.decrypt(onDisk, passphrase),
                BackupPayload.class);
        if (payload == null || !payload.isUsable(MAX_BACKUP_VERSION)) {
            throw new BackupCodec.BackupFormatException(
                    "the file just written is not a readable backup");
        }
        return payload.cards.size();
    }

    // --- payload assembly, kept free of Room and the Keystore so it can be tested ---

    /** The two unwrapping operations an export needs. */
    interface SecretReader {
        String secret(String stored) throws SecretVault.AuthRequiredException, SecretVault.VaultException;

        String data(String stored) throws SecretVault.VaultException;
    }

    static class Snapshot {
        final BackupPayload payload = new BackupPayload();
        int cardsMissingSecrets;
    }

    static Snapshot snapshot(List<CardEntity> cards, List<SpendEntity> spends, SecretReader reader)
            throws SecretVault.AuthRequiredException {
        Snapshot snapshot = new Snapshot();
        snapshot.payload.exportedAt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .format(new Date());

        for (CardEntity card : cards) {
            BackupPayload.Card out = new BackupPayload.Card();
            out.uuid = card.uuid;
            out.cardTypeId = card.cardTypeId;
            out.label = card.label;
            out.expiryDate = card.expiryDate;
            out.initialAmount = card.initialAmount;
            out.currency = card.currency;
            out.notes = card.notes;
            out.createdAt = card.createdAt;
            out.updatedAt = card.updatedAt;

            // Unwrapped from the device-bound key here, rewrapped under the passphrase by
            // the codec. This is the only moment they exist in the clear.
            boolean[] omitted = {false};
            out.pan = unwrapOrOmit(() -> reader.secret(card.encPan), omitted);
            out.cvv = unwrapOrOmit(() -> reader.secret(card.encCvv), omitted);
            out.cardExpiry = unwrapOrOmit(() -> reader.secret(card.encCardExpiry), omitted);
            out.giftUrl = unwrapOrOmit(() -> reader.data(card.encGiftUrl), omitted);
            if (omitted[0]) {
                Log.w(TAG, "card " + card.uuid + ": secrets are unreadable, saving without them");
                snapshot.cardsMissingSecrets++;
            }

            snapshot.payload.cards.add(out);
        }

        for (SpendEntity spend : spends) {
            BackupPayload.Spend out = new BackupPayload.Spend();
            out.uuid = spend.uuid;
            out.cardUuid = spend.cardUuid;
            out.title = spend.title;
            out.amount = spend.amount;
            out.storeName = spend.storeName;
            out.spentAt = spend.spentAt;
            out.source = spend.source;
            out.createdAt = spend.createdAt;
            snapshot.payload.spends.add(out);
        }

        return snapshot;
    }

    private interface Unwrap {
        String run() throws Exception;
    }

    /**
     * Decrypts one stored value, treating a permanently unreadable one as an omission rather
     * than as a failed export.
     *
     * <p>The distinction is the whole point. {@link SecretVault.AuthRequiredException} means
     * "unlock and ask again"; swallowing it would hand back a file that looks complete while
     * silently missing every card number — the worst thing a backup can do. Any other vault
     * failure is permanent: changing the screen lock invalidates the auth-bound key for good,
     * and no retry will ever read those bytes again. Failing the whole export in that case
     * would throw away the labels, balances and spend history too, which are still perfectly
     * readable and are most of what the user would otherwise lose.
     */
    private static String unwrapOrOmit(Unwrap unwrap, boolean[] omitted)
            throws SecretVault.AuthRequiredException {
        try {
            return unwrap.run();
        } catch (SecretVault.AuthRequiredException retryable) {
            throw retryable;
        } catch (Exception permanent) {
            omitted[0] = true;
            return null;
        }
    }

    private SecretReader vaultReader() {
        return new SecretReader() {
            @Override
            public String secret(String stored)
                    throws SecretVault.AuthRequiredException, SecretVault.VaultException {
                return vault.decryptSecret(stored);
            }

            @Override
            public String data(String stored) throws SecretVault.VaultException {
                return vault.decryptData(stored);
            }
        };
    }

    public boolean hasSecretsToExport() {
        for (CardEntity card : db.cardDao().getAll()) {
            if (card.hasSensitiveData()) {
                return true;
            }
        }
        return false;
    }

    // --- import ---

    /**
     * Decrypts and validates a backup without touching the database.
     *
     * <p>Separate from {@link #apply} because a file holding card numbers cannot be written
     * back until the user has unlocked: the Keystore key they are rewrapped under refuses to
     * encrypt without a recent authentication. Opening first lets the caller find out whether
     * an unlock is needed at all, and ask for it before any of the work begins.
     */
    public BackupPayload open(byte[] blob, char[] passphrase) throws Exception {
        return parse(blob, passphrase);
    }

    /** Free of Room and the Keystore, so the checks below can be exercised on a plain JVM. */
    static BackupPayload parse(byte[] blob, char[] passphrase) throws Exception {
        BackupPayload payload = new Gson().fromJson(BackupCodec.decrypt(blob, passphrase),
                BackupPayload.class);
        if (payload == null || !payload.isUsable(MAX_BACKUP_VERSION)) {
            throw new BackupCodec.BackupFormatException(
                    "Backup was written by a newer version of the app");
        }
        if (payload.cards.isEmpty() && (payload.spends == null || payload.spends.isEmpty())) {
            // Refused rather than reported as a clean restore of nothing. A file that holds
            // nothing is the exact shape of a failed export, and the only moment anyone can
            // still act on that is now.
            throw new EmptyBackupException("the backup decrypted cleanly but holds nothing");
        }
        return payload;
    }

    /**
     * Decrypts a backup and reports what it holds, touching nothing.
     *
     * <p>This exists because there was no honest way to test a backup. Restoring it onto the
     * phone it came from is a no-op — every card is already newer — so it proves nothing,
     * and restoring it anywhere else means having a spare phone. Reading the file and
     * measuring it against the wallet answers the question without a second device and
     * without changing a single row.
     */
    public CheckResult check(byte[] blob, char[] passphrase) throws Exception {
        return compare(parse(blob, passphrase), db.cardDao().getAll());
    }

    static CheckResult compare(BackupPayload payload, List<CardEntity> onPhone) {
        CheckResult result = new CheckResult();
        result.exportedAt = payload.exportedAt;
        result.cardsInFile = payload.cards.size();
        result.spendsInFile = payload.spends == null ? 0 : payload.spends.size();
        result.cardsOnPhone = onPhone.size();

        Map<String, BackupPayload.Card> inFile = new HashMap<>();
        for (BackupPayload.Card card : payload.cards) {
            if (card.uuid != null) {
                inFile.put(card.uuid, card);
            }
            if (card.pan != null || card.cvv != null
                    || card.cardExpiry != null || card.giftUrl != null) {
                result.cardsWithSecrets++;
            }
        }

        for (CardEntity card : onPhone) {
            BackupPayload.Card backed = inFile.get(card.uuid);
            if (backed == null) {
                String label = card.label == null || card.label.trim().isEmpty()
                        ? card.cardTypeId : card.label.trim();
                result.missingFromFile.add(label);
                continue;
            }
            if (backed.updatedAt < card.updatedAt) {
                result.olderInFile++;
            }
            // A card can be in the file and still be missing what makes it usable — this is
            // what a partial export leaves behind, and it is invisible from a card count.
            //
            // Compared field by field rather than as "does the file hold any secret for this
            // card". A partial export drops whatever the auth-bound key could no longer read
            // while keeping the gift link, which lives under a different key and survives —
            // so "has something" is true for exactly the cards that have lost the most.
            if (missing(card.encPan, backed.pan)
                    || missing(card.encCvv, backed.cvv)
                    || missing(card.encCardExpiry, backed.cardExpiry)
                    || missing(card.encGiftUrl, backed.giftUrl)) {
                result.secretsMissingFromFile++;
            }
        }

        return result;
    }

    /** True when the phone holds this value and the file does not. */
    private static boolean missing(String onPhone, String inFile) {
        return onPhone != null && inFile == null;
    }

    /** True when restoring this file has to write through the auth-bound key. */
    public static boolean containsSecrets(BackupPayload payload) {
        for (BackupPayload.Card card : payload.cards) {
            if (card.pan != null || card.cvv != null || card.cardExpiry != null) {
                return true;
            }
        }
        return false;
    }

    public ImportResult restore(byte[] blob, char[] passphrase) throws Exception {
        return apply(open(blob, passphrase));
    }

    public ImportResult apply(BackupPayload payload) throws Exception {
        ImportResult result = new ImportResult();
        result.cardsInFile = payload.cards.size();
        result.spendsInFile = payload.spends == null ? 0 : payload.spends.size();

        for (BackupPayload.Card incoming : payload.cards) {
            if (incoming.uuid == null || incoming.uuid.trim().isEmpty()) {
                // Counted, not silently stepped over: a card in the file that never reaches
                // the wallet is exactly the kind of loss the user has to hear about.
                Log.w(TAG, "a card in the backup has no identifier and cannot be restored");
                result.cardsFailed++;
                continue;
            }
            CardEntity existing = db.cardDao().getByUuid(incoming.uuid);

            if (existing != null && existing.updatedAt >= incoming.updatedAt) {
                // What is already here is the same age or newer. Overwriting would throw
                // away edits made on this device since the backup was taken.
                result.cardsSkipped++;
                continue;
            }

            CardEntity target = existing != null ? existing : new CardEntity();
            target.uuid = incoming.uuid;
            target.cardTypeId = incoming.cardTypeId == null ? "" : incoming.cardTypeId;
            target.label = incoming.label;
            target.expiryDate = incoming.expiryDate;
            target.initialAmount = incoming.initialAmount;
            target.currency = incoming.currency == null ? "ILS" : incoming.currency;
            target.notes = incoming.notes;
            target.createdAt = incoming.createdAt;
            target.updatedAt = incoming.updatedAt;

            // Re-wrapped under this device's own Keystore key on the way in. A card the vault
            // refuses is counted and stepped over: aborting here would abandon the restore
            // halfway through, leaving a wallet that is neither what was on the phone nor
            // what is in the file, and no way to tell which cards made it.
            try {
                target.encPan = incoming.pan == null ? null : vault.encryptSecret(incoming.pan);
                target.encCvv = incoming.cvv == null ? null : vault.encryptSecret(incoming.cvv);
                target.encCardExpiry = incoming.cardExpiry == null
                        ? null : vault.encryptSecret(incoming.cardExpiry);
                target.encGiftUrl = incoming.giftUrl == null
                        ? null : vault.encryptData(incoming.giftUrl);
            } catch (Exception e) {
                Log.w(TAG, "could not store the secrets for card " + incoming.uuid, e);
                result.cardsFailed++;
                continue;
            }

            if (existing != null) {
                db.cardDao().update(target);
                result.cardsUpdated++;
            } else {
                db.cardDao().insert(target);
                result.cardsAdded++;
            }
        }

        for (BackupPayload.Spend incoming : payload.spends == null
                ? Collections.<BackupPayload.Spend>emptyList() : payload.spends) {
            if (incoming.uuid == null) {
                Log.w(TAG, "a purchase in the backup has no identifier");
                result.spendsDropped++;
                continue;
            }
            if (db.spendDao().getByUuid(incoming.uuid) != null) {
                // Spends are immutable once logged, so an existing uuid needs no work.
                continue;
            }
            CardEntity owner = incoming.cardUuid == null
                    ? null : db.cardDao().getByUuid(incoming.cardUuid);
            if (owner == null) {
                // Orphaned entry; importing it would corrupt some other card's balance. The
                // right call, but not a silent one — this is spend history going missing.
                Log.w(TAG, "dropping spend " + incoming.uuid + ": its card is missing");
                result.spendsDropped++;
                continue;
            }

            SpendEntity spend = new SpendEntity();
            spend.uuid = incoming.uuid;
            spend.cardUuid = incoming.cardUuid;
            spend.cardId = owner.id;
            spend.title = incoming.title == null ? "" : incoming.title;
            spend.amount = incoming.amount;
            spend.storeName = incoming.storeName;
            spend.spentAt = incoming.spentAt;
            spend.source = incoming.source == null ? SpendEntity.SOURCE_MANUAL : incoming.source;
            spend.createdAt = incoming.createdAt;
            db.spendDao().insert(spend);
            result.spendsAdded++;
        }

        Log.i(TAG, "import complete: file held " + result.cardsInFile + " cards / "
                + result.spendsInFile + " purchases; added " + result.cardsAdded
                + ", updated " + result.cardsUpdated + ", unchanged " + result.cardsSkipped
                + ", failed " + result.cardsFailed + ", purchases added " + result.spendsAdded);
        return result;
    }
}
