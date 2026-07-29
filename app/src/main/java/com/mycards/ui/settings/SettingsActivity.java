package com.mycards.ui.settings;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.mycards.R;
import com.mycards.data.RemoteConfig;
import com.mycards.data.backup.BackupCodec;
import com.mycards.data.backup.BackupManager;
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

            AppExecutors.main(() -> {
                if (!needsUnlock) {
                    PassphraseDialog.show(this, R.string.backup_export, true,
                            passphrase -> writeBackup(uri, passphrase));
                    return;
                }
                // Card numbers sit behind an auth-bound key, so exporting them needs an
                // unlock exactly as revealing them does.
                BiometricGate.authenticate(this,
                        getString(R.string.biometric_title),
                        getString(R.string.backup_export),
                        new BiometricGate.Callback() {
                            @Override
                            public void onSuccess() {
                                PassphraseDialog.show(SettingsActivity.this,
                                        R.string.backup_export, true,
                                        passphrase -> writeBackup(uri, passphrase));
                            }

                            @Override
                            public void onFailure() {
                                toast(R.string.biometric_failed);
                            }
                        });
            });
        });
    }

    private void writeBackup(Uri uri, char[] passphrase) {
        toast(R.string.backup_working);
        AppExecutors.io(() -> {
            try {
                byte[] blob = new BackupManager(this).export(passphrase);
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) {
                        throw new IOException("could not open " + uri);
                    }
                    out.write(blob);
                }
                AppExecutors.main(() -> toast(R.string.backup_exported));
            } catch (Exception e) {
                Log.e(TAG, "export failed", e);
                AppExecutors.main(() -> toast(R.string.backup_export_failed));
            } finally {
                // Do not leave the passphrase sitting in memory once it has been used.
                Arrays.fill(passphrase, '\0');
            }
        });
    }

    private void readBackup(Uri uri, char[] passphrase) {
        toast(R.string.backup_working);
        AppExecutors.io(() -> {
            try {
                byte[] blob;
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
                    blob = buffer.toByteArray();
                }

                BackupManager.ImportResult result =
                        new BackupManager(this).restore(blob, passphrase);
                AppExecutors.main(() -> Toast.makeText(this,
                        getString(R.string.backup_imported, result.toString()),
                        Toast.LENGTH_LONG).show());

            } catch (BackupCodec.InvalidPassphraseException wrong) {
                AppExecutors.main(() -> toast(R.string.backup_wrong_passphrase));
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
