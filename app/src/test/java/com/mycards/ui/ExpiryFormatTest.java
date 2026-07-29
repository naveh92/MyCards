package com.mycards.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

public class ExpiryFormatTest {

    @Test
    public void roundTripsMonthAndYear() {
        assertEquals("2027-03", Formats.displayToStored("03/27"));
        assertEquals("03/27", Formats.expiryToDisplay("2027-03"));
        assertEquals("2027-12", Formats.displayToStored("12/27"));
        assertEquals("12/27", Formats.expiryToDisplay("2027-12"));
    }

    @Test
    public void acceptsInputWithoutASlash() {
        // The field auto-inserts the slash, but a paste might not have one.
        assertEquals("2027-03", Formats.displayToStored("0327"));
    }

    @Test
    public void rejectsImpossibleMonths() {
        assertNull(Formats.displayToStored("13/27"));
        assertNull(Formats.displayToStored("00/27"));
        assertFalse(Formats.isValidExpiryDisplay("13/27"));
    }

    @Test
    public void rejectsIncompleteInput() {
        assertNull(Formats.displayToStored("3/2"));
        assertNull(Formats.displayToStored("03"));
        assertNull(Formats.displayToStored(""));
    }

    @Test
    public void blankIsValidBecauseExpiryIsOptional() {
        assertTrue(Formats.isValidExpiryDisplay(""));
        assertTrue(Formats.isValidExpiryDisplay(null));
        assertTrue(Formats.isValidExpiryDisplay("   "));
    }

    @Test
    public void expiryLastsToTheEndOfItsMonth() {
        // A card marked 03/27 is spendable throughout March, so it must not read as
        // expired on the 2nd of the month.
        Calendar endOfMarch = Calendar.getInstance();
        endOfMarch.setTimeInMillis(Formats.expiryEndMillis("2027-03"));

        assertEquals(2027, endOfMarch.get(Calendar.YEAR));
        assertEquals(Calendar.MARCH, endOfMarch.get(Calendar.MONTH));
        assertEquals(31, endOfMarch.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    public void handlesFebruaryInALeapYear() {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(Formats.expiryEndMillis("2028-02"));
        assertEquals(29, cal.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    public void toleratesAFullIsoDate() {
        // Older rows or a hand-edited value may still carry a day component.
        assertEquals("03/27", Formats.expiryToDisplay("2027-03-15"));
    }

    @Test
    public void missingExpiryNeverExpires() {
        assertEquals(Long.MAX_VALUE, Formats.expiryEndMillis(null));
        assertEquals(Long.MAX_VALUE, Formats.expiryEndMillis(""));
        assertEquals(Long.MAX_VALUE, Formats.daysUntil(null));
        assertEquals("", Formats.expiryToDisplay(null));
    }

    @Test
    public void pastExpiryReportsNegativeDays() {
        assertTrue(Formats.daysUntil("2020-01") < 0);
        assertTrue(Formats.daysUntil("2099-01") > 0);
    }
}
