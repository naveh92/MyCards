package com.mycards.data.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.mycards.data.catalog.model.CardTypeDef;
import com.mycards.data.catalog.model.SourceDef;
import com.mycards.search.Store;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The fallback chain is the app's defence against every data source being independently
 * fragile, so its degradation behaviour is worth pinning down precisely.
 */
public class StoreFetcherTest {

    private SourceEnv env;

    @Before
    public void setUp() {
        // The fakes never touch the network or assets, so null capabilities are fine.
        env = new SourceEnv(null, null, "https://example.test");
    }

    private static SourceDef source(String type) {
        SourceDef def = new SourceDef();
        def.type = type;
        return def;
    }

    private static CardTypeDef cardType(String... sourceTypes) {
        CardTypeDef def = new CardTypeDef();
        def.id = "test_card";
        def.storeSources = new ArrayList<>();
        for (String t : sourceTypes) {
            def.storeSources.add(source(t));
        }
        return def;
    }

    /** Provider that always throws, standing in for a dead endpoint. */
    private static StoreSourceProvider failing(final String type) {
        return new StoreSourceProvider() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public List<Store> fetchStores(SourceDef def, SourceEnv env) throws Exception {
                throw new java.io.IOException("simulated failure");
            }
        };
    }

    private static StoreSourceProvider yielding(final String type, final List<Store> stores) {
        return new StoreSourceProvider() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public List<Store> fetchStores(SourceDef def, SourceEnv env) {
                return stores;
            }
        };
    }

    private static List<Store> oneStore() {
        return Collections.singletonList(
                new Store("Zara", Arrays.asList("זארה"), false));
    }

    @Test
    public void firstWorkingSourceWins() {
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(yielding("primary", oneStore()));
        registry.register(yielding("backup", oneStore()));

        FetchOutcome outcome = new StoreFetcher(registry)
                .fetch(cardType("primary", "backup"), env);

        assertTrue(outcome.isSuccess());
        assertEquals("primary", outcome.getSucceedingSourceType());
        assertTrue(outcome.getFailures().isEmpty());
    }

    @Test
    public void failingSourceFallsThroughToTheNext() {
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(failing("primary"));
        registry.register(yielding("backup", oneStore()));

        FetchOutcome outcome = new StoreFetcher(registry)
                .fetch(cardType("primary", "backup"), env);

        assertTrue(outcome.isSuccess());
        assertEquals("backup", outcome.getSucceedingSourceType());
        // The reason the first source was skipped is retained for diagnosing rot.
        assertEquals(1, outcome.getFailures().size());
    }

    @Test
    public void sourceReturningNoStoresIsTreatedAsFailure() {
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(yielding("primary", Collections.<Store>emptyList()));
        registry.register(yielding("backup", oneStore()));

        FetchOutcome outcome = new StoreFetcher(registry)
                .fetch(cardType("primary", "backup"), env);

        // An endpoint that responds but yields nothing almost always means the format
        // moved, not that the card genuinely covers no shops.
        assertEquals("backup", outcome.getSucceedingSourceType());
    }

    @Test
    public void unknownSourceTypeIsSkippedNotFatal() {
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(yielding("backup", oneStore()));

        // "from_the_future" is what a newer catalog might publish; an older build must
        // step over it rather than failing the whole refresh.
        FetchOutcome outcome = new StoreFetcher(registry)
                .fetch(cardType("from_the_future", "backup"), env);

        assertTrue(outcome.isSuccess());
        assertEquals("backup", outcome.getSucceedingSourceType());
    }

    @Test
    public void allSourcesFailingReportsFailureWithReasons() {
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(failing("primary"));
        registry.register(failing("backup"));

        FetchOutcome outcome = new StoreFetcher(registry)
                .fetch(cardType("primary", "backup"), env);

        assertFalse(outcome.isSuccess());
        assertTrue(outcome.getStores().isEmpty());
        assertEquals(2, outcome.getFailures().size());
    }

    private static List<Store> nStores(int n) {
        List<Store> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Store("Store " + i, null, false));
        }
        return out;
    }

    @Test
    public void truncatedResultFallsThroughToTheNextSource() {
        ProviderRegistry registry = new ProviderRegistry();
        // A half-published file: parses fine, but holds a fraction of what it should.
        registry.register(yielding("primary", nStores(5)));
        registry.register(yielding("backup", nStores(1000)));

        FetchOutcome outcome = new StoreFetcher(registry)
                .fetch(cardType("primary", "backup"), env, 1000);

        assertEquals("backup", outcome.getSucceedingSourceType());
        assertEquals(1000, outcome.getStores().size());
    }

    @Test
    public void aModestShrinkIsStillAccepted() {
        ProviderRegistry registry = new ProviderRegistry();
        // Merchant lists genuinely do shrink; 800 of an expected 1000 is not damage.
        registry.register(yielding("primary", nStores(800)));

        FetchOutcome outcome = new StoreFetcher(registry)
                .fetch(cardType("primary"), env, 1000);

        assertEquals("primary", outcome.getSucceedingSourceType());
        assertEquals(800, outcome.getStores().size());
    }

    @Test
    public void whenEverySourceLooksSmallTheLargestIsAcceptedAnyway() {
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(yielding("primary", nStores(5)));
        registry.register(yielding("backup", nStores(20)));

        FetchOutcome outcome = new StoreFetcher(registry)
                .fetch(cardType("primary", "backup"), env, 1000);

        // If they all agree the list shrank, it shrank. Rejecting everything would strand
        // this card on stale data permanently.
        assertTrue(outcome.isSuccess());
        assertEquals("backup", outcome.getSucceedingSourceType());
        assertEquals(20, outcome.getStores().size());
    }

    @Test
    public void withNoExpectationAnyNonEmptyResultIsAccepted() {
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(yielding("primary", nStores(3)));

        FetchOutcome outcome = new StoreFetcher(registry).fetch(cardType("primary"), env, 0);
        assertEquals("primary", outcome.getSucceedingSourceType());
    }

    @Test
    public void cardTypeWithNoSourcesFailsCleanly() {
        FetchOutcome outcome = new StoreFetcher(new ProviderRegistry())
                .fetch(cardType(), env);
        assertFalse(outcome.isSuccess());
    }
}
