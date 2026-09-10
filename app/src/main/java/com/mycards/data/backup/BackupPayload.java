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

        /**
         * When the card was put away by hand; 0 while in use.
         *
         * <p>Added after the format was already in the wild, and deliberately without
         * bumping {@code backupVersion}: a file written by 1.2 simply has no such field, Gson
         * leaves it at 0, and the card restores as in use — which is what it was. An older
         * build reading a newer file ignores the field for the same reason. Raising the
         * version would have made every existing backup unreadable to buy nothing.
         */
        public long archivedAt;

        /**
         * The colour the user picked for this card, ARGB, or null to let the card type pick.
         *
         * <p>Added the same way {@link #archivedAt} was and for the same reason: an older
         * file has no such field, Gson leaves it null, and the card restores taking its
         * colour from its type — which is what it was doing. Nothing about the format needs
         * to change for that to work in both directions.
         */
        public Integer faceColor;

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
