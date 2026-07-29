package com.mycards.search;

import java.util.Collections;
import java.util.List;

/**
 * A card type that matched a query, carrying <em>why</em> it matched.
 *
 * <p>The reason matters as much as the hit: standing at a till, "BuyMe All — accepted at
 * Zara" is actionable, whereas a bare card name leaves you guessing whether the app really
 * understood the question.
 */
public final class CardMatch {

    private final CardTypeIndex cardType;
    private final int score;
    private final boolean matchedByCardName;
    private final boolean matchedByCardProperName;
    private final List<Store> matchedStores;

    CardMatch(CardTypeIndex cardType, int score, boolean matchedByCardName,
              boolean matchedByCardProperName, List<Store> matchedStores) {
        this.cardType = cardType;
        this.score = score;
        this.matchedByCardName = matchedByCardName;
        this.matchedByCardProperName = matchedByCardProperName;
        this.matchedStores = matchedStores == null
                ? Collections.<Store>emptyList()
                : Collections.unmodifiableList(matchedStores);
    }

    public CardTypeIndex getCardType() {
        return cardType;
    }

    public String getCardTypeId() {
        return cardType.getCardTypeId();
    }

    public int getScore() {
        return score;
    }

    /** True when the query hit the card's name or any of its aliases. Drives ranking. */
    public boolean isMatchedByCardName() {
        return matchedByCardName;
    }

    /**
     * True when the query hit the card's actual name, not merely one of its aliases.
     *
     * <p>Narrower than {@link #isMatchedByCardName()} on purpose. Aliases carry issuer names
     * and marketing phrases, some of which are also shop names — so an alias hit is not
     * evidence that the user meant the card, whereas a name hit is.
     */
    public boolean isMatchedByCardProperName() {
        return matchedByCardProperName;
    }

    /** The merchants that matched, best first, capped at the engine's display limit. */
    public List<Store> getMatchedStores() {
        return matchedStores;
    }

    /** True when any matched merchant supports online redemption. */
    public boolean hasOnlineMatch() {
        for (Store s : matchedStores) {
            if (s.isOnlineRedeem()) {
                return true;
            }
        }
        return false;
    }
}
