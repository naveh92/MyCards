import com.mycards.data.backup.BackupCodec;
import com.mycards.data.backup.BackupPayload;

import com.google.gson.Gson;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Writes a passphrase-encrypted backup full of plausible demo cards.
 *
 * <p>Two jobs, both worth having:
 *
 * <ol>
 *   <li>It seeds a clean install with data worth photographing, so the store screenshots
 *       show the app doing its actual job rather than an empty state.
 *   <li>It is an end-to-end check on the R8 configuration. This tool compiles the real
 *       {@link BackupPayload} unobfuscated; the release APK reads it back through a build
 *       where R8 has renamed everything it was allowed to. If the keep rule for the payload
 *       is ever dropped, the import fails here instead of on a stranger's new phone.
 * </ol>
 *
 * <p>Card numbers below are the standard non-routable test values. They are not accounts.
 *
 * <p>Usage: {@code java DemoBackupGen <out-file> <passphrase>}
 */
public final class DemoBackupGen {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: DemoBackupGen <out-file> <passphrase>");
            System.exit(2);
        }

        BackupPayload payload = new BackupPayload();
        payload.backupVersion = 1;
        payload.exportedAt = "2026-07-29T09:00:00Z";

        // A spread that exercises the screens rather than just filling them: one card with
        // stored payment details, one nearly expired, one with a purchase history, and
        // enough different issuers that the search results are visibly discriminating.
        card(payload, "b1f0c3a2-0001-4a10-9c01-000000000001", "buyme_all",
                "Rosh Hashana 2025", "2027-03", 500.00,
                "4580458045804580", "123", "2027-03",
                "https://buyme.co.il/giftcard/demo-not-a-real-link");

        card(payload, "b1f0c3a2-0002-4a10-9c01-000000000002", "all_in_zone",
                "All-inZone", "2026-12", 250.00, null, null, null, null);

        card(payload, "b1f0c3a2-0003-4a10-9c01-000000000003", "love_gift_card",
                "LOVE", "2026-09", 150.00, null, null, null, null);

        card(payload, "b1f0c3a2-0004-4a10-9c01-000000000004", "buyme_chef",
                "BuyMe CHEF", "2027-06", 300.00, null, null, null, null);

        // Expires next month, so the list shows its expiring-soon treatment. That warning is
        // the whole reason the app sorts by expiry, so a screenshot without one undersells it.
        card(payload, "b1f0c3a2-0005-4a10-9c01-000000000005", "superzone",
                "SuperZone", "2026-08", 400.00, null, null, null, null);

        spend(payload, "5be0c3a2-0001-4a10-9c01-000000000001",
                "b1f0c3a2-0001-4a10-9c01-000000000001",
                // Merchants chosen from the real bundled lists. Zara reads like an obvious
                // demo store but is not a BuyMe merchant, so it would have shown a purchase
                // at a shop that card cannot be used in.
                "Winter jacket", 189.90, "MANGO", 2026, Calendar.MAY, 12);
        spend(payload, "5be0c3a2-0002-4a10-9c01-000000000002",
                "b1f0c3a2-0001-4a10-9c01-000000000001",
                "Bed linen", 74.00, "Fox Home", 2026, Calendar.JUNE, 20);
        spend(payload, "5be0c3a2-0003-4a10-9c01-000000000003",
                "b1f0c3a2-0003-4a10-9c01-000000000003",
                "T-shirts", 62.50, "Castro", 2026, Calendar.JULY, 4);

        byte[] blob = BackupCodec.encrypt(new Gson().toJson(payload),
                args[1].toCharArray());
        Files.write(Paths.get(args[0]), blob);

        System.out.println("wrote " + blob.length + " bytes to " + args[0]
                + " (" + payload.cards.size() + " cards, "
                + payload.spends.size() + " purchases)");
    }

    private static void card(BackupPayload payload, String uuid, String typeId, String label,
                             String expiry, double amount,
                             String pan, String cvv, String cardExpiry, String giftUrl) {
        BackupPayload.Card c = new BackupPayload.Card();
        c.uuid = uuid;
        c.cardTypeId = typeId;
        c.label = label;
        c.expiryDate = expiry;
        c.initialAmount = amount;
        c.currency = "ILS";
        c.pan = pan;
        c.cvv = cvv;
        c.cardExpiry = cardExpiry;
        c.giftUrl = giftUrl;
        c.createdAt = millis(2025, Calendar.SEPTEMBER, 15);
        // Older than any card already on the device would be, so a restore onto a populated
        // install is skipped rather than silently overwriting real data.
        c.updatedAt = millis(2026, Calendar.JULY, 1);
        payload.cards.add(c);
    }

    private static void spend(BackupPayload payload, String uuid, String cardUuid, String title,
                              double amount, String store, int year, int month, int day) {
        BackupPayload.Spend s = new BackupPayload.Spend();
        s.uuid = uuid;
        s.cardUuid = cardUuid;
        s.title = title;
        s.amount = amount;
        s.storeName = store;
        s.spentAt = millis(year, month, day);
        s.source = "MANUAL";
        s.createdAt = s.spentAt;
        payload.spends.add(s);
    }

    private static long millis(int year, int month, int day) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.clear();
        cal.set(year, month, day, 12, 0, 0);
        return cal.getTimeInMillis();
    }

    private DemoBackupGen() {
    }
}
