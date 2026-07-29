package com.mycards.data.source;

import com.mycards.data.catalog.model.SourceDef;

/**
 * Best-effort lookup of a card's remaining balance from a public gift-card link.
 *
 * <p>Contract: return {@code null} whenever the balance cannot be read <em>confidently</em>.
 * Returning a guess is actively harmful here — it would raise a false "unlogged transaction"
 * alert and push the user to record a purchase that never happened. Silence is the correct
 * failure mode; the manual spend log remains the source of truth.
 */
public interface BalanceSourceProvider {

    String type();

    /**
     * @param giftUrl the card's stored gift link, already decrypted
     * @return the remaining balance, or null when it could not be determined
     */
    Double fetchBalance(SourceDef def, String giftUrl, SourceEnv env) throws Exception;
}
