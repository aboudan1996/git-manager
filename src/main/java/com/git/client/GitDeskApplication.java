package com.git.client;

import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/** JavaFX bootstrap and coordinator for the open repository tabs and Git operations. */
public class GitDeskApplication extends Application {
    private final LogService logService = new LogService();
    private final RecentRepositoryStore recentStore = new RecentRepositoryStore();
    private final RepositoryTabManager repositoryTabs = new RepositoryTabManager();
    private final GitAccountService accountService = new GitAccountService();
    private final MenuButton repositoryLabel = new MenuButton("No repository open");
    private final Label branchLabel = new Label("No branch");
    private final MenuButton actionsButton = new MenuButton("Git actions");
    private final MenuButton accountButton = new MenuButton("Account");
    private final ProgressIndicator operationSpinner = new ProgressIndicator();
    private final Deque<RepositoryRequest> pendingRepositoryOpens = new ArrayDeque<>();
    private final List<Button> quickActionButtons = new ArrayList<>();
    private Stage stage;
    private GitRepositoryService repositoryService;
    private GitActionsMenu actionsMenu;
    private javafx.scene.control.Button openRepositoryButton;
    private javafx.scene.control.Button cloneRepositoryButton;
    private javafx.scene.control.Button refreshButton;
    private ContextMenu branchContextMenu;
    private boolean operationRunning;
    private boolean actionsMenuBusy;
    private String preferredRepositoryPath = "";
    private boolean restoreSelectionPending;
    private RepositoryWorkspaceView activeWorkspace;
    private GitAccountService.Account authenticatedAccount;
    private BorderPane root;
    private Node workspaceNode;
    private HBox toolbar;
    private Node loginView;
    private ComboBox<GitAccountService.Provider> loginProvider;
    private PasswordField loginToken;
    private Button loginButton;
    private Label loginStatus;
    private ProgressIndicator loginSpinner;
    private boolean repositoriesRestored;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        logService.info("GitPilot application started.");
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
        root = new BorderPane();
        root.getStyleClass().add("app-root");
        toolbar = createToolbar();
        workspaceNode = workspace().create();
        loginView = createLoginView();
        root.setCenter(loginView);
        var screenBounds = Screen.getPrimary().getVisualBounds();
        double initialWidth = Math.min(1440, screenBounds.getWidth() * 0.95);
        double initialHeight = screenBounds.getHeight() * 0.94;
        Scene scene = new Scene(root, initialWidth, initialHeight);
        scene.getStylesheets().add(GitDeskApplication.class.getResource("styles.css").toExternalForm());
        stage.setTitle("GitPilot");
        stage.getIcons().add(new Image(
                GitDeskApplication.class.getResourceAsStream("gitdesk_icon.png")));
        stage.setMinWidth(Math.min(900, screenBounds.getWidth() * 0.8));
        stage.setMinHeight(Math.min(620, screenBounds.getHeight() * 0.75));
        stage.setScene(scene);
        stage.show();
        stage.setX(screenBounds.getMinX() + (screenBounds.getWidth() - stage.getWidth()) / 2);
        stage.setY(screenBounds.getMinY() + (screenBounds.getHeight() - stage.getHeight()) / 2);
        updateControls();
        workspace().setRepositoryAvailable(repositoryService != null);
        refreshRecentRepositoryList();
        configureActionsMenu();
        restoreAuthentication();
    }

    private RepositoryWorkspaceView workspace() { return activeWorkspace; }

    private Node createLoginView() {
        Label brandMark = new Label("GP");
        brandMark.getStyleClass().add("login-brand-mark");
        Label brandName = new Label("GITPILOT");
        brandName.getStyleClass().add("login-brand-name");
        HBox brand = new HBox(10, brandMark, brandName);
        brand.setAlignment(Pos.CENTER_LEFT);

        Label heading = new Label("Welcome to GitPilot");
        heading.getStyleClass().add("login-heading");
        Label description = new Label(
                "Use a personal access token with profile-read permission. Private repositories "
                        + "also require repository access.");
        description.getStyleClass().add("login-description");
        description.setWrapText(true);

        loginProvider = new ComboBox<>();
        loginProvider.getItems().setAll(GitAccountService.Provider.values());
        loginProvider.setValue(GitAccountService.Provider.GITHUB);
        loginProvider.setConverter(new StringConverter<>() {
            @Override public String toString(GitAccountService.Provider provider) {
                return provider == null ? "" : provider.displayName();
            }
            @Override public GitAccountService.Provider fromString(String value) {
                return null;
            }
        });
        loginProvider.getStyleClass().add("login-input");
        loginProvider.setMaxWidth(Double.MAX_VALUE);

        Label providerLabel = new Label("Git provider");
        providerLabel.getStyleClass().add("login-field-label");
        loginToken = new PasswordField();
        loginToken.setPromptText("Paste your personal access token");
        loginToken.getStyleClass().add("login-input");
        Label tokenLabel = new Label("Personal access token");
        tokenLabel.getStyleClass().add("login-field-label");

        Hyperlink tokenHelp = new Hyperlink("Create a token");
        tokenHelp.getStyleClass().add("login-link");
        tokenHelp.setOnAction(event -> {
            GitAccountService.Provider selected = loginProvider.getValue();
            String url = selected == GitAccountService.Provider.GITLAB
                    ? "https://gitlab.com/-/user_settings/personal_access_tokens"
                    : "https://github.com/settings/personal-access-tokens";
            getHostServices().showDocument(url);
        });
        loginStatus = new Label("Your token is validated with the selected provider.");
        loginStatus.getStyleClass().add("login-status");
        loginStatus.setWrapText(true);
        loginSpinner = new ProgressIndicator();
        loginSpinner.setPrefSize(20, 20);
        loginSpinner.setMaxSize(20, 20);
        loginSpinner.setVisible(false);
        loginSpinner.setManaged(false);
        loginButton = new Button("Sign in securely");
        loginButton.getStyleClass().add("login-submit");
        loginButton.setMaxWidth(Double.MAX_VALUE);
        loginButton.setOnAction(event -> submitLogin());
        loginToken.setOnAction(event -> submitLogin());
        loginToken.textProperty().addListener((observable, old, value) ->
                loginButton.setDisable(value == null || value.isBlank()));
        loginButton.setDisable(true);

        HBox statusRow = new HBox(9, loginSpinner, loginStatus);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        VBox fields = new VBox(9, providerLabel, loginProvider, tokenLabel, loginToken);
        VBox.setMargin(tokenHelp, new Insets(0, 0, 3, 0));
        VBox card = new VBox(18, brand, heading, description, fields, tokenHelp,
                statusRow, loginButton);
        card.getStyleClass().add("login-card");
        card.setMaxWidth(440);
        StackPane page = new StackPane(card);
        page.getStyleClass().add("login-page");
        return page;
    }

    private void restoreAuthentication() {
        showLogin("Checking for a saved GitHub or GitLab session...");
        loginSpinner.setVisible(true);
        loginSpinner.setManaged(true);
        loginButton.setDisable(true);
        Task<GitAccountService.Account> task = new Task<>() {
            @Override protected GitAccountService.Account call() throws Exception {
                GitAccountService.Account saved = accountService.loadStoredAccount();
                if (saved == null) return null;
                try {
                    return accountService.verifyStored(saved);
                } catch (GitAccountService.InvalidTokenException invalidToken) {
                    accountService.signOut();
                    return null;
                } finally {
                    saved.close();
                }
            }
        };
        task.setOnSucceeded(event -> {
            if (task.getValue() == null) {
                showLogin("Sign in with GitHub or GitLab to continue.");
            } else {
                activateAuthenticatedAccount(task.getValue());
            }
        });
        task.setOnFailed(event ->
                showLogin("Could not verify the saved session: " + errorMessage(task.getException())));
        Thread worker = new Thread(task, "git-account-restore");
        worker.setDaemon(true);
        worker.start();
    }

    private void submitLogin() {
        if (loginButton.isDisabled() || loginProvider.getValue() == null) return;
        GitAccountService.Provider provider = loginProvider.getValue();
        char[] token = loginToken.getText().toCharArray();
        loginToken.clear();
        loginButton.setDisable(true);
        loginSpinner.setVisible(true);
        loginSpinner.setManaged(true);
        loginStatus.getStyleClass().setAll("login-status");
        loginStatus.setText("Verifying your account...");
        Task<GitAccountService.Account> task = new Task<>() {
            @Override protected GitAccountService.Account call() throws Exception {
                try {
                    return accountService.signIn(provider, token);
                } finally {
                    java.util.Arrays.fill(token, '\0');
                }
            }
        };
        task.setOnSucceeded(event -> activateAuthenticatedAccount(task.getValue()));
        task.setOnFailed(event -> {
            loginSpinner.setVisible(false);
            loginSpinner.setManaged(false);
            loginStatus.getStyleClass().setAll("login-status", "login-status-error");
            loginStatus.setText(errorMessage(task.getException()));
            loginButton.setDisable(loginToken.getText().isBlank());
        });
        Thread worker = new Thread(task, "git-account-login");
        worker.setDaemon(true);
        worker.start();
    }

    private void activateAuthenticatedAccount(GitAccountService.Account account) {
        GitAccountService.Account previous = authenticatedAccount;
        authenticatedAccount = account;
        repositoryTabs.repositories().forEach(service ->
                service.setAuthenticatedAccount(authenticatedAccount));
        if (previous != null) previous.close();
        accountButton.setText("@" + account.username());
        String sessionStorage = accountService.supportsPersistence()
                ? "Saved securely on this Windows account."
                : "Available for this session only.";
        accountButton.setTooltip(new Tooltip("Signed in to " + account.provider().displayName()
                + " as " + account.username() + ". " + sessionStorage));
        accountButton.setVisible(true);
        accountButton.setManaged(true);
        loginSpinner.setVisible(false);
        loginSpinner.setManaged(false);
        root.setTop(toolbar);
        root.setCenter(workspaceNode);
        workspace().setRepositoryAvailable(repositoryService != null);
        refreshRecentRepositoryList();
        configureActionsMenu();
        if (!repositoriesRestored) {
            repositoriesRestored = true;
            restoreRepositories();
        } else if (repositoryTabs.selected() != null) {
            refreshRepository();
        }
        updateControls();
    }

    private void showLogin(String message) {
        root.setTop(null);
        root.setCenter(loginView);
        loginSpinner.setVisible(false);
        loginSpinner.setManaged(false);
        loginStatus.getStyleClass().setAll("login-status");
        loginStatus.setText(message);
        loginButton.setDisable(loginToken.getText().isBlank());
    }

    private void signOut() {
        if (operationRunning || authenticatedAccount == null) return;
        operationRunning = true;
        updateControls();
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                accountService.signOut();
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            operationRunning = false;
            repositoryTabs.repositories().forEach(GitRepositoryService::clearAuthenticatedAccount);
            authenticatedAccount.close();
            authenticatedAccount = null;
            accountButton.setVisible(false);
            accountButton.setManaged(false);
            showLogin("You have signed out. Sign in again to continue.");
            updateControls();
        });
        task.setOnFailed(event -> {
            operationRunning = false;
            updateControls();
            showError("Could not sign out", task.getException());
        });
        Thread worker = new Thread(task, "git-account-sign-out");
        worker.setDaemon(true);
        worker.start();
    }

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
            @Override public void stashSelected(GitRepositoryService.StashEntry stash) {
                showStashDiff(stash);
            }
            @Override public void stashFileSelected(GitRepositoryService.StashEntry stash, String path) {
                showStashFileDiff(stash, path);
            }
            @Override public void applyStash(GitRepositoryService.StashEntry stash,
                                             boolean dropAfterApply) {
                if (stash == null) return;
                String operation = dropAfterApply ? "Pop selected stash" : "Apply selected stash";
                runRemoteAction(operation,
                        () -> repositoryService.applyStash(stash.objectId(), dropAfterApply));
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
            @Override public void remoteAction(String operation, GitActionsMenu.RemoteAction action,
                                               Runnable onSuccess) {
                runRemoteAction(operation, action::run, onSuccess);
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
        Label appName = new Label("GITPILOT");
        appName.getStyleClass().add("app-name");
        repositoryLabel.getStyleClass().addAll("repository-label", "repository-switcher");
        repositoryLabel.setPrefHeight(34);
        repositoryLabel.setMinHeight(34);
        repositoryLabel.setMaxHeight(34);
        branchLabel.getStyleClass().addAll("branch-badge", "muted-badge");
        branchLabel.setTooltip(new Tooltip(
                "Click to search local and remote branches; right-click for branch actions"));
        branchLabel.setOnMouseClicked(event -> {
            if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY
                    && repositoryService != null && !operationRunning) {
                actionsMenu.switchBranch();
            }
        });
        MenuItem createBranchFromCurrent = new MenuItem("Create branch from current...");
        createBranchFromCurrent.setOnAction(event -> actionsMenu.createBranchFromCurrent());
        MenuItem searchBranches = new MenuItem("Search and switch branch...");
        searchBranches.setOnAction(event -> actionsMenu.switchBranch());
        branchContextMenu = new ContextMenu(createBranchFromCurrent,
                new SeparatorMenuItem(), searchBranches);
        branchLabel.setContextMenu(branchContextMenu);
        openRepositoryButton = new Button("Open repository");
        openRepositoryButton.getStyleClass().add("secondary-button");
        openRepositoryButton.setOnAction(event -> chooseRepository());
        StackPane addIcon = new StackPane(
                new Rectangle(14, 2.4),
                new Rectangle(2.4, 14));
        addIcon.getStyleClass().add("add-repository-icon");
        Button addRepositoryButton = new Button();
        addRepositoryButton.setGraphic(addIcon);
        addRepositoryButton.setAccessibleText("Open another repository");
        addRepositoryButton.getStyleClass().addAll("quick-action-button", "add-repository-button");
        addRepositoryButton.setPrefHeight(34);
        addRepositoryButton.setMinHeight(34);
        addRepositoryButton.setMaxHeight(34);
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
        accountButton.getStyleClass().add("secondary-button");
        accountButton.setTooltip(new Tooltip("Authenticated Git provider account"));
        accountButton.setVisible(false);
        accountButton.setManaged(false);
        MenuItem signOut = new MenuItem("Sign out");
        signOut.setOnAction(event -> signOut());
        accountButton.getItems().setAll(signOut);
        quickActionButtons.clear();
        HBox quickActions = new HBox(3,
                createQuickAction("↶", "Undo", "Save and clear uncommitted changes",
                        () -> runRemoteAction("Undo", () -> repositoryService.undoWorkingChanges())),
                createQuickAction("↷", "Redo", "Restore the last changes cleared by Undo",
                        () -> runRemoteAction("Redo", () -> repositoryService.redoWorkingChanges())),
                createQuickAction("↓", "Pull", "Pull changes from the upstream branch",
                        () -> runRemoteAction("Pull", () -> repositoryService.pull())),
                createQuickAction("↑", "Push", "Push the current branch",
                        () -> runRemoteAction("Push", () -> repositoryService.push())),
                createQuickAction("⎇", "Branch", "Search and switch branches",
                        () -> actionsMenu.switchBranch()),
                createQuickAction("▤", "Stash", "Stash working changes",
                        () -> runRemoteAction("Stash", () -> repositoryService.stash())),
                createQuickAction("⇩", "Pop", "Apply and remove the latest stash",
                        () -> runRemoteAction("Pop stash",
                                () -> repositoryService.applyLatestStash(true))),
                createQuickAction("⇧", "Patch", "Export staged, unstaged and untracked changes",
                        this::choosePatchDestination));
        quickActions.setAlignment(Pos.CENTER_LEFT);
        quickActions.getStyleClass().add("quick-actions");
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
        HBox toolbar = new HBox(10, appName, heading, quickActions, spacer,
                operationSpinner, branchLabel, menu, accountButton,
                cloneRepositoryButton, openRepositoryButton, refreshButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(16, 22, 16, 22));
        toolbar.getStyleClass().add("toolbar");
        return toolbar;
    }

    private Button createQuickAction(String icon, String title, String tooltip, Runnable action) {
        Label iconLabel = new Label(icon);
        iconLabel.getStyleClass().add("quick-action-icon");
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("quick-action-title");
        VBox graphic = new VBox(1, iconLabel, titleLabel);
        graphic.setAlignment(Pos.CENTER);
        Button button = new Button();
        button.setGraphic(graphic);
        button.setTooltip(new Tooltip(tooltip));
        button.setAccessibleText(title + ": " + tooltip);
        button.getStyleClass().add("quick-action-button");
        button.setOnAction(event -> action.run());
        quickActionButtons.add(button);
        return button;
    }

    private void restoreRepositories() {
        preferredRepositoryPath = recentStore.lastRepository();
        restoreSelectionPending = true;
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
            branchLabel.setDisable(true);
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
            if (restoreSelectionPending) {
                restoreSelectionPending = false;
                if (!preferredRepositoryPath.isBlank()) {
                    selectRepositoryTab(preferredRepositoryPath);
                }
            }
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
                GitRepositoryService service = GitRepositoryService.open(request.directory());
                if (authenticatedAccount != null) {
                    service.setAuthenticatedAccount(authenticatedAccount);
                }
                return service;
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
            branchLabel.setDisable(false);
            List<String> conflicts = repositoryService.getConflictPaths();
            String status = state.unstaged().size() + " unstaged  ·  " + state.staged().size()
                    + " staged  ·  " + conflicts.size() + " conflicts";
            workspace().updateRepository(state.unstaged(), state.staged(), conflicts,
                    repositoryService.getHistory(), repositoryService.getStashes(), status);
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
            boolean inspectConflicts = isConflictOperation(operation)
                    || "Resolve conflict".equals(operation);
            if (!inspectConflicts) {
                setStatusText(operation + " completed successfully.");
                logService.info(operation + " completed successfully.");
                return;
            }
            List<String> conflicts = repositoryService.getConflictPaths();
            if (conflicts.isEmpty()) {
                setStatusText(operation + " completed successfully.");
            } else {
                if (isConflictOperation(operation)) {
                    setStatusText(operation + " paused for conflict resolution.");
                }
                openConflictEditor(conflicts.get(0));
            }
            if (conflicts.isEmpty()) {
                logService.info(operation + " completed successfully.");
            } else {
                logService.warning(operation + " left " + conflicts.size()
                        + " file(s) to resolve.");
            }
        } catch (IOException | GitAPIException | IllegalArgumentException exception) {
            logService.error(operation + " failed.", exception);
            setStatusText(operation + " failed: " + errorMessage(exception));
            showError("Git operation failed", exception);
        }
    }

    private boolean isConflictOperation(String operation) {
        return operation.equalsIgnoreCase("Rebase onto")
                || operation.equalsIgnoreCase("Merge branch");
    }

    private void runRemoteAction(String operation, GitActionsMenu.RemoteAction action) {
        runRemoteAction(operation, action, null);
    }

    private void runRemoteAction(String operation, GitActionsMenu.RemoteAction action,
                                 Runnable onSuccess) {
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
                if (onSuccess != null) {
                    onSuccess.run();
                }
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
                    GitRepositoryService service =
                            GitRepositoryService.cloneRepository(url, destination, username, secret);
                    if (authenticatedAccount != null) {
                        service.setAuthenticatedAccount(authenticatedAccount);
                    }
                    return service;
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

    private void showStashDiff(GitRepositoryService.StashEntry stash) {
        if (stash == null) { workspace().showWorkingChanges(); return; }
        if (repositoryService == null) return;
        try {
            workspace().showStash(stash, repositoryService.getStashFiles(stash.objectId()),
                    repositoryService.getStashDiff(stash.objectId()));
        } catch (IOException exception) {
            workspace().renderDiff(exception.getMessage());
        }
    }

    private void showStashFileDiff(GitRepositoryService.StashEntry stash, String path) {
        if (path == null || repositoryService == null) return;
        try {
            workspace().renderDiff(stash.reference() + " — " + path + "\n\n"
                    + repositoryService.getStashFileDiff(stash.objectId(), path));
        } catch (IOException exception) {
            workspace().renderDiff(exception.getMessage());
        }
    }

    private void choosePatchDestination() {
        if (repositoryService == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export local changes as a patch");
        chooser.setInitialFileName("gitpilot-changes.patch");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Git patch files (*.patch)", "*.patch"));
        File selected = chooser.showSaveDialog(stage);
        if (selected != null) {
            Path destination = selected.toPath();
            if (!destination.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".patch")) {
                destination = destination.resolveSibling(destination.getFileName() + ".patch");
            }
            Path patchDestination = destination;
            runRepositoryAction("Create patch",
                    () -> repositoryService.writeWorkingTreePatch(patchDestination));
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
        branchLabel.setDisable(!hasRepository || operationRunning);
        quickActionButtons.forEach(button -> button.setDisable(!hasRepository || operationRunning));
        accountButton.setDisable(operationRunning);
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
        DialogStyler.apply(alert);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showError(String title, Throwable exception) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(stage);
        DialogStyler.apply(alert);
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
        if (authenticatedAccount != null) authenticatedAccount.close();
        if (loginToken != null) loginToken.clear();
        logService.info("GitPilot application stopped.");
        logService.close();
    }

    private record RepositoryRequest(Path directory, boolean remember) {
    }
}
