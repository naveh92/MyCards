package com.mycards.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The measure that orders matches inside the fuzzy band. */
public class EditDistanceTest {

    @Test
    public void identicalStringsAreZeroApart() {
        assertEquals(0, EditDistance.between("ארוקה", "ארוקה"));
        assertEquals(0, EditDistance.between("", ""));
    }

    @Test
    public void countsInsertionsDeletionsAndSubstitutions() {
        assertEquals(1, EditDistance.between("ארוקה", "אירוקה"));   // one inserted yod
        assertEquals(1, EditDistance.between("קרולינה", "כרולינה")); // one swapped letter
        assertEquals(2, EditDistance.between("ארוקה", "אירוכה"));   // both at once
    }

    @Test
    public void anEmptySideCostsTheWholeOtherSide() {
        assertEquals(5, EditDistance.between("", "ארוקה"));
        assertEquals(5, EditDistance.between("ארוקה", ""));
    }

    @Test
    public void isSymmetric() {
        assertEquals(EditDistance.between("ארוקה", "אאוריקה"),
                EditDistance.between("אאוריקה", "ארוקה"));
    }

    /**
     * The distinction the whole tiebreak exists to draw, and the one a length comparison
     * cannot: both candidates are exactly one character longer than the query.
     */
    @Test
    public void separatesSpellingsThatAreTheSameLength() {
        String typed = "ארוקה";
        int closer = EditDistance.between(typed, "אירוקה");
        int further = EditDistance.between(typed, "אירוכה");
        assertEquals("both candidates are the same length",
                "אירוקה".length(), "אירוכה".length());
        assertTrue("אירוקה is the closer spelling", closer < further);
    }
}
