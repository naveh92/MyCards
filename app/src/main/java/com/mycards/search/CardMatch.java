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
    private final List<Store> matchedStores;

    CardMatch(CardTypeIndex cardType, int score, boolean matchedByCardName, List<Store> matchedStores) {
        this.cardType = cardType;
        this.score = score;
        this.matchedByCardName = matchedByCardName;
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

    /** True when the query hit the card's own name rather than one of its merchants. */
    public boolean isMatchedByCardName() {
        return matchedByCardName;
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
