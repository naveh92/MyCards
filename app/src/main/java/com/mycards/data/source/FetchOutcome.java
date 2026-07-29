package com.mycards.data.source;

import com.mycards.search.Store;

import java.util.Collections;
import java.util.List;

/** Result of running a card type's fallback chain, including what went wrong along the way. */
public final class FetchOutcome {

    private final List<Store> stores;
    private final String succeedingSourceType;
    private final List<String> failures;

    private FetchOutcome(List<Store> stores, String succeedingSourceType, List<String> failures) {
        this.stores = stores;
        this.succeedingSourceType = succeedingSourceType;
        this.failures = failures;
    }

    public static FetchOutcome success(List<Store> stores, String sourceType, List<String> failures) {
        return new FetchOutcome(stores, sourceType, failures);
    }

    public static FetchOutcome failure(List<String> failures) {
        return new FetchOutcome(Collections.<Store>emptyList(), null, failures);
    }

    public boolean isSuccess() {
        return succeedingSourceType != null;
    }

    public List<Store> getStores() {
        return stores;
    }

    /** Which source actually produced the data, shown in the UI as provenance. */
    public String getSucceedingSourceType() {
        return succeedingSourceType;
    }

    /** Human-readable reasons each earlier source was skipped; useful for diagnosing rot. */
    public List<String> getFailures() {
        return Collections.unmodifiableList(failures);
    }
}
