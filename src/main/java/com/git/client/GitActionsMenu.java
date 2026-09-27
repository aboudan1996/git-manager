package com.git.client;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Builds Git action menus and owns their modal workflows; operations are delegated to the coordinator. */
final class GitActionsMenu {
    interface Actions {
        Stage owner();
        GitRepositoryService repository();
        GitRepositoryService.CommitEntry selectedCommit(String action);
        void repositoryAction(String operation, RepositoryAction action);
        void remoteAction(String operation, RemoteAction action);
        void setStatus(String text);
        void logInfo(String message);
        void showError(String title, Throwable error);
        void showMessage(String title, String message);
        void clone(String url, String username, char[] secret, Path destination);
        void openRecent(String path);
    }

    @FunctionalInterface interface RepositoryAction {
        void run() throws IOException, GitAPIException;
    }
    @FunctionalInterface interface BranchAction {
        String run(String branch) throws IOException, GitAPIException;
    }
    @FunctionalInterface interface RemoteAction {
        String run() throws Exception;
    }

    private final MenuButton menu;
    private final Actions actions;

    GitActionsMenu(MenuButton menu, Actions actions) {
        this.menu = menu;
        this.actions = actions;
    }

    void configure(boolean hasRepository, List<String> recentRepositories) {
        menu.getItems().clear();
        if (hasRepository) {
            menu.setText("Git actions");
            addAction("Fetch", () -> actions.remoteAction("Fetch", () -> actions.repository().fetch()));
            addAction("Pull", () -> actions.remoteAction("Pull", () -> actions.repository().pull()));
            addAction("Push", () -> actions.remoteAction("Push", () -> actions.repository().push()));
            menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
            addAction("Set HTTPS credentials...", this::setHttpsCredentials);
            addAction("Add remote...", this::addRemote);
            addAction("Create branch...", this::createBranch);
            addAction("Switch branch...", this::checkoutBranch);
            addRecent(recentRepositories);
            menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
            addAction("Merge branch...", () -> chooseBranch("Merge branch", actions.repository()::merge));
            addAction("Rebase onto...", () -> chooseBranch("Rebase onto", actions.repository()::rebase));
            addAction("Cherry-pick selected commit", this::cherryPickSelected);
            addAction("Revert selected commit", this::revertSelected);
            addAction("Continue rebase", () -> actions.repositoryAction("Continue rebase", () ->
                    actions.setStatus(actions.repository().continueRebase())));
            addAction("Abort rebase", () -> actions.repositoryAction("Abort rebase", () ->
                    actions.setStatus(actions.repository().abortRebase())));
            menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
            addAction("Stash changes", () -> actions.repositoryAction("Stash changes",
                    () -> actions.repository().stash()));
            addAction("Apply latest stash", () -> actions.repositoryAction("Apply latest stash", () ->
                    actions.setStatus(actions.repository().applyLatestStash(false))));
            addAction("Pop latest stash", () -> actions.repositoryAction("Pop latest stash", () ->
                    actions.setStatus(actions.repository().applyLatestStash(true))));
        } else {
            menu.setText("Recent repositories");
            addRecent(recentRepositories);
        }
    }

    private void addRecent(List<String> paths) {
        MenuItem header = new MenuItem("Recent repositories");
        header.getProperties().put("gitdesk-role", "recent-repositories-header");
        header.setDisable(true);
        menu.getItems().add(header);
        int index = menu.getItems().size();
        if (paths.isEmpty()) {
            MenuItem empty = new MenuItem("No recent repositories");
            empty.setDisable(true);
            empty.getProperties().put("gitdesk-recent", true);
            menu.getItems().add(empty);
        } else {
            for (String path : paths) {
                MenuItem item = new MenuItem(path);
                item.getProperties().put("gitdesk-recent", true);
                item.setOnAction(event -> actions.openRecent(path));
                menu.getItems().add(index++, item);
            }
        }
    }

    private void addAction(String title, Runnable action) {
        MenuItem item = new MenuItem(title);
        item.setOnAction(event -> action.run());
        menu.getItems().add(item);
    }

    private void setHttpsCredentials() {
        TextField username = new TextField();
        username.setPromptText("Git username");
        PasswordField password = new PasswordField();
        password.setPromptText("Password or personal access token");
        GridPane fields = new GridPane();
        fields.setHgap(10); fields.setVgap(10);
        fields.addRow(0, new Label("Username:"), username);
        fields.addRow(1, new Label("Password/token:"), password);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(actions.owner());
        dialog.setTitle("HTTPS credentials");
        dialog.setHeaderText("Credentials are stored in Windows Credential Manager, not Git config.");
        dialog.getDialogPane().setContent(fields);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(button -> {
            char[] secret = password.getText().toCharArray();
            try {
                boolean persisted = actions.repository().setHttpsCredentials(username.getText(), secret);
                String message = persisted
                        ? "HTTPS credentials saved securely for this repository."
                        : "HTTPS credentials configured for this session only.";
                actions.setStatus(message);
                actions.logInfo(message);
            } catch (IllegalArgumentException | IOException exception) {
                actions.showError("Could not configure credentials", exception);
            } finally {
                Arrays.fill(secret, '\0');
                password.clear();
            }
        });
    }

