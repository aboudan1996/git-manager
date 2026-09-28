package com.git.client.ui;

import com.git.client.git.ConflictResolutionDocument;
import com.git.client.git.RepositoryOperations;
import com.git.client.platform.ApplicationEvent;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
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
        void userEvent(ApplicationEvent event, String detail);
        void historySelected(RepositoryOperations.CommitEntry commit);
        void commitFileSelected(RepositoryOperations.CommitEntry commit, String path);
        void stashSelected(RepositoryOperations.StashEntry stash);
        void stashFileSelected(RepositoryOperations.StashEntry stash, String path);
        void applyStash(RepositoryOperations.StashEntry stash, boolean dropAfterApply);
    }

    private static final DateTimeFormatter COMMIT_DATE =
            DateTimeFormatter.ofPattern("MMM d, yyyy  HH:mm").withZone(ZoneId.systemDefault());

    private final Stage owner;
    private final Actions actions;
    private final ListView<RepositoryOperations.CommitEntry> history = new ListView<>();
    private final ListView<RepositoryOperations.StashEntry> stashes = new ListView<>();
    private final ListView<String> recent = new ListView<>();
    private final ListView<String> committedFiles = new ListView<>();
    private final ListView<String> stashedFiles = new ListView<>();
    private final ListView<String> unstaged = new ListView<>();
    private final ListView<String> staged = new ListView<>();
    private final ListView<String> conflicts = new ListView<>();
    private final VBox diffLines = new VBox();
    private final VBox commitPane = new VBox(10);
    private final VBox conflictPane = new VBox(10);
    private final VBox committedPane = new VBox(10);
    private final VBox stashedPane = new VBox(10);
    private final HBox changeLists = new HBox(14);
    private final Label changesTitle = new Label("Changes");
    private final Label historyTitle = new Label("History");
    private final Label status = new Label("Open a local Git repository to get started.");
    private final javafx.scene.control.TextField search = new javafx.scene.control.TextField();
    private final javafx.scene.control.TextField stashSearch = new javafx.scene.control.TextField();
    private final TabPane activityTabs = new TabPane();
    private final TextArea commitMessage = new TextArea();
    private final Button stageButton = new Button("Stage selected");
    private final Button unstageButton = new Button("Unstage selected");
    private final Button commitButton = new Button("Commit changes");
    private List<RepositoryOperations.CommitEntry> allCommits = List.of();
    private boolean repositoryAvailable;
    private boolean operationAvailable = true;
    private RepositoryOperations.CommitEntry pressedCommit;
    private boolean pressedCommitWasSelected;
    private RepositoryOperations.StashEntry pressedStash;
    private boolean pressedStashWasSelected;
    private List<RepositoryOperations.StashEntry> allStashes = List.of();
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
        search.getStyleClass().add("search-field");
        search.textProperty().addListener((o, old, query) -> {
            filterHistory(query);
            actions.userEvent(ApplicationEvent.COMMIT_SEARCH_UPDATED,
                    "queryLength=" + (query == null ? 0 : query.length()));
        });
        history.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(RepositoryOperations.CommitEntry commit, boolean empty) {
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
        history.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            pressedCommit = commitCellAt(event);
            pressedCommitWasSelected = pressedCommit != null
                    && pressedCommit.equals(history.getSelectionModel().getSelectedItem());
        });
        history.setOnMouseClicked(event -> {
            RepositoryOperations.CommitEntry clickedCommit = commitCellAt(event);
            if (pressedCommitWasSelected && pressedCommit != null
                    && pressedCommit.equals(clickedCommit)) {
                history.getSelectionModel().clearSelection();
            }
            pressedCommit = null;
            pressedCommitWasSelected = false;
        });
        history.getSelectionModel().selectedItemProperty()
                .addListener((o, old, commit) -> actions.historySelected(commit));
        configureStashes();
        VBox commitsContent = new VBox(10, history, search);
        VBox.setVgrow(history, Priority.ALWAYS);
        Tab commitsTab = new Tab("Commits", commitsContent);
        commitsTab.setClosable(false);
        VBox stashesContent = new VBox(10, stashes, stashSearch);
        VBox.setVgrow(stashes, Priority.ALWAYS);
        Tab stashesTab = new Tab("Stashes", stashesContent);
        stashesTab.setClosable(false);
        activityTabs.getTabs().setAll(commitsTab, stashesTab);
        activityTabs.getSelectionModel().selectedItemProperty().addListener((o, old, selected) -> {
            if (selected == commitsTab) {
                actions.historySelected(selectedCommit());
            } else if (selected == stashesTab) {
                actions.stashSelected(selectedStash());
            }
        });
        activityTabs.getStyleClass().add("activity-tabs");
        VBox historyPane = new VBox(12, historyTitle, activityTabs, recent);
        historyPane.setPadding(new Insets(22));
        historyPane.setPrefWidth(360);
        historyPane.setMinWidth(280);
        historyPane.getStyleClass().add("history-pane");
        VBox.setVgrow(activityTabs, Priority.ALWAYS);
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
                .addListener((o, old, path) -> {
                    if (path != null) actions.userEvent(ApplicationEvent.CONFLICT_SELECTED, "file");
                    actions.showConflictDiff(path);
                });
        committedFiles.setPlaceholder(new Label("No files changed by this commit"));
        committedFiles.getStyleClass().add("change-list");
        committedFiles.setPrefHeight(82);
        committedFiles.setMinHeight(82);
        committedFiles.setMaxHeight(82);
        committedFiles.getSelectionModel().selectedItemProperty().addListener((o, old, path) -> {
            RepositoryOperations.CommitEntry selected = selectedCommit();
            if (path != null && selected != null) {
                actions.userEvent(ApplicationEvent.COMMIT_FILE_SELECTED, selected.shortId());
                actions.commitFileSelected(selected, path);
            }
        });
        committedPane.getChildren().setAll(sectionTitle("Committed changes"), committedFiles);
        committedPane.getStyleClass().add("change-pane");
        setVisibleManaged(committedPane, false);
        stashedFiles.setPlaceholder(new Label("No files changed by this stash"));
        stashedFiles.getStyleClass().add("change-list");
        stashedFiles.setPrefHeight(82);
        stashedFiles.setMinHeight(82);
        stashedFiles.setMaxHeight(82);
        stashedFiles.getSelectionModel().selectedItemProperty().addListener((o, old, path) -> {
            RepositoryOperations.StashEntry selected = selectedStash();
            if (path != null && selected != null) {
                actions.userEvent(ApplicationEvent.STASH_FILE_SELECTED, selected.reference());
                actions.stashFileSelected(selected, path);
            }
        });
        stashedPane.getChildren().setAll(sectionTitle("Stashed changes"), stashedFiles);
        stashedPane.getStyleClass().add("change-pane");
        setVisibleManaged(stashedPane, false);
        changeLists.getChildren().setAll(unstagedPane, stagedPane, conflictPane,
                committedPane, stashedPane);
        HBox.setHgrow(unstagedPane, Priority.ALWAYS);
        HBox.setHgrow(stagedPane, Priority.ALWAYS);
        HBox.setHgrow(conflictPane, Priority.ALWAYS);
        HBox.setHgrow(committedPane, Priority.ALWAYS);
        HBox.setHgrow(stashedPane, Priority.ALWAYS);
    }

    private void configureStashes() {
        stashes.setPlaceholder(new Label("No stashes yet"));
        stashSearch.setPromptText("Search stashes...");
        stashSearch.getStyleClass().add("search-field");
        stashSearch.textProperty().addListener((o, old, query) -> {
            filterStashes(query);
            actions.userEvent(ApplicationEvent.STASH_SEARCH_UPDATED,
                    "queryLength=" + (query == null ? 0 : query.length()));
        });
        stashes.setCellFactory(list -> new ListCell<>() {
            private final MenuItem applyStash = new MenuItem("Apply selected stash");
            private final MenuItem popStash = new MenuItem("Pop selected stash");
            private final ContextMenu stashMenu = new ContextMenu(applyStash, popStash);

            {
                stashMenu.setOnShowing(event ->
                        actions.userEvent(ApplicationEvent.STASH_CONTEXT_MENU_OPENED, "Stash actions"));
                applyStash.setOnAction(event -> {
                    if (getItem() != null) actions.applyStash(getItem(), false);
                });
                popStash.setOnAction(event -> {
                    if (getItem() != null) actions.applyStash(getItem(), true);
                });
                setOnContextMenuRequested(event -> {
                    if (!isEmpty() && getItem() != null) {
                        list.getSelectionModel().select(getItem());
                    }
                });
            }

            @Override protected void updateItem(RepositoryOperations.StashEntry stash, boolean empty) {
                super.updateItem(stash, empty);
                if (empty || stash == null) {
                    setText(null);
                    setGraphic(null);
                    setContextMenu(null);
                    return;
                }
                setContextMenu(stashMenu);
                Label summary = new Label(stash.message());
                summary.getStyleClass().add("commit-summary");
                summary.setWrapText(true);
                Label details = new Label(stash.reference() + "  ·  " + stash.shortId()
                        + "  ·  " + COMMIT_DATE.format(stash.date()));
                details.getStyleClass().add("commit-details");
                setGraphic(new VBox(5, summary, details));
            }
        });
        stashes.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            pressedStash = stashCellAt(event);
            pressedStashWasSelected = pressedStash != null
                    && pressedStash.equals(stashes.getSelectionModel().getSelectedItem());
        });
        stashes.setOnMouseClicked(event -> {
            RepositoryOperations.StashEntry clickedStash = stashCellAt(event);
            if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY
                    && pressedStashWasSelected && pressedStash != null
                    && pressedStash.equals(clickedStash)) {
                stashes.getSelectionModel().clearSelection();
            }
            pressedStash = null;
            pressedStashWasSelected = false;
        });
        stashes.getSelectionModel().selectedItemProperty()
                .addListener((o, old, stash) -> actions.stashSelected(stash));
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
        historyTitle.setText(available ? "Activity" : "Recent repositories");
        setVisibleManaged(activityTabs, available);
        setVisibleManaged(recent, !available);
        if (repositoryWorkspace != null) setVisibleManaged(repositoryWorkspace, available);
        updateCommitEnabled();
    }

    void updateRepository(List<String> unstagedPaths, List<String> stagedPaths,
                          List<String> conflictPaths, List<RepositoryOperations.CommitEntry> commits,
                          List<RepositoryOperations.StashEntry> stashEntries,
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
        allStashes = List.copyOf(stashEntries);
        filterStashes(stashSearch.getText());
        diffLines.getChildren().clear();
        updateCommitEnabled();
    }

    void clear() {
        unstaged.getItems().clear();
        staged.getItems().clear();
        conflicts.getItems().clear();
        history.getItems().clear();
        stashes.getItems().clear();
        committedFiles.getItems().clear();
        stashedFiles.getItems().clear();
        allCommits = List.of();
        allStashes = List.of();
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
        stashSearch.clear();
        history.getSelectionModel().clearSelection();
        stashes.getSelectionModel().clearSelection();
        showWorkingChanges();
    }

    RepositoryOperations.CommitEntry selectedCommit() {
        return history.getSelectionModel().getSelectedItem();
    }

    RepositoryOperations.StashEntry selectedStash() {
        return stashes.getSelectionModel().getSelectedItem();
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

    void showCommit(RepositoryOperations.CommitEntry commit, List<String> files, String diff) {
        changesTitle.setText("Committed changes");
        setVisibleManaged(unstagedPane, false);
        setVisibleManaged(stagedPane, false);
        setVisibleManaged(committedPane, true);
        setVisibleManaged(stashedPane, false);
        committedFiles.getSelectionModel().clearSelection();
        committedFiles.setItems(FXCollections.observableArrayList(files));
        renderDiff("Commit " + commit.shortId() + " — " + commit.message() + "\n\n" + diff);
    }

    void showStash(RepositoryOperations.StashEntry stash, List<String> files, String diff) {
        changesTitle.setText("Stashed changes");
        setVisibleManaged(unstagedPane, false);
        setVisibleManaged(stagedPane, false);
        setVisibleManaged(committedPane, false);
        setVisibleManaged(stashedPane, true);
        stashedFiles.getSelectionModel().clearSelection();
        stashedFiles.setItems(FXCollections.observableArrayList(files));
        renderDiff(stash.reference() + " — " + stash.message() + "\n\n" + diff);
    }

    void showWorkingChanges() {
        changesTitle.setText("Changes");
        setVisibleManaged(unstagedPane, true);
        setVisibleManaged(stagedPane, true);
        setVisibleManaged(committedPane, false);
        setVisibleManaged(stashedPane, false);
        committedFiles.getSelectionModel().clearSelection();
        stashedFiles.getSelectionModel().clearSelection();
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

    void showConflictEditor(String path, RepositoryOperations.ConflictContents conflict) {
        ConflictResolutionDocument document = ConflictResolutionDocument.parse(conflict);
        VBox hunks = new VBox(12);
        hunks.getStyleClass().add("conflict-hunks");
        TextArea resolution = conflictText("Choose a version for each conflict block...",
                conflict.working());
        resolution.setEditable(true);
        resolution.getStyleClass().add("resolution-editor");
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        DialogStyler.apply(dialog, actions::userEvent);
        dialog.setTitle("Resolve conflict");
        dialog.setHeaderText(path + " — select changes for each conflict");
        ButtonType resolveType = new ButtonType("Mark resolved", ButtonBar.ButtonData.OK_DONE);
        Runnable updateResolution = () -> {
            boolean allSelected = document.hasSelections();
            resolution.setText(allSelected ? document.compose() : conflict.working());
            dialog.getDialogPane().lookupButton(resolveType).setDisable(!allSelected);
        };
        for (int index = 0; index < document.hunks().size(); index++) {
            int hunkIndex = index;
            ConflictResolutionDocument.Hunk hunk = document.hunks().get(index);
            VBox currentSide = conflictSide("Current branch", hunk.current(), "diff-removed");
            VBox incomingSide = conflictSide("Incoming branch", hunk.incoming(), "diff-added");
            HBox versions = new HBox(10, currentSide, incomingSide);
            HBox.setHgrow(currentSide, Priority.ALWAYS);
            HBox.setHgrow(incomingSide, Priority.ALWAYS);
            Button useCurrent = new Button("Use current");
            Button useIncoming = new Button("Use incoming");
            Button useBoth = new Button("Use both");
            useCurrent.getStyleClass().add("secondary-button");
            useIncoming.getStyleClass().add("secondary-button");
            useBoth.getStyleClass().add("secondary-button");
            useCurrent.setOnAction(event -> {
                document.select(hunkIndex, ConflictResolutionDocument.Choice.CURRENT);
                updateResolution.run();
            });
            useIncoming.setOnAction(event -> {
                document.select(hunkIndex, ConflictResolutionDocument.Choice.INCOMING);
                updateResolution.run();
            });
            useBoth.setOnAction(event -> {
                document.select(hunkIndex, ConflictResolutionDocument.Choice.BOTH);
                updateResolution.run();
            });
            HBox choices = new HBox(8, useCurrent, useIncoming, useBoth);
            choices.setAlignment(Pos.CENTER_RIGHT);
            VBox card = new VBox(10, sectionTitle("Conflict " + (index + 1)),
                    versions, choices);
            card.getStyleClass().add("conflict-hunk");
            hunks.getChildren().add(card);
        }
        ScrollPane hunkScroll = new ScrollPane(hunks);
        hunkScroll.setFitToWidth(true);
        hunkScroll.getStyleClass().add("diff-scroll");
        VBox resultPane = new VBox(8, sectionTitle("Resolved result (editable)"), resolution);
        resultPane.getStyleClass().add("conflict-result-pane");
        VBox content = new VBox(12, sectionTitle("Choose which side to keep for each conflict"),
                hunkScroll, resultPane);
        VBox.setVgrow(hunkScroll, Priority.ALWAYS);
        VBox.setVgrow(resolution, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(resolveType, ButtonType.CANCEL);
        double screenHeight = Screen.getScreensForRectangle(owner.getX(), owner.getY(),
                        owner.getWidth(), owner.getHeight()).stream()
                .findFirst()
                .orElse(Screen.getPrimary())
                .getVisualBounds()
                .getHeight();
        double dialogHeight = Math.min(680, screenHeight * 0.8);
        dialog.getDialogPane().setPrefSize(1080, dialogHeight);
        dialog.getDialogPane().setMaxHeight(dialogHeight);
        dialog.getDialogPane().lookupButton(resolveType).setDisable(true);
        dialog.showAndWait().filter(button -> button.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                .ifPresent(button -> actions.resolveConflict(path, resolution.getText()));
    }

    private VBox conflictSide(String title, String contents, String lineStyle) {
        VBox lines = new VBox();
        lines.getStyleClass().add("diff-lines");
        String[] contentLines = (contents == null ? "" : contents).split("\\R", -1);
        for (int index = 0; index < contentLines.length; index++) {
            if (index == contentLines.length - 1 && contentLines[index].isEmpty()) continue;
            lines.getChildren().add(diffLine(contentLines[index], lineStyle));
        }
        if (lines.getChildren().isEmpty()) {
            lines.getChildren().add(diffLine("(empty)", "diff-context"));
        }
        ScrollPane scroll = new ScrollPane(lines);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("diff-scroll");
        VBox side = new VBox(6, sectionTitle(title), scroll);
        side.getStyleClass().add("conflict-side");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return side;
    }

    private void filterHistory(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase();
        List<RepositoryOperations.CommitEntry> filtered = allCommits.stream()
                .filter(commit -> normalized.isEmpty()
                        || commit.message().toLowerCase().contains(normalized)
                        || commit.author().toLowerCase().contains(normalized)
                        || commit.shortId().toLowerCase().contains(normalized))
                .toList();
        history.setItems(FXCollections.observableArrayList(filtered));
    }

    private void filterStashes(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase();
        List<RepositoryOperations.StashEntry> filtered = allStashes.stream()
                .filter(stash -> normalized.isEmpty()
                        || stash.message().toLowerCase().contains(normalized)
                        || stash.reference().toLowerCase().contains(normalized)
                        || stash.shortId().toLowerCase().contains(normalized))
                .toList();
        stashes.setItems(FXCollections.observableArrayList(filtered));
    }

    private RepositoryOperations.CommitEntry commitCellAt(MouseEvent event) {
        Node target = event.getPickResult().getIntersectedNode();
        while (target != null && !(target instanceof ListCell<?>)) {
            target = target.getParent();
        }
        if (target instanceof ListCell<?> cell
                && cell.getListView() == history
                && cell.getItem() instanceof RepositoryOperations.CommitEntry commit) {
            return commit;
        }
        return null;
    }

    private RepositoryOperations.StashEntry stashCellAt(MouseEvent event) {
        Node target = event.getPickResult().getIntersectedNode();
        while (target != null && !(target instanceof ListCell<?>)) {
            target = target.getParent();
        }
        if (target instanceof ListCell<?> cell
                && cell.getListView() == stashes
                && cell.getItem() instanceof RepositoryOperations.StashEntry stash) {
            return stash;
        }
        return null;
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
