package com.git.client;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextArea;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.nio.file.Path;
import java.util.List;

/**
 * Owns the repository history, changes, diff, conflict, and commit widgets.
 * Repository operations are supplied as callbacks so this view holds no Git service state.
 */
final class RepositoryWorkspaceView {
    interface Actions {
        void openRecent(String path);
        void stageSelected(List<String> paths);
        void unstageSelected(List<String> paths);
        void stageAll();
        void unstageAll();
        void commit(String message);
        void showWorkingDiff(String path, boolean staged);
        void showConflictDiff(String path);
        void resolveConflict(String path, String contents);
        void loadConflict(String path);
        void historySelected(GitRepositoryService.CommitEntry commit);
        void commitFileSelected(GitRepositoryService.CommitEntry commit, String path);
    }

    private static final DateTimeFormatter COMMIT_DATE =
            DateTimeFormatter.ofPattern("MMM d, yyyy  HH:mm").withZone(ZoneId.systemDefault());

    private final Stage owner;
    private final Actions actions;
    private final ListView<GitRepositoryService.CommitEntry> history = new ListView<>();
    private final ListView<String> recent = new ListView<>();
    private final ListView<String> committedFiles = new ListView<>();
    private final ListView<String> unstaged = new ListView<>();
    private final ListView<String> staged = new ListView<>();
    private final ListView<String> conflicts = new ListView<>();
    private final VBox diffLines = new VBox();
    private final VBox commitPane = new VBox(10);
    private final VBox conflictPane = new VBox(10);
    private final VBox committedPane = new VBox(10);
    private final HBox changeLists = new HBox(14);
    private final Label changesTitle = new Label("Changes");
    private final Label historyTitle = new Label("History");
    private final Label status = new Label("Open a local Git repository to get started.");
    private final javafx.scene.control.TextField search = new javafx.scene.control.TextField();
    private final TextArea commitMessage = new TextArea();
    private final Button stageButton = new Button("Stage selected");
    private final Button unstageButton = new Button("Unstage selected");
    private final Button commitButton = new Button("Commit changes");
    private List<GitRepositoryService.CommitEntry> allCommits = List.of();
    private boolean repositoryAvailable;
    private boolean operationAvailable = true;
    private VBox unstagedPane;
    private VBox stagedPane;
    private VBox repositoryWorkspace;

    RepositoryWorkspaceView(Stage owner, Actions actions) {
        this.owner = owner;
        this.actions = actions;
    }

