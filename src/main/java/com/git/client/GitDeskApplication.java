package com.git.client;

import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** JavaFX bootstrap and coordinator for the open repository tabs and Git operations. */
public class GitDeskApplication extends Application {
    private final LogService logService = new LogService();
    private final RecentRepositoryStore recentStore = new RecentRepositoryStore();
    private final RepositoryTabManager repositoryTabs = new RepositoryTabManager();
    private final MenuButton repositoryLabel = new MenuButton("No repository open");
    private final Label branchLabel = new Label("No branch");
    private final MenuButton actionsButton = new MenuButton("Git actions");
    private final ProgressIndicator operationSpinner = new ProgressIndicator();
    private final Deque<RepositoryRequest> pendingRepositoryOpens = new ArrayDeque<>();
    private Stage stage;
    private GitRepositoryService repositoryService;
    private GitActionsMenu actionsMenu;
    private javafx.scene.control.Button openRepositoryButton;
    private javafx.scene.control.Button cloneRepositoryButton;
    private javafx.scene.control.Button refreshButton;
    private boolean operationRunning;
    private boolean actionsMenuBusy;
    private String preferredRepositoryPath = "";
    private RepositoryWorkspaceView activeWorkspace;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        logService.info("GitDesk application started.");
        logService.info("Application log file: " + logService.logFile().toAbsolutePath());
        activeWorkspace = new RepositoryWorkspaceView(stage, workspaceActions());
        actionsMenu = new GitActionsMenu(actionsButton, menuActions());
        repositoryTabs.onSelection(this::activateRepositoryTab);
        repositoryTabs.onClosed(() -> {
            persistOpenRepositories();
            refreshOpenRepositoryMenu();
            if (repositoryTabs.tabs().isEmpty()) recentStore.clearLastRepository();
            updateControls();
        });
        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setTop(createToolbar());
        root.setCenter(workspace().create());
        Scene scene = new Scene(root, 1180, 760);
        scene.getStylesheets().add(GitDeskApplication.class.getResource("styles.css").toExternalForm());
        stage.setTitle("GitDesk");
        stage.getIcons().add(new Image(
                GitDeskApplication.class.getResourceAsStream("gitdesk_icon.png")));
        stage.setMinWidth(900);
        stage.setMinHeight(620);
        stage.setScene(scene);
        stage.show();
        updateControls();
        workspace().setRepositoryAvailable(repositoryService != null);
        refreshRecentRepositoryList();
        configureActionsMenu();
        restoreRepositories();
    }

    private RepositoryWorkspaceView workspace() { return activeWorkspace; }

    private RepositoryWorkspaceView.Actions workspaceActions() {
        return new RepositoryWorkspaceView.Actions() {
            @Override public void openRecent(String path) { openRepository(Path.of(path)); }
            @Override public void stageSelected(List<String> paths) {
                runRepositoryAction("Stage selected changes", () -> repositoryService.stage(paths));
            }
            @Override public void unstageSelected(List<String> paths) {
                runRepositoryAction("Unstage selected changes", () -> repositoryService.unstage(paths));
            }
            @Override public void stageAll() {
                runRepositoryAction("Stage all changes", () -> repositoryService.stageAll());
            }
            @Override public void unstageAll() {
                runRepositoryAction("Unstage all changes", () -> repositoryService.unstageAll());
            }
            @Override public void commit(String message) {
                runRepositoryAction("Commit", () -> {
                    repositoryService.commit(message);
                    workspace().clearCommitMessage();
                });
            }
            @Override public void showWorkingDiff(String path, boolean staged) {
                showDiff(path, staged);
            }
            @Override public void showConflictDiff(String path) { updateConflictDiff(path); }
            @Override public void resolveConflict(String path, String contents) {
                runRepositoryAction("Resolve conflict", () -> repositoryService.resolveConflict(path, contents));
            }
            @Override public void loadConflict(String path) { openConflictEditor(path); }
            @Override public void historySelected(GitRepositoryService.CommitEntry commit) {
                showCommitDiff(commit);
            }
            @Override public void commitFileSelected(GitRepositoryService.CommitEntry commit, String path) {
                showCommitFileDiff(commit, path);
            }
        };
    }

    private GitActionsMenu.Actions menuActions() {
        return new GitActionsMenu.Actions() {
            @Override public Stage owner() { return stage; }
            @Override public GitRepositoryService repository() { return repositoryService; }
            @Override public GitRepositoryService.CommitEntry selectedCommit(String action) {
                GitRepositoryService.CommitEntry selected = workspace().selectedCommit();
                if (selected == null) showMessage(action, "Select a commit in the history first.");
                return selected;
            }
            @Override public void repositoryAction(String operation, GitActionsMenu.RepositoryAction action) {
                runRepositoryAction(operation, action::run);
            }
            @Override public void remoteAction(String operation, GitActionsMenu.RemoteAction action) {
                runRemoteAction(operation, action::run);
            }
            @Override public void setStatus(String text) { setStatusText(text); }
            @Override public void logInfo(String message) { logService.info(message); }
            @Override public void showError(String title, Throwable error) {
                GitDeskApplication.this.showError(title, error);
            }
            @Override public void showMessage(String title, String message) {
                GitDeskApplication.this.showMessage(title, message);
            }
            @Override public void clone(String url, String username, char[] secret, Path destination) {
                cloneRepository(url, username, secret, destination);
            }
            @Override public void openRecent(String path) { openRepository(Path.of(path)); }
        };
    }

    private HBox createToolbar() {
        Label appName = new Label("GITDESK");
        appName.getStyleClass().add("app-name");
        repositoryLabel.getStyleClass().addAll("repository-label", "repository-switcher");
        branchLabel.getStyleClass().addAll("branch-badge", "muted-badge");
        openRepositoryButton = new Button("Open repository");
        openRepositoryButton.getStyleClass().add("secondary-button");
        openRepositoryButton.setOnAction(event -> chooseRepository());
        Button addRepositoryButton = new Button("+");
        addRepositoryButton.getStyleClass().add("add-repository-button");
        addRepositoryButton.setTooltip(new Tooltip("Open another repository"));
        addRepositoryButton.setOnAction(event -> chooseRepository());
        cloneRepositoryButton = new Button("Clone");
        cloneRepositoryButton.getStyleClass().add("secondary-button");
        cloneRepositoryButton.setOnAction(event -> actionsMenu.cloneRepository());
        refreshButton = new Button("Refresh");
        refreshButton.getStyleClass().add("secondary-button");
        refreshButton.setOnAction(event -> refreshRepository());
        MenuButton menu = actionsButton;
        menu.getStyleClass().add("secondary-button");
        operationSpinner.setPrefSize(19, 19);
        operationSpinner.setMaxSize(19, 19);
        operationSpinner.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        operationSpinner.setAccessibleText("Git operation in progress");
        operationSpinner.setVisible(false);
        operationSpinner.setManaged(false);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox heading = new HBox(6, repositoryLabel, addRepositoryButton);
        heading.setAlignment(Pos.CENTER_LEFT);
        HBox toolbar = new HBox(14, appName, heading, spacer, operationSpinner, branchLabel, menu,
                cloneRepositoryButton, openRepositoryButton, refreshButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(16, 22, 16, 22));
        toolbar.getStyleClass().add("toolbar");
        return toolbar;
    }

    private void restoreRepositories() {
        preferredRepositoryPath = recentStore.lastRepository();
        for (String path : recentStore.openRepositories()) {
            pendingRepositoryOpens.addLast(new RepositoryRequest(Path.of(path), false));
        }
        if (pendingRepositoryOpens.isEmpty() && repositoryTabs.tabs().isEmpty()) {
            recentStore.recentRepositories().stream().findFirst()
                    .ifPresent(path -> pendingRepositoryOpens.addLast(
                            new RepositoryRequest(Path.of(path), false)));
        }
        openNextRepository();
    }

    private void activateRepositoryTab(Tab tab) {
        if (tab == null) {
            repositoryService = null;
            repositoryLabel.setText("No repository open");
            repositoryLabel.setTooltip(null);
            branchLabel.setText("No branch");
            branchLabel.getStyleClass().setAll("branch-badge", "muted-badge");
            workspace().clear();
            workspace().setRepositoryAvailable(false);
        } else {
            String path = repositoryTabs.path(tab);
            repositoryService = repositoryTabs.repository(path);
            if (repositoryService != null) {
                workspace().resetForRepository();
                recentStore.setLastRepository(path);
                refreshRepository();
                workspace().setRepositoryAvailable(true);
            }
        }
        configureActionsMenu();
        refreshOpenRepositoryMenu();
        if (tab != null) persistOpenRepositories();
        updateControls();
    }

    private void createRepositoryTab(Path directory, GitRepositoryService service) {
        String path = RepositoryTabManager.normalize(directory);
        if (repositoryTabs.contains(path)) {
            service.close();
            selectRepositoryTab(path);
            return;
        }
        repositoryTabs.add(directory, service);
        logService.info("Opened repository: " + path);
        recentStore.remember(directory);
        refreshRecentRepositoryList();
        configureActionsMenu();
        persistOpenRepositories();
        refreshOpenRepositoryMenu();
    }

    private void refreshOpenRepositoryMenu() {
        repositoryLabel.getItems().clear();
        for (Tab tab : repositoryTabs.tabs()) {
            String path = repositoryTabs.path(tab);
            MenuItem item = new MenuItem(tab.getText());
            if (tab == repositoryTabs.selected()) item.setText("✓  " + tab.getText());
            item.setOnAction(event -> selectRepositoryTab(path));
            repositoryLabel.getItems().add(item);
        }
        if (!repositoryTabs.tabs().isEmpty()) {
            if (!repositoryLabel.getItems().isEmpty())
                repositoryLabel.getItems().add(new javafx.scene.control.SeparatorMenuItem());
            MenuItem close = new MenuItem("Close current repository");
            close.setOnAction(event -> {
                Tab selected = repositoryTabs.selected();
                if (selected != null) closeRepository(repositoryTabs.path(selected));
            });
            repositoryLabel.getItems().add(close);
        }
        repositoryLabel.setDisable(repositoryTabs.tabs().isEmpty());
    }

    private void closeRepository(String path) {
        repositoryTabs.close(path);
    }

    private void selectRepositoryTab(String path) {
        repositoryTabs.select(path);
    }

    private void persistOpenRepositories() {
        recentStore.setOpenRepositories(repositoryTabs.paths());
    }

    private void configureActionsMenu() {
        if (actionsMenu == null) return;
        actionsMenu.configure(repositoryService != null, recentStore.recentRepositories());
        updateControls();
    }

    private void refreshRecentRepositoryList() {
        workspace().setRecentRepositories(recentStore.recentRepositories());
    }

    private void chooseRepository() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Open Git repository");
        File directory = chooser.showDialog(stage);
        if (directory != null) openRepository(directory.toPath());
    }

    private void openRepository(Path directory) { openRepository(directory, true); }

    private void openRepository(Path directory, boolean remember) {
        String path = RepositoryTabManager.normalize(directory);
        if (repositoryTabs.contains(path)) {
            selectRepositoryTab(path);
            if (remember) {
                recentStore.remember(directory);
                refreshRecentRepositoryList();
                configureActionsMenu();
            }
            return;
        }
        boolean alreadyQueued = pendingRepositoryOpens.stream().anyMatch(request ->
                RepositoryTabManager.normalize(request.directory()).equals(path));
        if (!alreadyQueued) pendingRepositoryOpens.addLast(new RepositoryRequest(directory, remember));
        openNextRepository();
    }

    private void openNextRepository() {
        if (operationRunning) return;
        RepositoryRequest request = pendingRepositoryOpens.pollFirst();
        if (request == null) {
            if (!preferredRepositoryPath.isBlank()) selectRepositoryTab(preferredRepositoryPath);
            return;
        }

        String path = RepositoryTabManager.normalize(request.directory());
        if (repositoryTabs.contains(path)) {
            selectRepositoryTab(path);
            openNextRepository();
            return;
        }

        operationRunning = true;
        setStatusText("Opening repository...");
        logService.info("Opening repository: " + path);
        updateControls();
        Task<GitRepositoryService> task = new Task<>() {
            @Override
            protected GitRepositoryService call() throws Exception {
                return GitRepositoryService.open(request.directory());
            }
        };
        task.setOnSucceeded(event -> {
            createRepositoryTab(request.directory(), task.getValue());
            if (request.remember()) {
                recentStore.remember(request.directory());
                refreshRecentRepositoryList();
            } else {
                recentStore.setLastRepository(path);
            }
            operationRunning = false;
            setStatusText("Repository opened successfully.");
            logService.info("Opened repository successfully: " + path);
            updateControls();
            openNextRepository();
        });
        task.setOnFailed(event -> {
            operationRunning = false;
            Throwable failure = task.getException();
            logService.error("Could not open repository at " + path, failure);
            setStatusText("Could not open repository: " + errorMessage(failure));
            updateControls();
            showError("Could not open repository", failure);
            openNextRepository();
        });
        Thread worker = new Thread(task, "git-open-repository");
        worker.setDaemon(true);
        worker.start();
    }

    private void refreshRepository() {
        if (repositoryService == null) { updateControls(); return; }
        try {
            GitRepositoryService.RepositoryState state = repositoryService.getState();
            Repository repository = repositoryService.getRepository();
            repositoryLabel.setText(repository.getWorkTree().getName());
            repositoryLabel.setTooltip(new Tooltip(repository.getWorkTree().getAbsolutePath()));
            branchLabel.setText(state.branch());
            branchLabel.getStyleClass().remove("muted-badge");
            List<String> conflicts = repositoryService.getConflictPaths();
            String status = state.unstaged().size() + " unstaged  ·  " + state.staged().size()
                    + " staged  ·  " + conflicts.size() + " conflicts";
            workspace().updateRepository(state.unstaged(), state.staged(), conflicts,
                    repositoryService.getHistory(), status);
            updateControls();
        } catch (IOException | GitAPIException exception) {
            logService.error("Could not refresh repository state.", exception);
            showError("Could not refresh repository", exception);
        }
    }

    private void runRepositoryAction(String operation, GitActionsMenu.RepositoryAction action) {
        if (repositoryService == null) return;
        logService.info(operation + " started.");
        setStatusText(operation + " in progress...");
        try {
            action.run();
            refreshRepository();
            setStatusText(operation + " completed successfully.");
            logService.info(operation + " completed successfully.");
        } catch (IOException | GitAPIException | IllegalArgumentException exception) {
            logService.error(operation + " failed.", exception);
            setStatusText(operation + " failed: " + errorMessage(exception));
            showError("Git operation failed", exception);
        }
    }

    private void runRemoteAction(String operation, GitActionsMenu.RemoteAction action) {
        if (repositoryService == null || actionsButton.isDisabled()) return;
        actionsMenuBusy = true;
        operationRunning = true;
        updateControls();
        logService.info(operation + " started.");
        setStatusText(operation + " in progress...");
        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception { return action.run(); }
        };
        task.setOnSucceeded(event -> {
            actionsMenuBusy = false;
            operationRunning = false;
            refreshRepository();
            String result = task.getValue();
            String detail = result == null ? "" : result.trim();
            boolean hasIssue = detail.toLowerCase().contains("failed")
                    || detail.toLowerCase().contains("conflict");
            if (hasIssue) {
                setStatusText(detail);
                logService.warning(operation + " finished with a non-success result: " + detail);
                if (detail.toLowerCase().contains("failed")) {
                    showError(operation + " failed", new IllegalStateException(detail));
                }
            } else {
                setStatusText(detail.isBlank()
                        ? operation + " completed successfully."
                        : operation + " completed successfully. " + detail);
                logService.info(operation + " completed successfully."
                        + (detail.isBlank() ? "" : " Result: " + detail));
            }
            openNextRepository();
        });
        task.setOnFailed(event -> {
            actionsMenuBusy = false;
            operationRunning = false;
            Throwable failure = task.getException();
            logService.error(operation + " failed.", failure);
            setStatusText(operation + " failed: " + errorMessage(failure));
            updateControls();
            showError(operation + " failed", failure);
            openNextRepository();
        });
        Thread worker = new Thread(task, "git-network-operation");
        worker.setDaemon(true);
        worker.start();
    }

    private void cloneRepository(String url, String username, char[] secret, Path destination) {
        operationRunning = true;
        logService.info("Clone started for destination " + destination.toAbsolutePath().normalize());
        setStatusText("Clone in progress...");
        updateControls();
        Task<GitRepositoryService> task = new Task<>() {
            @Override protected GitRepositoryService call() throws Exception {
                try {
                    return GitRepositoryService.cloneRepository(url, destination, username, secret);
                } finally {
                    java.util.Arrays.fill(secret, '\0');
                }
            }
        };
        task.setOnSucceeded(event -> {
            operationRunning = false;
            createRepositoryTab(destination, task.getValue());
            setStatusText("Repository cloned successfully.");
            logService.info("Clone completed successfully at "
                    + destination.toAbsolutePath().normalize());
            updateControls();
            openNextRepository();
        });
        task.setOnFailed(event -> {
            actionsMenuBusy = false;
            operationRunning = false;
            Throwable failure = task.getException();
            logService.error("Clone failed.", failure);
            setStatusText("Clone failed: " + errorMessage(failure));
            updateControls();
            showError("Clone failed", failure);
            openNextRepository();
        });
        Thread worker = new Thread(task, "git-clone-operation");
        worker.setDaemon(true);
        worker.start();
    }

    private void showDiff(String path, boolean staged) {
        if (repositoryService == null) return;
        try {
            workspace().renderDiff(repositoryService.getDiff(path, staged));
        } catch (IOException exception) {
            workspace().renderDiff(exception.getMessage());
        }
    }

    private void updateConflictDiff(String path) {
        if (path == null || repositoryService == null) return;
        try {
            GitRepositoryService.ConflictContents conflict = repositoryService.getConflictContents(path);
            workspace().renderDiff("Conflict in " + path + "\n"
                    + "Review the base, ours, and theirs versions with Resolve selected.\n\n"
                    + conflict.working());
        } catch (IOException | GitAPIException exception) {
            workspace().renderDiff(exception.getMessage());
        }
    }

    private void openConflictEditor(String path) {
        if (path == null || repositoryService == null) return;
        try {
            workspace().showConflictEditor(path, repositoryService.getConflictContents(path));
        } catch (IOException | GitAPIException exception) {
            showError("Could not load conflict", exception);
        }
    }

    private void showCommitDiff(GitRepositoryService.CommitEntry commit) {
        if (commit == null) { workspace().showWorkingChanges(); return; }
        if (repositoryService == null) return;
        try {
            workspace().showCommit(commit, repositoryService.getCommitFiles(commit.objectId()),
                    repositoryService.getCommitDiff(commit.objectId()));
        } catch (IOException exception) {
            workspace().renderDiff(exception.getMessage());
        }
    }

    private void showCommitFileDiff(GitRepositoryService.CommitEntry commit, String path) {
        if (path == null || repositoryService == null) return;
        try {
            workspace().renderDiff("Commit " + commit.shortId() + " — " + path + "\n\n"
                    + repositoryService.getCommitFileDiff(commit.objectId(), path));
        } catch (IOException exception) {
            workspace().renderDiff(exception.getMessage());
        }
    }

    private void updateControls() {
        boolean hasRepository = repositoryService != null;
        operationSpinner.setVisible(operationRunning);
        operationSpinner.setManaged(operationRunning);
        actionsButton.setDisable(operationRunning || actionsMenuBusy);
        openRepositoryButton.setDisable(operationRunning);
        cloneRepositoryButton.setDisable(operationRunning);
        refreshButton.setDisable(!hasRepository || operationRunning);
        repositoryTabs.setDisabled(operationRunning);
        workspace().setCommitEnabled(hasRepository && !operationRunning);
    }

    private void setStatusText(String text) {
        // Status is part of the workspace's visible operation feedback.
        workspace().setOperationStatus(text);
    }

    private void showMessage(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(stage);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showError(String title, Throwable exception) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(stage);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(errorMessage(exception));
        alert.showAndWait();
    }

    private String errorMessage(Throwable exception) {
        if (exception == null) return "Unknown error.";
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    @Override
    public void stop() {
        persistOpenRepositories();
        repositoryTabs.closeAll();
        logService.info("GitDesk application stopped.");
        logService.close();
    }

    private record RepositoryRequest(Path directory, boolean remember) {
    }
}
