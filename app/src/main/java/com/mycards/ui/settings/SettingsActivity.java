package com.mycards.ui.settings;

import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.mycards.R;
import com.mycards.data.RemoteConfig;
import com.mycards.data.backup.BackupCodec;
import com.mycards.data.backup.BackupManager;
import com.mycards.data.backup.BackupPayload;
import com.mycards.data.crypto.SecretVault;
import com.mycards.sync.SyncScheduler;
import com.mycards.ui.AppExecutors;
import com.mycards.ui.BiometricGate;
import com.mycards.ui.Formats;
import com.mycards.ui.ThemePrefs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        com.mycards.ui.EdgeToEdge.apply(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        setUpLanguage();
        setUpTheme();
        setUpSync();
        setUpBackup();
    }

    // --- backup ---

    private ActivityResultLauncher<String> createBackupFile;
    private ActivityResultLauncher<String[]> openBackupFile;

    private void setUpBackup() {
        // File first, passphrase second. Asking for the passphrase first meant holding it
        // in a field while the system file picker was in front — and if Android destroyed
        // this activity in the meantime, that field came back null and the export silently
        // did nothing. Nothing now has to survive the round trip.
        createBackupFile = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/octet-stream"),
                uri -> {
                    if (uri != null) {
                        askPassphraseThenExport(uri);
                    }
                });

        openBackupFile = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri == null) {
                        return;
                    }
                    PassphraseDialog.show(this, R.string.backup_import, false,
                            passphrase -> readBackup(uri, passphrase));
                });

        findViewById(R.id.exportBackup).setOnClickListener(v -> startExport());
        findViewById(R.id.importBackup).setOnClickListener(v ->
                openBackupFile.launch(new String[]{"*/*"}));
    }

    private void startExport() {
        createBackupFile.launch("mycards-"
                + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date())
                + ".mycards");
    }

    /** Runs once a destination has been chosen, so no state spans the picker. */
    private void askPassphraseThenExport(Uri uri) {
        // hasSecretsToExport() reads the database, so it cannot run here — this is the
        // main thread, and Room throws rather than risking a janky query. That throw was
        // being delivered inside the activity-result callback, which took the whole
        // activity down and looked from outside like the app silently restarting.
        AppExecutors.io(() -> {
            boolean needsUnlock = new BackupManager(this).hasSecretsToExport()
                    && new SecretVault(this).isBiometricProtectionAvailable();

            AppExecutors.main(() -> PassphraseDialog.show(this, R.string.backup_export, true,
                    passphrase -> {
                        if (needsUnlock) {
                            unlockThenWrite(uri, passphrase);
                        } else {
                            writeBackup(uri, passphrase, false);
                        }
                    },
                    // The picker created the file the moment a location was chosen. Backing
                    // out now would leave an empty one behind, indistinguishable from a real
                    // backup until the day someone needs it.
                    () -> discardUnwritten(uri, 0)));
        });
    }

    /**
     * Unlocks immediately before the export, rather than before the passphrase prompt.
     *
     * <p>Card numbers sit behind an auth-bound Keystore key, and that key stays authorised
     * for thirty seconds. Prompting first and only then asking for a passphrase — invented
     * on the spot and typed twice — routinely spends longer than that, so the decryption at
     * the far end was refused and the export died after its file already existed. Unlocking
     * last leaves the whole window for the work that actually needs it.
     */
    private void unlockThenWrite(Uri uri, char[] passphrase) {
        BiometricGate.authenticate(this,
                getString(R.string.biometric_title),
                getString(R.string.backup_export),
                new BiometricGate.Callback() {
                    @Override
                    public void onSuccess() {
                        writeBackup(uri, passphrase, true);
                    }

                    @Override
                    public void onFailure() {
                        Arrays.fill(passphrase, '\0');
                        discardUnwritten(uri, R.string.backup_export_needs_unlock);
                    }
                });
    }

    /**
     * @param alreadyUnlocked true when an unlock has just been shown, so a key refusal here
     *                        is a real failure rather than something one retry could fix
     */
    private void writeBackup(Uri uri, char[] passphrase, boolean alreadyUnlocked) {
        toast(R.string.backup_working);
        AppExecutors.io(() -> {
            BackupManager.ExportResult result;
            try {
                result = new BackupManager(this).export(passphrase);
            } catch (SecretVault.AuthRequiredException needsUnlock) {
                if (alreadyUnlocked) {
                    Arrays.fill(passphrase, '\0');
                    discardUnwritten(uri, R.string.backup_export_unlock_expired);
                } else {
                    // The retry takes ownership of the passphrase, so it is not wiped here.
                    AppExecutors.main(() -> unlockThenWrite(uri, passphrase));
                }
                return;
            } catch (Exception e) {
                Log.e(TAG, "could not assemble the backup", e);
                Arrays.fill(passphrase, '\0');
                discardUnwritten(uri, R.string.backup_export_failed_body);
                return;
            }

            try {
                if (result.cards == 0 && result.spends == 0) {
                    discardUnwritten(uri, R.string.backup_nothing_to_export);
                    return;
                }

                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) {
                        throw new IOException("could not open " + uri + " for writing");
                    }
                    out.write(result.bytes);
                    out.flush();
                }

                // Read the file back and decrypt it before claiming anything. A write that
                // returns without complaint is not proof of a restorable backup, and this is
                // the one operation whose failure is only ever discovered too late.
                byte[] onDisk = readAll(uri);
                if (!Arrays.equals(onDisk, result.bytes)) {
                    throw new IOException("saved file holds " + onDisk.length
                            + " bytes, expected " + result.bytes.length);
                }
                int cardsInFile = new BackupManager(this).verifySaved(onDisk, passphrase);

                AppExecutors.main(() -> announceExport(result, cardsInFile));
            } catch (Exception e) {
                Log.e(TAG, "export failed", e);
                discardUnwritten(uri, R.string.backup_export_failed_body);
            } finally {
                // Do not leave the passphrase sitting in memory once it has been used.
                Arrays.fill(passphrase, '\0');
            }
        });
    }

    private void announceExport(BackupManager.ExportResult result, int cardsInFile) {
        if (result.cardsMissingSecrets == 0) {
            // Counts, not just "saved": an empty backup should be obvious on the spot.
            Toast.makeText(this,
                    getString(R.string.backup_exported_count, cardsInFile, result.spends),
                    Toast.LENGTH_LONG).show();
            return;
        }
        // Losing card numbers is not something to report in a toast that vanishes.
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.backup_export_partial_title)
                .setMessage(getString(R.string.backup_export_partial_body,
                        cardsInFile, result.spends, result.cardsMissingSecrets))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * Removes the file the picker created, since nothing was ever written into it.
     *
     * @param reasonRes what to tell the user, or 0 when they cancelled and already know
     */
    private void discardUnwritten(Uri uri, int reasonRes) {
        AppExecutors.io(() -> {
            boolean removed = false;
            try {
                removed = DocumentsContract.deleteDocument(getContentResolver(), uri);
            } catch (Exception e) {
                Log.w(TAG, "could not remove the unwritten backup file", e);
            }

            boolean leftBehind = !removed;
            AppExecutors.main(() -> {
                if (reasonRes == 0 || isFinishing() || isDestroyed()) {
                    return;
                }
                String message = getString(reasonRes);
                if (leftBehind) {
                    message += "\n\n" + getString(R.string.backup_leftover_file);
                }
                new AlertDialog.Builder(this)
                        .setTitle(R.string.backup_export_failed)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        });
    }

    private byte[] readAll(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("could not open " + uri);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            return buffer.toByteArray();
        }
    }

    private void readBackup(Uri uri, char[] passphrase) {
        toast(R.string.backup_working);
        AppExecutors.io(() -> {
            try {
                BackupPayload payload = new BackupManager(this).open(readAll(uri), passphrase);

                // Card numbers are rewrapped under the auth-bound Keystore key on the way in,
                // and that key refuses to encrypt without a recent unlock just as it refuses
                // to decrypt. Without this the restore threw halfway through and reported
                // only "could not read that backup".
                boolean needsUnlock = BackupManager.containsSecrets(payload)
                        && new SecretVault(this).isBiometricProtectionAvailable();

                AppExecutors.main(() -> {
                    if (!needsUnlock) {
                        applyBackup(payload);
                        return;
                    }
                    BiometricGate.authenticate(this,
                            getString(R.string.biometric_title),
                            getString(R.string.backup_import),
                            new BiometricGate.Callback() {
                                @Override
                                public void onSuccess() {
                                    applyBackup(payload);
                                }

                                @Override
                                public void onFailure() {
                                    toast(R.string.backup_import_needs_unlock);
                                }
                            });
                });

            } catch (BackupCodec.InvalidPassphraseException wrong) {
                AppExecutors.main(() -> toast(R.string.backup_wrong_passphrase));
            } catch (BackupManager.EmptyBackupException empty) {
                Log.w(TAG, "refused an empty backup", empty);
                AppExecutors.main(() -> showImportProblem(R.string.backup_empty));
            } catch (BackupCodec.BackupFormatException notOurs) {
                AppExecutors.main(() -> toast(R.string.backup_not_a_backup));
            } catch (Exception e) {
                Log.e(TAG, "import failed", e);
                AppExecutors.main(() -> toast(R.string.backup_import_failed));
            } finally {
                Arrays.fill(passphrase, '\0');
            }
        });
    }

    private void applyBackup(BackupPayload payload) {
        AppExecutors.io(() -> {
            try {
                BackupManager.ImportResult result = new BackupManager(this).apply(payload);
                AppExecutors.main(() -> announceImport(result));
            } catch (Exception e) {
                Log.e(TAG, "restore failed", e);
                AppExecutors.main(() -> toast(R.string.backup_import_failed));
            }
        });
    }

    /**
     * Reports a restore, and calls a restore that restored nothing what it is.
     *
     * <p>The old wording was "Restored: 0 added, 0 updated, 5 unchanged, 0 purchases". Every
     * number in it was correct and the whole thing was a lie by framing: not one item had
     * been applied. Read as confirmation that a backup file was good, it is worth less than
     * nothing — the same line appears for a file holding five cards, one card, or rubbish.
     *
     * <p>So the outcome leads, and only a restore that actually put something back is allowed
     * to look like one.
     */
    private void announceImport(BackupManager.ImportResult result) {
        if (isFinishing() || isDestroyed()) {
            return;
        }

        StringBuilder message = new StringBuilder();
        if (result.changedNothing()) {
            message.append(getString(R.string.backup_import_changed_nothing)).append("\n\n");
        }
        message.append(getString(R.string.backup_import_file_holds,
                result.cardsInFile, result.spendsInFile));
        message.append("\n\n").append(getString(R.string.backup_import_outcome,
                result.cardsAdded, result.cardsUpdated, result.cardsSkipped, result.spendsAdded));

        if (result.cardsFailed > 0) {
            message.append("\n\n").append(getString(R.string.backup_import_some_failed,
                    result.cardsFailed));
        }
        if (result.spendsDropped > 0) {
            message.append("\n\n").append(getString(R.string.backup_import_spends_dropped,
                    result.spendsDropped));
        }

        int title = R.string.backup_import_result_title;
        if (result.changedNothing()) {
            title = R.string.backup_import_nothing_title;
        } else if (result.lostSomething()) {
            title = R.string.backup_import_incomplete_title;
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showImportProblem(int messageRes) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.backup_import_failed)
                .setMessage(messageRes)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void toast(int messageRes) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show();
    }

    private void setUpLanguage() {
        RadioButton system = findViewById(R.id.langSystem);
        RadioButton english = findViewById(R.id.langEnglish);
        RadioButton hebrew = findViewById(R.id.langHebrew);

        LocaleListCompat current = AppCompatDelegate.getApplicationLocales();
        if (current.isEmpty()) {
            system.setChecked(true);
        } else {
            String tag = current.get(0).getLanguage();
            // Android reports Hebrew as the legacy code "iw" on many devices.
            if ("he".equals(tag) || "iw".equals(tag)) {
                hebrew.setChecked(true);
            } else {
                english.setChecked(true);
            }
        }

        // Applying locales recreates the activity, which is what flips the layout to RTL.
        system.setOnClickListener(v ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()));
        english.setOnClickListener(v ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")));
        hebrew.setOnClickListener(v ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("he")));
    }

    private void setUpTheme() {
        RadioButton system = findViewById(R.id.themeSystem);
        RadioButton light = findViewById(R.id.themeLight);
        RadioButton dark = findViewById(R.id.themeDark);

        int mode = ThemePrefs.getMode(this);
        if (mode == AppCompatDelegate.MODE_NIGHT_NO) {
            light.setChecked(true);
        } else if (mode == AppCompatDelegate.MODE_NIGHT_YES) {
            dark.setChecked(true);
        } else {
            system.setChecked(true);
        }

        system.setOnClickListener(v ->
                ThemePrefs.setMode(this, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
        light.setOnClickListener(v ->
                ThemePrefs.setMode(this, AppCompatDelegate.MODE_NIGHT_NO));
        dark.setOnClickListener(v ->
                ThemePrefs.setMode(this, AppCompatDelegate.MODE_NIGHT_YES));
    }

    private void setUpSync() {
        TextView lastSync = findViewById(R.id.lastSync);
        long at = SyncScheduler.lastSyncAt(this);
        lastSync.setText(getString(R.string.last_sync,
                at == 0L ? getString(R.string.never) : Formats.prettyDate(this, at)));

        TextView note = findViewById(R.id.catalogNote);
        if (!RemoteConfig.isCatalogUrlConfigured()) {
            note.setText(R.string.catalog_not_configured);
        } else {
            note.setText(RemoteConfig.CATALOG_BASE_URL);
        }

        findViewById(R.id.syncNow).setOnClickListener(v -> {
            SyncScheduler.syncNow(this);
            Toast.makeText(this, R.string.sync_running, Toast.LENGTH_SHORT).show();
        });
    }
}
