package com.mycards.data.source;

import com.mycards.data.catalog.model.SourceDef;
import com.mycards.search.Store;

import java.util.List;

/**
 * One strategy for obtaining a card's merchant list.
 *
 * <p>Implementations throw on failure; {@link StoreFetcher} catches and moves to the next
 * source in the chain. Returning an empty list also counts as a failure — an issuer page
 * that loads but yields nothing is far more likely to be a changed format than a genuinely
 * empty card.
 */
public interface StoreSourceProvider {

    /** The {@code type} value in the catalog that selects this provider. */
    String type();

    List<Store> fetchStores(SourceDef def, SourceEnv env) throws Exception;
}
