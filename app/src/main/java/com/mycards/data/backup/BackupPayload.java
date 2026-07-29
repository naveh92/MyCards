package com.mycards.data.backup;

import java.util.ArrayList;
import java.util.List;

/**
 * The plaintext contents of a backup, before {@link BackupCodec} encrypts it.
 *
 * <p>Cards are identified by {@code uuid} rather than by their database row id, because row
 * ids are only unique within one device: restoring onto a second phone would otherwise
 * collide arbitrary cards with each other.
 *
 * <p>Sensitive fields appear here in the clear. That is the whole point — they are unwrapped
 * from the device-bound Keystore key and immediately rewrapped under the user's passphrase,
 * because a Keystore key cannot leave the phone it was created on. This object should never
 * be written anywhere unencrypted.
 */
public class BackupPayload {

    public int backupVersion = 1;
    public String exportedAt;
    public List<Card> cards = new ArrayList<>();
    public List<Spend> spends = new ArrayList<>();

    public static class Card {
        public String uuid;
        public String cardTypeId;
        public String label;
        public String expiryDate;
        public double initialAmount;
        public String currency;
        public String notes;
        public long createdAt;
        public long updatedAt;

        // Decrypted for transport, re-encrypted under the passphrase by the codec.
        public String pan;
        public String cvv;
        public String cardExpiry;
        public String giftUrl;
    }

    public static class Spend {
        public String uuid;
        public String cardUuid;
        public String title;
        public double amount;
        public String storeName;
        public long spentAt;
        public String source;
        public long createdAt;
    }

    public boolean isUsable(int maxSupportedVersion) {
        return backupVersion > 0 && backupVersion <= maxSupportedVersion && cards != null;
    }
}
