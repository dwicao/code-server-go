package dev.codeservergo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VersionsTest {

    @Test
    public void compareNamesIsNumericNotLexicographic() {
        assertEquals(1, Versions.compareNames("1.2.0", "1.1.10"));
        assertTrue(Versions.compareNames("1.1.10", "1.1.9") > 0);
        assertTrue(Versions.compareNames("2.0.0", "1.9.9") > 0);
        assertTrue(Versions.compareNames("1.1.0", "1.2.0") < 0);
        assertEquals(0, Versions.compareNames("1.2.0", "1.2.0"));
        assertEquals(0, Versions.compareNames("1.2", "1.2.0"));
    }

    @Test
    public void compareNamesIgnoresSuffixes() {
        assertEquals(0, Versions.compareNames("1.2.0-beta", "1.2.0"));
        assertTrue(Versions.compareNames("1.3.0", "1.2.0+build") > 0);
    }

    @Test
    public void updateAvailableUsesVersionCodeWhenPresent() {
        assertTrue(Versions.isUpdateAvailable(12, "1.2.0", 11, "1.1.6"));
        assertFalse(Versions.isUpdateAvailable(12, "1.2.0", 12, "1.1.6"));
        assertFalse(Versions.isUpdateAvailable(12, "1.2.0", 13, "1.1.6"));
    }

    @Test
    public void updateAvailableFallsBackToDottedNames() {
        assertTrue(Versions.isUpdateAvailable(0, "1.2.0", 0, "1.1.6"));
        assertFalse(Versions.isUpdateAvailable(0, "1.1.6", 0, "1.1.6"));
    }
}
