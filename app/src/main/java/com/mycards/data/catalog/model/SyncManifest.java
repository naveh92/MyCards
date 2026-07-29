package com.mycards.data.catalog.model;

import java.util.Map;

/**
 * Index of the published store lists, fetched before anything else during a sync.
 *
 * <p>It exists so a refresh that finds nothing new costs one small request rather than
 * re-downloading every list to discover they are unchanged. With BuyMe All alone at ~500 KB
 * published (5.8 MB at source), that difference is the whole reason a weekly sync is
 * reasonable to run on mobile data.
 */
public class SyncManifest {

    public int manifestVersion;
    public String generatedAt;

    public FileInfo catalog;

    /** Keyed by card type id, e.g. {@code buyme_all}. */
    public Map<String, FileInfo> stores;

    public static class FileInfo {
        public String sha256;
        public long bytes;
        public int count;
    }

    /** @return the published hash for a card type, or null when it is not published */
    public String hashFor(String cardTypeId) {
        FileInfo info = infoFor(cardTypeId);
        return info == null ? null : info.sha256;
    }

    /**
     * @return how many merchants the published list should contain, or 0 when unknown
     *
     * <p>Used as a sanity floor: a download that parses cleanly but yields a fraction of
     * this is far more likely to be truncated or half-published than a real change.
     */
    public int countFor(String cardTypeId) {
        FileInfo info = infoFor(cardTypeId);
        return info == null ? 0 : info.count;
    }

    private FileInfo infoFor(String cardTypeId) {
        return stores == null ? null : stores.get(cardTypeId);
    }

    public boolean isUsable(int maxSupportedVersion) {
        return manifestVersion > 0
                && manifestVersion <= maxSupportedVersion
                && stores != null
                && !stores.isEmpty();
    }
}
