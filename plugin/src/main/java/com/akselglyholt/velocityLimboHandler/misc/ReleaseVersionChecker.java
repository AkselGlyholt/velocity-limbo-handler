package com.akselglyholt.velocityLimboHandler.misc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks GitHub's latest-release endpoint without delaying plugin startup. */
public final class ReleaseVersionChecker {
    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/akselglyholt/velocity-limbo-handler/releases/latest";
    private static final String RELEASES_URL =
            "https://github.com/akselglyholt/velocity-limbo-handler/releases/latest";
    private static final Pattern TAG_NAME_PATTERN =
            Pattern.compile("\\\"tag_name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final int TIMEOUT_MILLIS = 5_000;

    private ReleaseVersionChecker() {
    }

    public static void checkForUpdate(String currentVersion, Logger logger) {
        try {
            String latestVersion = fetchLatestVersion();
            if (latestVersion != null && isNewerVersion(latestVersion, currentVersion)) {
                logger.warning("A new VelocityLimboHandler version is available: " + latestVersion
                        + " (running " + currentVersion + "). Download it at " + RELEASES_URL);
            }
        } catch (IOException ignored) {
            // Update checks are optional and must not affect plugin startup.
        }
    }

    static boolean isNewerVersion(String candidateVersion, String currentVersion) {
        Version candidate = Version.parse(candidateVersion);
        Version current = Version.parse(currentVersion);
        return candidate != null && current != null && candidate.compareTo(current) > 0;
    }

    private static String fetchLatestVersion() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(LATEST_RELEASE_URL).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "VelocityLimboHandler-Version-Checker");
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);

        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String response = reader.lines().reduce("", String::concat);
                Matcher matcher = TAG_NAME_PATTERN.matcher(response);
                return matcher.find() ? matcher.group(1) : null;
            }
        } finally {
            connection.disconnect();
        }
    }

    private record Version(int[] components, boolean preRelease) implements Comparable<Version> {
        private static Version parse(String value) {
            if (value == null) {
                return null;
            }

            String normalized = value.trim().replaceFirst("^[vV]", "");
            if (normalized.isEmpty()) {
                return null;
            }

            String[] releaseAndQualifier = normalized.split("[-+]", 2);
            String[] parts = releaseAndQualifier[0].split("\\.");
            int[] components = new int[parts.length];
            try {
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].isEmpty()) {
                        return null;
                    }
                    components[i] = Integer.parseInt(parts[i]);
                }
            } catch (NumberFormatException exception) {
                return null;
            }

            return new Version(components, releaseAndQualifier.length > 1 && normalized.contains("-"));
        }

        @Override
        public int compareTo(Version other) {
            int length = Math.max(components.length, other.components.length);
            for (int i = 0; i < length; i++) {
                int left = i < components.length ? components[i] : 0;
                int right = i < other.components.length ? other.components[i] : 0;
                int comparison = Integer.compare(left, right);
                if (comparison != 0) {
                    return comparison;
                }
            }
            return Boolean.compare(other.preRelease, preRelease);
        }
    }
}
