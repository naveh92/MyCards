package com.mycards.data.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * One physical gift card the user holds.
 *
 * <p>Several rows may share a {@code cardTypeId} — two All-inZone cards from different
 * years are two cards over one merchant list.
 *
 * <p>The {@code enc*} columns hold Base64 of IV+ciphertext produced by
 * {@link com.mycards.data.crypto.SecretVault}. Nothing sensitive is ever written in clear
 * text, so a database pulled off the device without the Keystore key reveals only the card
 * type, balance and expiry.
 */
@Entity(tableName = "cards", indices = {@Index("cardTypeId")})
public class CardEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /**
     * Stable identity that survives export, restore and a move to a new phone.
     *
     * <p>The autoincrement {@link #id} is only unique within one database, so two devices
     * would inevitably assign the same id to different cards. Merging needs an identifier
     * minted at creation time and never reused.
     */
    @NonNull
    public String uuid = java.util.UUID.randomUUID().toString();

    /** Last local modification, epoch millis — the tiebreaker when merging two copies. */
    public long updatedAt;

    /** Catalog card type id, e.g. {@code buyme_all}. */
    @NonNull
    public String cardTypeId = "";

    /** The user's own label, so two cards of one type stay distinguishable. */
    public String label;

    /** Gift-card expiry as {@code yyyy-MM} (see {@code Formats.ISO_MONTH}); null when open-ended. */
    public String expiryDate;

    /** Face value when the card was added. */
    public double initialAmount;

    @NonNull
    public String currency = "ILS";

    // --- encrypted, optional ---

    @ColumnInfo(name = "enc_pan")
    public String encPan;

    @ColumnInfo(name = "enc_cvv")
    public String encCvv;

    /** Expiry printed on the payment card itself, which may differ from the voucher's. */
    @ColumnInfo(name = "enc_card_expiry")
    public String encCardExpiry;

    @ColumnInfo(name = "enc_gift_url")
    public String encGiftUrl;

    public String notes;

    public long createdAt;

    /** Epoch millis of the last automatic balance check; 0 when never checked. */
    public long lastBalanceCheckAt;

    /** Balance last reported by the issuer, null when unknown. */
    public Double lastFetchedBalance;

    /**
     * Set when a balance check found less money than the spend log accounts for, and the
     * user has not yet reconciled it. Drives the "unlogged transaction" prompt.
     */
    public boolean hasUnreconciledMismatch;

    /**
     * True when this card holds anything behind the auth-bound key.
     *
     * <p>All three fields count. The card expiry was missing from this test while every
     * other part of the app treated it as a secret — saving it demands an unlock, revealing
     * it decrypts it, and exporting it needs the key — so a card carrying only an expiry got
     * no "Show card number" button at all and its own data became unreachable.
     */
    public boolean hasSensitiveData() {
        return encPan != null || encCvv != null || encCardExpiry != null;
    }

    public boolean hasGiftUrl() {
        return encGiftUrl != null;
    }
}
