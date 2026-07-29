package com.mycards.data.backup;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.mycards.data.crypto.SecretVault;
import com.mycards.data.db.AppDatabase;
import com.mycards.data.db.CardEntity;
import com.mycards.data.db.SpendEntity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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

    /** Counts of what an import changed, so the user gets a real confirmation. */
    public static class ImportResult {
        public int cardsAdded;
        public int cardsUpdated;
        public int cardsSkipped;
        public int spendsAdded;

        @Override
        public String toString() {
            return cardsAdded + " added, " + cardsUpdated + " updated, "
                    + cardsSkipped + " unchanged, " + spendsAdded + " purchases";
        }
    }

    // --- export ---

    /**
     * @throws SecretVault.AuthRequiredException when card numbers are stored and the user
     *         has not authenticated recently — the caller should prompt and retry
     */
    public byte[] export(char[] passphrase) throws Exception {
        BackupPayload payload = new BackupPayload();
        payload.exportedAt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .format(new Date());

        for (CardEntity card : db.cardDao().getAll()) {
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
            out.pan = vault.decryptSecret(card.encPan);
            out.cvv = vault.decryptSecret(card.encCvv);
            out.cardExpiry = vault.decryptSecret(card.encCardExpiry);
            out.giftUrl = vault.decryptData(card.encGiftUrl);

            payload.cards.add(out);
        }

        for (SpendEntity spend : db.spendDao().getAll()) {
            BackupPayload.Spend out = new BackupPayload.Spend();
            out.uuid = spend.uuid;
            out.cardUuid = spend.cardUuid;
            out.title = spend.title;
            out.amount = spend.amount;
            out.storeName = spend.storeName;
            out.spentAt = spend.spentAt;
            out.source = spend.source;
            out.createdAt = spend.createdAt;
            payload.spends.add(out);
        }

        return BackupCodec.encrypt(gson.toJson(payload), passphrase);
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

    public ImportResult restore(byte[] blob, char[] passphrase) throws Exception {
        String json = BackupCodec.decrypt(blob, passphrase);

        BackupPayload payload = gson.fromJson(json, BackupPayload.class);
        if (payload == null || !payload.isUsable(MAX_BACKUP_VERSION)) {
            throw new BackupCodec.BackupFormatException(
                    "Backup was written by a newer version of the app");
        }

        ImportResult result = new ImportResult();

        for (BackupPayload.Card incoming : payload.cards) {
            if (incoming.uuid == null || incoming.uuid.trim().isEmpty()) {
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

            // Re-wrapped under this device's own Keystore key on the way in.
            target.encPan = incoming.pan == null ? null : vault.encryptSecret(incoming.pan);
            target.encCvv = incoming.cvv == null ? null : vault.encryptSecret(incoming.cvv);
            target.encCardExpiry = incoming.cardExpiry == null
                    ? null : vault.encryptSecret(incoming.cardExpiry);
            target.encGiftUrl = incoming.giftUrl == null
                    ? null : vault.encryptData(incoming.giftUrl);

            if (existing != null) {
                db.cardDao().update(target);
                result.cardsUpdated++;
            } else {
                db.cardDao().insert(target);
                result.cardsAdded++;
            }
        }

        for (BackupPayload.Spend incoming : payload.spends) {
            if (incoming.uuid == null || db.spendDao().getByUuid(incoming.uuid) != null) {
                // Spends are immutable once logged, so an existing uuid needs no work.
                continue;
            }
            CardEntity owner = incoming.cardUuid == null
                    ? null : db.cardDao().getByUuid(incoming.cardUuid);
            if (owner == null) {
                // Orphaned entry; importing it would corrupt some other card's balance.
                Log.w(TAG, "skipping spend " + incoming.uuid + ": its card is missing");
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

        Log.i(TAG, "import complete: " + result);
        return result;
    }
}
