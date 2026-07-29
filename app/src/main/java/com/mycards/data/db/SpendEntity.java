package com.mycards.data.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** A single purchase charged against a card. */
@Entity(
        tableName = "spends",
        foreignKeys = @ForeignKey(
                entity = CardEntity.class,
                parentColumns = "id",
                childColumns = "cardId",
                onDelete = ForeignKey.CASCADE),
        indices = {@Index("cardId")})
public class SpendEntity {

    /** Logged by hand after a purchase. */
    public static final String SOURCE_MANUAL = "MANUAL";

    /** Created when an issuer balance check revealed spending that was never logged. */
    public static final String SOURCE_RECONCILIATION = "RECONCILIATION";

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** Stable cross-device identity; see {@link CardEntity#uuid}. */
    @NonNull
    public String uuid = java.util.UUID.randomUUID().toString();

    public long cardId;

    /** The owning card's uuid, so an export stays linked without local row ids. */
    public String cardUuid;

    /** What the money went on — required, so the log stays meaningful months later. */
    @NonNull
    public String title = "";

    public double amount;

    /** Where it was spent, if worth recording. */
    public String storeName;

    public long spentAt;

    @NonNull
    public String source = SOURCE_MANUAL;

    public long createdAt;
}
