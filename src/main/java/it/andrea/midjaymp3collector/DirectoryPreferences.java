package it.andrea.midjaymp3collector;

import java.util.prefs.Preferences;

final class DirectoryPreferences {

    private static final String SOURCE_DIRECTORY_KEY = "sourceDirectory";
    private static final String DESTINATION_DIRECTORY_KEY = "destinationDirectory";

    private final Preferences preferences = Preferences.userNodeForPackage(MidjayMp3Collector.class);

    String sourceDirectory() {
        return preferences.get(SOURCE_DIRECTORY_KEY, "");
    }

    String destinationDirectory() {
        return preferences.get(DESTINATION_DIRECTORY_KEY, "");
    }

    void save(String sourceDirectory, String destinationDirectory) {
        preferences.put(SOURCE_DIRECTORY_KEY, sourceDirectory.trim());
        preferences.put(DESTINATION_DIRECTORY_KEY, destinationDirectory.trim());
    }
}
