package com.akselglyholt.velocityLimboHandler.misc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseVersionCheckerTest {

    @Test
    void recognizesNewerNumericVersions() {
        assertTrue(ReleaseVersionChecker.isNewerVersion("v1.8.5", "1.8.4"));
        assertTrue(ReleaseVersionChecker.isNewerVersion("1.9", "1.8.99"));
        assertTrue(ReleaseVersionChecker.isNewerVersion("1.8.4.1", "1.8.4"));
    }

    @Test
    void doesNotReportEqualOrOlderVersions() {
        assertFalse(ReleaseVersionChecker.isNewerVersion("v1.8.4", "1.8.4"));
        assertFalse(ReleaseVersionChecker.isNewerVersion("1.8.3", "1.8.4"));
        assertFalse(ReleaseVersionChecker.isNewerVersion("1.8.4-rc.1", "1.8.4"));
    }

    @Test
    void treatsAStableReleaseAsNewerThanItsPrerelease() {
        assertTrue(ReleaseVersionChecker.isNewerVersion("1.8.4", "1.8.4-rc.1"));
    }

    @Test
    void rejectsMalformedVersions() {
        assertFalse(ReleaseVersionChecker.isNewerVersion("latest", "1.8.4"));
        assertFalse(ReleaseVersionChecker.isNewerVersion("1.8.5", "unknown"));
    }
}