    Node create() {
        historyTitle.getStyleClass().add("section-title");
        history.setPlaceholder(new Label("No commits yet"));
        recent.setPlaceholder(new Label("No recent repositories"));
        recent.getStyleClass().add("recent-repositories-list");
        recent.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(String path, boolean empty) {
                super.updateItem(path, empty);
                if (empty || path == null) {
                    setText(null); setGraphic(null); setTooltip(null); return;
                }
                Path repositoryPath = Path.of(path);
                Label name = new Label(repositoryPath.getFileName() == null
                        ? path : repositoryPath.getFileName().toString());
                name.getStyleClass().add("commit-summary");
                Label location = new Label(path);
                location.getStyleClass().add("commit-details");
                location.setWrapText(true);
                setGraphic(new VBox(4, name, location));
                setTooltip(new javafx.scene.control.Tooltip(path));
            }
        });
        recent.getSelectionModel().selectedItemProperty().addListener((o, old, path) -> {
            if (path != null) {
                recent.getSelectionModel().clearSelection();
                actions.openRecent(path);
            }
        });
        search.setPromptText("Search commits...");
        search.textProperty().addListener((o, old, query) -> filterHistory(query));
        history.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(GitRepositoryService.CommitEntry commit, boolean empty) {
                super.updateItem(commit, empty);
                if (empty || commit == null) { setText(null); setGraphic(null); return; }
                Label summary = new Label(commit.message());
                summary.getStyleClass().add("commit-summary");
                summary.setWrapText(true);
                Label details = new Label(commit.shortId() + "  ·  " + commit.author()
                        + "  ·  " + COMMIT_DATE.format(commit.date()));
                details.getStyleClass().add("commit-details");
                setGraphic(new VBox(5, summary, details));
            }
        });
        history.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && history.getSelectionModel().getSelectedItem() != null)
                history.getSelectionModel().clearSelection();
        });
        history.getSelectionModel().selectedItemProperty()
                .addListener((o, old, commit) -> actions.historySelected(commit));
        VBox historyPane = new VBox(12, historyTitle, history, search, recent);
        historyPane.setPadding(new Insets(22));
        historyPane.setPrefWidth(360);
        historyPane.setMinWidth(280);
        historyPane.getStyleClass().add("history-pane");
        VBox.setVgrow(history, Priority.ALWAYS);
        VBox.setVgrow(recent, Priority.ALWAYS);

        changesTitle.getStyleClass().add("section-title");
        status.getStyleClass().add("status-label");
        Region spacer = new Region();
        HBox changesHeader = new HBox(changesTitle, spacer, status);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        changesHeader.setAlignment(Pos.CENTER_LEFT);
        configureChangeLists();
        VBox diffPane = createDiffPane();
        VBox mainPane = createMainPane(changesHeader, diffPane);
        repositoryWorkspace = mainPane;
        HBox result = new HBox(historyPane, mainPane);
        HBox.setHgrow(mainPane, Priority.ALWAYS);
        setRepositoryAvailable(false);
        return result;
    }

    private void configureChangeLists() {
        configureChangeList(unstaged, "Working tree is clean");
        configureChangeList(staged, "No staged changes");
        configureChangeList(conflicts, "No conflicts");
        unstagedPane = createChangePane("Unstaged changes", "Stage all", stageButton, unstaged);
        stagedPane = createChangePane("Staged changes", "Unstage all", unstageButton, staged);
        Button resolveButton = new Button("Resolve selected");
        resolveButton.getStyleClass().add("text-button");
        resolveButton.disableProperty().bind(conflicts.getSelectionModel().selectedItemProperty().isNull());
        resolveButton.setOnAction(event -> actions.loadConflict(conflicts.getSelectionModel().getSelectedItem()));
        conflictPane.getChildren().setAll(sectionTitle("Conflicts"), conflicts, resolveButton);
        conflictPane.getStyleClass().add("change-pane");
        VBox.setVgrow(conflicts, Priority.ALWAYS);
        conflicts.getSelectionModel().selectedItemProperty()
                .addListener((o, old, path) -> actions.showConflictDiff(path));
        committedFiles.setPlaceholder(new Label("No files changed by this commit"));
        committedFiles.getStyleClass().add("change-list");
        committedFiles.setPrefHeight(82);
        committedFiles.setMinHeight(82);
        committedFiles.setMaxHeight(82);
        committedFiles.getSelectionModel().selectedItemProperty().addListener((o, old, path) -> {
            GitRepositoryService.CommitEntry selected = selectedCommit();
            if (path != null && selected != null) actions.commitFileSelected(selected, path);
        });
        committedPane.getChildren().setAll(sectionTitle("Committed changes"), committedFiles);
        committedPane.getStyleClass().add("change-pane");
        setVisibleManaged(committedPane, false);
        changeLists.getChildren().setAll(unstagedPane, stagedPane, conflictPane, committedPane);
        HBox.setHgrow(unstagedPane, Priority.ALWAYS);
        HBox.setHgrow(stagedPane, Priority.ALWAYS);
        HBox.setHgrow(conflictPane, Priority.ALWAYS);
        HBox.setHgrow(committedPane, Priority.ALWAYS);
    }

    private void configureChangeList(ListView<String> list, String placeholder) {
        list.setPlaceholder(new Label(placeholder));
        list.getStyleClass().add("change-list");
        list.setPrefHeight(82);
        list.setMinHeight(82);
        list.setMaxHeight(82);
    }

    private VBox createChangePane(String title, String bulkTitle, Button selectedButton,
                                  ListView<String> list) {
        Button bulk = new Button(bulkTitle);
        bulk.getStyleClass().add("text-button");
        bulk.setOnAction(event -> {
            if (list == unstaged) actions.stageAll(); else actions.unstageAll();
        });
        Label heading = sectionTitle(title);
        Region spacer = new Region();
        HBox row = new HBox(heading, spacer, bulk);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);
        selectedButton.getStyleClass().add("text-button");
        selectedButton.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        selectedButton.setOnAction(event -> {
            List<String> selected = List.copyOf(list.getSelectionModel().getSelectedItems());
            if (list == unstaged) actions.stageSelected(selected); else actions.unstageSelected(selected);
        });
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.getSelectionModel().selectedItemProperty().addListener((o, old, path) -> {
            if (path != null) {
                (list == unstaged ? staged : unstaged).getSelectionModel().clearSelection();
                actions.showWorkingDiff(path, list == staged);
            }
        });
        VBox pane = new VBox(10, row, list, selectedButton);
        pane.getStyleClass().add("change-pane");
        return pane;
    }

    private VBox createDiffPane() {
        diffLines.getStyleClass().add("diff-lines");
        ScrollPane scroll = new ScrollPane(diffLines);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("diff-scroll");
        VBox pane = new VBox(10, sectionTitle("Diff"), scroll);
        pane.getStyleClass().add("diff-pane");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return pane;
    }

    private VBox createMainPane(HBox changesHeader, VBox diffPane) {
        commitMessage.setPromptText("Describe the changes you're committing...");
        commitMessage.setPrefRowCount(3);
        commitMessage.setWrapText(true);
        commitMessage.textProperty().addListener((observable, previous, current) -> updateCommitEnabled());
        commitButton.setText("Commit changes");
        commitButton.setId("commitButton");
        commitButton.setOnAction(event -> actions.commit(commitMessage.getText()));
        HBox commitActions = new HBox(commitButton);
        commitActions.setAlignment(Pos.CENTER_RIGHT);
        commitPane.getChildren().setAll(sectionTitle("Commit"), commitMessage, commitActions);
        commitPane.getStyleClass().add("commit-pane");
        VBox main = new VBox(14, changesHeader, changeLists, diffPane, commitPane);
        main.setPadding(new Insets(22));
        VBox.setVgrow(diffPane, Priority.ALWAYS);
        return main;
    }

    void setRecentRepositories(List<String> paths) {
        recent.setItems(FXCollections.observableArrayList(paths));
    }

    void setRepositoryAvailable(boolean available) {
        repositoryAvailable = available;
        historyTitle.setText(available ? "History" : "Recent repositories");
        setVisibleManaged(history, available);
        setVisibleManaged(search, available);
        setVisibleManaged(recent, !available);
        if (repositoryWorkspace != null) setVisibleManaged(repositoryWorkspace, available);
        updateCommitEnabled();
    }

    void updateRepository(List<String> unstagedPaths, List<String> stagedPaths,
                          List<String> conflictPaths, List<GitRepositoryService.CommitEntry> commits,
                          String statusText) {
        unstaged.setItems(FXCollections.observableArrayList(unstagedPaths));
        staged.setItems(FXCollections.observableArrayList(stagedPaths));
        conflicts.setItems(FXCollections.observableArrayList(conflictPaths));
        setVisibleManaged(conflictPane, !conflictPaths.isEmpty());
        setVisibleManaged(commitPane, !stagedPaths.isEmpty());
        status.getStyleClass().setAll("status-label");
        status.setText(statusText);
        allCommits = List.copyOf(commits);
        filterHistory(search.getText());
        diffLines.getChildren().clear();
        updateCommitEnabled();
    }

    void clear() {
        unstaged.getItems().clear();
        staged.getItems().clear();
        conflicts.getItems().clear();
        history.getItems().clear();
        committedFiles.getItems().clear();
        allCommits = List.of();
        diffLines.getChildren().clear();
        commitMessage.clear();
        status.getStyleClass().setAll("status-label");
        status.setText("Open a repository to get started.");
        setVisibleManaged(commitPane, false);
        setVisibleManaged(conflictPane, false);
        showWorkingChanges();
        updateCommitEnabled();
    }

    void resetForRepository() {
        commitMessage.clear();
        search.clear();
        history.getSelectionModel().clearSelection();
        showWorkingChanges();
    }

    GitRepositoryService.CommitEntry selectedCommit() {
        return history.getSelectionModel().getSelectedItem();
    }

    void clearCommitMessage() { commitMessage.clear(); }
    void setCommitEnabled(boolean enabled) {
        operationAvailable = enabled;
        updateCommitEnabled();
    }

    void setOperationStatus(String text) {
        status.getStyleClass().setAll("status-label");
        if (text != null && (text.toLowerCase().contains("failed")
                || text.toLowerCase().contains("conflict"))) {
            status.getStyleClass().add("status-error");
        } else if (text != null && (text.toLowerCase().contains("successfully")
                || text.toLowerCase().contains("completed"))) {
            status.getStyleClass().add("status-success");
        }
        status.setText(text);
    }

    void showCommit(GitRepositoryService.CommitEntry commit, List<String> files, String diff) {
        changesTitle.setText("Committed changes");
        setVisibleManaged(unstagedPane, false);
        setVisibleManaged(stagedPane, false);
        setVisibleManaged(committedPane, true);
        committedFiles.getSelectionModel().clearSelection();
        committedFiles.setItems(FXCollections.observableArrayList(files));
        renderDiff("Commit " + commit.shortId() + " — " + commit.message() + "\n\n" + diff);
    }

    void showWorkingChanges() {
        changesTitle.setText("Changes");
        setVisibleManaged(unstagedPane, true);
        setVisibleManaged(stagedPane, true);
        setVisibleManaged(committedPane, false);
        committedFiles.getSelectionModel().clearSelection();
        diffLines.getChildren().clear();
    }

    void renderDiff(String diff) {
        diffLines.getChildren().clear();
        if (diff == null || diff.isEmpty()) {
            diffLines.getChildren().add(diffLine("No differences to display.", "diff-context"));
            return;
        }
        String[] lines = diff.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i == lines.length - 1 && line.isEmpty()) continue;
            String style = line.startsWith("+") && !line.startsWith("+++")
                    ? "diff-added" : line.startsWith("-") && !line.startsWith("---")
                    ? "diff-removed" : line.startsWith("@@") || line.startsWith("diff --git")
                    || line.startsWith("index ") || line.startsWith("---") || line.startsWith("+++")
                    ? "diff-header" : "diff-context";
            diffLines.getChildren().add(diffLine(line, style));
        }
    }

    void showConflictEditor(String path, GitRepositoryService.ConflictContents conflict) {
        TextArea base = conflictText("Base version", conflict.base());
        TextArea ours = conflictText("Current branch (ours)", conflict.ours());
        TextArea theirs = conflictText("Incoming branch (theirs)", conflict.theirs());
        TextArea resolution = conflictText("Resolved result (editable)", conflict.working());
        resolution.setEditable(true);
        resolution.getStyleClass().add("resolution-editor");
        GridPane versions = new GridPane();
        versions.setHgap(10); versions.setVgap(10);
        versions.addRow(0, sectionTitle("Base"), sectionTitle("Ours"));
        versions.addRow(1, base, ours);
        versions.addRow(2, sectionTitle("Theirs"), sectionTitle("Resolve"));
        versions.addRow(3, theirs, resolution);
        for (int column = 0; column < 2; column++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setPercentWidth(50); constraints.setHgrow(Priority.ALWAYS);
            versions.getColumnConstraints().add(constraints);
        }
        for (TextArea area : List.of(base, ours, theirs, resolution)) {
            GridPane.setHgrow(area, Priority.ALWAYS);
            GridPane.setVgrow(area, Priority.ALWAYS);
        }
        ScrollPane scroll = new ScrollPane(versions);
        scroll.setFitToWidth(true);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Resolve conflict");
        dialog.setHeaderText(path + " — edit the result, then mark it resolved");
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().getButtonTypes().addAll(
                new ButtonType("Mark resolved", ButtonBar.ButtonData.OK_DONE), ButtonType.CANCEL);
        dialog.getDialogPane().setPrefSize(900, 650);
        dialog.showAndWait().filter(button -> button.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                .ifPresent(button -> actions.resolveConflict(path, resolution.getText()));
    }

    private void filterHistory(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase();
        List<GitRepositoryService.CommitEntry> filtered = allCommits.stream()
                .filter(commit -> normalized.isEmpty()
                        || commit.message().toLowerCase().contains(normalized)
                        || commit.author().toLowerCase().contains(normalized)
                        || commit.shortId().toLowerCase().contains(normalized))
                .toList();
        history.setItems(FXCollections.observableArrayList(filtered));
    }

    private void updateCommitEnabled() {
        boolean canCommit = repositoryAvailable && operationAvailable
                && !staged.getItems().isEmpty() && conflicts.getItems().isEmpty()
                && !commitMessage.getText().isBlank();
        commitButton.setDisable(!canCommit);
    }

    private TextArea conflictText(String prompt, String contents) {
        TextArea text = new TextArea(contents == null ? "" : contents);
        text.setPromptText(prompt); text.setWrapText(false); text.setPrefRowCount(12); text.setEditable(false);
        return text;
    }

    private Label diffLine(String text, String style) {
        Label line = new Label(text == null ? "Unable to display diff." : text);
        line.getStyleClass().addAll("diff-line", style);
        line.setMaxWidth(Double.MAX_VALUE);
        line.setMinHeight(Region.USE_PREF_SIZE);
        line.setWrapText(true);
        return line;
    }

    private Label sectionTitle(String text) {
        Label title = new Label(text);
        title.getStyleClass().add("section-title");
        return title;
    }

    private void setVisibleManaged(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
