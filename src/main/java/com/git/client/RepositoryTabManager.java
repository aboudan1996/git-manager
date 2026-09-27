package com.git.client;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Owns repository-tab lifetimes and the matching Git service instances. */
final class RepositoryTabManager {
    private final TabPane tabs = new TabPane();
    private final Map<String, GitRepositoryService> repositories = new LinkedHashMap<>();
    private Consumer<Tab> selectionListener = ignored -> { };
    private Runnable closeListener = () -> { };

    RepositoryTabManager() {
        tabs.getSelectionModel().selectedItemProperty()
                .addListener((observable, previous, selected) -> selectionListener.accept(selected));
    }

    TabPane node() { return tabs; }
    List<Tab> tabs() { return List.copyOf(tabs.getTabs()); }
    Tab selected() { return tabs.getSelectionModel().getSelectedItem(); }
    String path(Tab tab) { return tab == null ? null : (String) tab.getProperties().get("repository-path"); }
    GitRepositoryService repository(String path) { return repositories.get(path); }
    List<GitRepositoryService> repositories() { return List.copyOf(repositories.values()); }
    boolean contains(String path) { return repositories.containsKey(path); }

    void onSelection(Consumer<Tab> listener) { selectionListener = listener; }
    void onClosed(Runnable listener) { closeListener = listener; }

    void add(Path directory, GitRepositoryService repository) {
        String path = normalize(directory);
        repositories.put(path, repository);
        Tab tab = new Tab(directory.getFileName() == null ? path : directory.getFileName().toString());
        tab.setClosable(true);
        tab.setTooltip(new Tooltip(path));
        tab.getProperties().put("repository-path", path);
        tab.setOnClosed(event -> {
            GitRepositoryService closed = repositories.remove(path);
            if (closed != null) closed.close();
            closeListener.run();
        });
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
    }

    void close(String path) {
        Tab tab = tabs.getTabs().stream().filter(candidate -> path.equals(path(candidate)))
                .findFirst().orElse(null);
        if (tab == null) return;
        GitRepositoryService closed = repositories.remove(path);
        if (closed != null) closed.close();
        tabs.getTabs().remove(tab);
        closeListener.run();
    }

    void select(String path) {
        String normalized = normalize(Path.of(path));
        tabs.getTabs().stream().filter(tab -> normalized.equals(path(tab)))
                .findFirst().ifPresent(tab -> tabs.getSelectionModel().select(tab));
    }

    List<String> paths() {
        return tabs.getTabs().stream().map(this::path).filter(path -> path != null).toList();
    }

    void setDisabled(boolean disabled) { tabs.setDisable(disabled); }

    void closeAll() {
        repositories.values().forEach(GitRepositoryService::close);
        repositories.clear();
    }

    static String normalize(Path path) { return path.toAbsolutePath().normalize().toString(); }
}