    private void createBranch() {
        try {
            List<String> startPoints = new ArrayList<>();
            startPoints.add("HEAD (current)");
            startPoints.addAll(actions.repository().getLocalBranches());
            actions.repository().getHistory().stream()
                    .map(commit -> commit.shortId() + " — " + commit.message()).forEach(startPoints::add);
            ChoiceDialog<String> startDialog = new ChoiceDialog<>("HEAD (current)", startPoints);
            startDialog.initOwner(actions.owner());
            startDialog.setTitle("Create branch");
            startDialog.setHeaderText("Choose where the new branch starts");
            startDialog.setContentText("Start from:");
            startDialog.showAndWait().ifPresent(startPoint -> {
                String revision = "HEAD (current)".equals(startPoint) ? "HEAD"
                        : startPoint.contains(" — ") ? startPoint.substring(0, startPoint.indexOf(" — "))
                        : startPoint;
                TextInputDialog nameDialog = new TextInputDialog();
                nameDialog.initOwner(actions.owner());
                nameDialog.setTitle("Create branch");
                nameDialog.setHeaderText("The new branch will start from " + startPoint);
                nameDialog.setContentText("Branch name:");
                nameDialog.showAndWait().ifPresent(name -> actions.repositoryAction("Create branch", () -> {
                    String actualRevision = revision;
                    if (startPoint.contains(" — ")) {
                        actualRevision = actions.repository().getHistory().stream()
                                .filter(commit -> commit.shortId().equals(revision))
                                .map(GitRepositoryService.CommitEntry::objectId)
                                .findFirst().orElse(revision);
                    }
                    actions.repository().createBranch(name, actualRevision);
                }));
            });
        } catch (IOException | GitAPIException exception) {
            actions.showError("Could not create branch", exception);
        }
    }

    private void addRemote() {
        TextInputDialog nameDialog = new TextInputDialog("origin");
        nameDialog.initOwner(actions.owner());
        nameDialog.setTitle("Add remote");
        nameDialog.setHeaderText("Add a named Git remote");
        nameDialog.setContentText("Remote name:");
        nameDialog.showAndWait().ifPresent(name -> {
            TextInputDialog urlDialog = new TextInputDialog();
            urlDialog.initOwner(actions.owner());
            urlDialog.setTitle("Add remote");
            urlDialog.setHeaderText("Enter the remote URL");
            urlDialog.setContentText("Remote URL:");
            urlDialog.showAndWait().ifPresent(url -> actions.repositoryAction("Add remote",
                    () -> actions.repository().addRemote(name, url)));
        });
    }

    void cloneRepository() {
        TextInputDialog urlDialog = new TextInputDialog();
        urlDialog.initOwner(actions.owner());
        urlDialog.setTitle("Clone repository");
        urlDialog.setHeaderText("Clone a remote Git repository");
        urlDialog.setContentText("HTTPS URL:");
        urlDialog.showAndWait().ifPresent(url -> {
            TextField username = new TextField();
            username.setPromptText("Git username");
            PasswordField token = new PasswordField();
            token.setPromptText("Personal access token");
            GridPane credentials = new GridPane();
            credentials.setHgap(10); credentials.setVgap(10);
            credentials.addRow(0, new Label("Username (optional):"), username);
            credentials.addRow(1, new Label("Token (optional):"), token);
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.initOwner(actions.owner());
            dialog.setTitle("Clone authentication");
            dialog.setHeaderText("Leave blank for public repositories.");
            dialog.getDialogPane().setContent(credentials);
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(button -> {
                String usernameValue = username.getText();
                char[] secret = token.getText().toCharArray();
                DirectoryChooser chooser = new DirectoryChooser();
                chooser.setTitle("Choose parent folder for the clone");
                File parent = chooser.showDialog(actions.owner());
                if (parent == null) { Arrays.fill(secret, '\0'); return; }
                File destination = new File(parent, repositoryNameFromUrl(url));
                if (destination.exists()) {
                    Arrays.fill(secret, '\0');
                    actions.showMessage("Clone repository", "The destination already exists:\n"
                            + destination.getAbsolutePath());
                    return;
                }
                actions.clone(url, usernameValue, secret, destination.toPath());
            });
        });
    }

