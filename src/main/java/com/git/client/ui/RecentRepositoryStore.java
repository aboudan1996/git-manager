package com.git.client.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.prefs.Preferences;

/** Persists the recent, last-selected, and currently open repository paths. */
final class RecentRepositoryStore {
    private static final String LAST_REPOSITORY_KEY = "lastRepository";
    private static final String RECENT_REPOSITORIES_KEY = "recentRepositories";
    private static final String OPEN_REPOSITORIES_KEY = "openRepositories";
    private static final int MAX_RECENT_REPOSITORIES = 8;
    private final Preferences preferences = Preferences.userNodeForPackage(GitDeskApplication.class);

    List<String> recentRepositories() {
        String stored = preferences.get(RECENT_REPOSITORIES_KEY, "");
        if (stored.isBlank()) return List.of();
        return Arrays.stream(stored.split("\\R"))
                .filter(path -> !path.isBlank())
                .filter(path -> Files.isDirectory(Path.of(path)))
                .distinct()
                .limit(MAX_RECENT_REPOSITORIES)
                .toList();
    }

    List<String> openRepositories() {
        return existingPaths(preferences.get(OPEN_REPOSITORIES_KEY, ""));
    }

    String lastRepository() {
        return preferences.get(LAST_REPOSITORY_KEY, "");
    }

    void remember(Path path) {
        String normalized = normalize(path);
        List<String> recent = new ArrayList<>();
        recent.add(normalized);
        recentRepositories().stream().filter(existing -> !existing.equals(normalized))
                .forEach(recent::add);
        preferences.put(RECENT_REPOSITORIES_KEY, String.join(System.lineSeparator(),
                recent.stream().limit(MAX_RECENT_REPOSITORIES).toList()));
        setLastRepository(normalized);
    }

    void setLastRepository(String path) {
        preferences.put(LAST_REPOSITORY_KEY, path);
    }

    void clearLastRepository() {
        preferences.remove(LAST_REPOSITORY_KEY);
    }

    void setOpenRepositories(List<String> paths) {
        preferences.put(OPEN_REPOSITORIES_KEY, String.join(System.lineSeparator(), paths));
    }

    private List<String> existingPaths(String stored) {
        if (stored.isBlank()) return List.of();
        return Arrays.stream(stored.split("\\R"))
                .filter(path -> !path.isBlank())
                .filter(path -> Files.isDirectory(Path.of(path)))
                .toList();
    }

    private String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}