    private String repositoryNameFromUrl(String url) {
        String normalized = url.trim().replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (name.endsWith(".git")) name = name.substring(0, name.length() - 4);
        return name.isBlank() ? "repository" : name;
    }

    private void checkoutBranch() {
        chooseBranchToSwitch();
    }

    private void chooseBranchToSwitch() {
        try {
            String current = actions.repository().getState().branch();
            List<String> branches = actions.repository().getLocalBranches().stream()
                    .filter(branch -> !branch.equals(current))
                    .toList();
            if (branches.isEmpty()) {
                actions.showMessage("Switch branch", "There are no other local branches.");
                return;
            }

            TextField search = new TextField();
            search.setPromptText("Search branches...");
            ListView<String> results = new ListView<>();
            results.getItems().setAll(branches);
            results.setPlaceholder(new Label("No branches match this search"));
            results.setCellFactory(list -> new ListCell<>() {
                @Override
                protected void updateItem(String branch, boolean empty) {
                    super.updateItem(branch, empty);
                    setText(empty ? null : branch);
                }
            });
            search.textProperty().addListener((observable, previous, query) -> {
                String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
                results.getItems().setAll(branches.stream()
                        .filter(branch -> branch.toLowerCase(Locale.ROOT).contains(normalized))
                        .toList());
                if (results.getItems().isEmpty()) {
                    results.getSelectionModel().clearSelection();
                } else {
                    results.getSelectionModel().selectFirst();
                }
            });
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.initOwner(actions.owner());
            dialog.setTitle("Switch branch");
            dialog.setHeaderText("Select or search for a local branch");
            dialog.getDialogPane().setContent(new javafx.scene.layout.VBox(10, search, results));
            dialog.getDialogPane().getButtonTypes().addAll(
                    ButtonType.OK, ButtonType.CANCEL);
            var okButton = dialog.getDialogPane().lookupButton(ButtonType.OK);
            okButton.disableProperty().bind(results.getSelectionModel().selectedItemProperty().isNull());
            results.getSelectionModel().selectFirst();
            search.setOnAction(event -> {
                if (results.getSelectionModel().getSelectedItem() != null) {
                    ((javafx.scene.control.Button) okButton).fire();
                }
            });
            results.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && results.getSelectionModel().getSelectedItem() != null) {
                    ((javafx.scene.control.Button) okButton).fire();
                }
            });
            results.setPrefSize(420, 320);
            javafx.scene.layout.VBox.setVgrow(results, Priority.ALWAYS);
            dialog.setOnShown(event -> search.requestFocus());
            dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(ignored -> {
                String branch = results.getSelectionModel().getSelectedItem();
                if (branch != null) {
                    actions.remoteAction("Switch branch", () -> {
                        actions.repository().checkout(branch);
                        return "Switched to " + branch;
                    });
                }
            });
        } catch (IOException | GitAPIException exception) {
            actions.showError("Could not list branches", exception);
        }
    }

    private void chooseBranch(String title, BranchAction action) {
        try {
            String current = actions.repository().getState().branch();
            List<String> branches = actions.repository().getLocalBranches().stream()
                    .filter(branch -> !branch.equals(current)).toList();
            if (branches.isEmpty()) { actions.showMessage(title, "There are no other local branches."); return; }
            ChoiceDialog<String> dialog = new ChoiceDialog<>(branches.get(0), branches);
            dialog.initOwner(actions.owner());
            dialog.setTitle(title); dialog.setHeaderText(title);
            dialog.setContentText("Select a local branch:");
            dialog.showAndWait().ifPresent(branch -> actions.repositoryAction(title,
                    () -> actions.setStatus(action.run(branch))));
        } catch (IOException | GitAPIException exception) {
            actions.showError("Could not list branches", exception);
        }
    }

    private void cherryPickSelected() {
        GitRepositoryService.CommitEntry selected = actions.selectedCommit("Cherry-pick");
        if (selected != null) actions.repositoryAction("Cherry-pick commit", () ->
                actions.setStatus(actions.repository().cherryPick(selected.objectId())));
    }

    private void revertSelected() {
        GitRepositoryService.CommitEntry selected = actions.selectedCommit("Revert");
        if (selected == null) return;
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.initOwner(actions.owner());
        confirmation.setTitle("Revert commit");
        confirmation.setHeaderText("Create a new commit that reverses this change?");
        confirmation.setContentText(selected.shortId() + "  " + selected.message());
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            actions.repositoryAction("Revert commit", () ->
                    actions.setStatus(actions.repository().revert(selected.objectId())));
        }
    }
}
