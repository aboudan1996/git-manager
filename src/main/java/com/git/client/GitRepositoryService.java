package com.git.client;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

public final class GitRepositoryService implements AutoCloseable {
    private final Git git;
    private final GitDiffHistoryService diffHistoryService;
    private final GitConflictResolutionService conflictResolutionService;
    private final WindowsCredentialStore credentialStore = new WindowsCredentialStore();
    private final String credentialTarget;
    private CredentialsProvider credentialsProvider;
    private boolean credentialsPersisted;
    private final Deque<UndoSnapshot> redoSnapshots = new ArrayDeque<>();

    private GitRepositoryService(Git git) throws IOException {
        this.git = git;
        this.diffHistoryService = new GitDiffHistoryService(git.getRepository());
        this.conflictResolutionService = new GitConflictResolutionService(git);
        this.credentialTarget = credentialStore.targetFor(git.getRepository().getWorkTree().toPath());
        if (credentialStore.isSupported()) {
            WindowsCredentialStore.StoredCredentials stored = credentialStore.load(credentialTarget);
            if (stored != null) {
                try {
                    credentialsProvider = new UsernamePasswordCredentialsProvider(
                            stored.username(), stored.secret().clone());
                    credentialsPersisted = true;
                } finally {
                    java.util.Arrays.fill(stored.secret(), '\0');
                }
            }
        }
    }

    public static GitRepositoryService open(Path directory) throws IOException {
        return new GitRepositoryService(Git.open(directory.toFile()));
    }

    public static GitRepositoryService cloneRepository(String uri, Path destination)
            throws GitAPIException, IOException {
        return cloneRepository(uri, destination, "", new char[0]);
    }

    public static GitRepositoryService cloneRepository(String uri, Path destination,
                                                        String username, char[] password)
            throws GitAPIException, IOException {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("Enter a repository URL.");
        }
        CloneCommand command = Git.cloneRepository()
                .setURI(uri.trim())
                .setDirectory(destination.toFile());
        UsernamePasswordCredentialsProvider credentials = null;
        boolean hasUsername = username != null && !username.isBlank();
        boolean hasPassword = password != null && password.length > 0;
        if (hasUsername != hasPassword) {
            throw new IllegalArgumentException("Enter both a username and an access token.");
        }
        if (hasUsername) {
            credentials = new UsernamePasswordCredentialsProvider(username.trim(), password.clone());
            command.setCredentialsProvider(credentials);
        }
        try {
            Git clonedGit = command.call();
            GitRepositoryService service;
            try {
                service = new GitRepositoryService(clonedGit);
                if (hasUsername) {
                    service.setHttpsCredentials(username, password);
                }
            } catch (IOException | RuntimeException exception) {
                clonedGit.close();
                throw exception;
            }
            return service;
        } finally {
            if (credentials != null) {
                credentials.clear();
            }
        }
    }

    public Repository getRepository() {
        return git.getRepository();
    }

    public boolean setHttpsCredentials(String username, char[] password) throws IOException {
        if (username == null || username.isBlank() || password == null || password.length == 0) {
            throw new IllegalArgumentException("Enter both a username and a password or access token.");
        }
        String normalizedUsername = username.trim();
        boolean persistCredentials = credentialStore.isSupported();
        if (persistCredentials) {
            credentialStore.save(credentialTarget, normalizedUsername, password);
        }
        clearHttpsCredentials();
        credentialsProvider = new UsernamePasswordCredentialsProvider(normalizedUsername,
                password.clone());
        credentialsPersisted = persistCredentials;
        return persistCredentials;
    }

    public void clearHttpsCredentials() {
        if (credentialsProvider instanceof UsernamePasswordCredentialsProvider provider) {
            provider.clear();
        }
        credentialsProvider = null;
        credentialsPersisted = false;
    }

    public void addRemote(String name, String uri) throws GitAPIException {
        if (name == null || name.isBlank() || uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("Enter both a remote name and URL.");
        }
        try {
            git.remoteAdd().setName(name.trim()).setUri(new URIish(uri.trim())).call();
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid remote URL or name.", exception);
        }
    }

    public RepositoryState getState() throws GitAPIException, IOException {
        Status status = git.status().call();
        Set<String> staged = new LinkedHashSet<>();
        staged.addAll(status.getAdded());
        staged.addAll(status.getChanged());
        staged.addAll(status.getRemoved());

        Set<String> unstaged = new LinkedHashSet<>();
        unstaged.addAll(status.getModified());
        unstaged.addAll(status.getMissing());
        unstaged.addAll(status.getUntracked());

        String branch = git.getRepository().getBranch();
        return new RepositoryState(branch, List.copyOf(unstaged), List.copyOf(staged));
    }

    public String getDiff(String path, boolean staged) throws IOException {
        return diffHistoryService.getDiff(path, staged);
    }

    public String getCommitDiff(String commitId) throws IOException {
        return diffHistoryService.getCommitDiff(commitId);
    }

    public List<String> getCommitFiles(String commitId) throws IOException {
        return diffHistoryService.getCommitFiles(commitId);
    }

    public String getCommitFileDiff(String commitId, String path) throws IOException {
        return diffHistoryService.getCommitFileDiff(commitId, path);
    }

    public List<String> getConflictPaths() throws GitAPIException {
        return conflictResolutionService.getConflictPaths();
    }

    public ConflictContents getConflictContents(String path) throws IOException, GitAPIException {
        return conflictResolutionService.getConflictContents(path);
    }

    public void resolveConflict(String path, String contents) throws IOException, GitAPIException {
        conflictResolutionService.resolveConflict(path, contents);
    }

    public void stage(Collection<String> paths) throws GitAPIException {
        Set<String> missing = git.status().call().getMissing();
        for (String path : paths) {
            if (missing.contains(path)) {
                git.rm().setCached(true).addFilepattern(path).call();
            } else {
                git.add().addFilepattern(path).call();
            }
        }
    }

    public void stageAll() throws GitAPIException {
        git.add().addFilepattern(".").call();
        Set<String> missing = git.status().call().getMissing();
        for (String path : missing) {
            git.rm().setCached(true).addFilepattern(path).call();
        }
    }

    public void unstage(Collection<String> paths) throws GitAPIException, IOException {
        if (git.getRepository().resolve(Constants.HEAD) == null) {
            for (String path : paths) {
                git.rm().setCached(true).addFilepattern(path).call();
            }
        } else {
            for (String path : paths) {
                git.reset().addPath(path).call();
            }
        }
    }

    public void unstageAll() throws GitAPIException, IOException {
        if (git.getRepository().resolve(Constants.HEAD) == null) {
            for (String path : getState().staged()) {
                git.rm().setCached(true).addFilepattern(path).call();
            }
        } else {
            git.reset().call();
        }
    }

    public void commit(String message) throws GitAPIException {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Enter a commit message.");
        }
        Repository repository = git.getRepository();
        String authorName = repository.getConfig().getString("user", null, "name");
        String authorEmail = repository.getConfig().getString("user", null, "email");
        if (authorName == null || authorName.isBlank()
                || authorEmail == null || authorEmail.isBlank()) {
            throw new IllegalArgumentException(
                    "Configure your Git identity (user.name and user.email) before committing.");
        }
        Status status = git.status().call();
        if (status.getAdded().isEmpty() && status.getChanged().isEmpty()
                && status.getRemoved().isEmpty()) {
            throw new IllegalArgumentException("There are no staged changes to commit.");
        }
        PersonIdent gitIdentity = new PersonIdent(authorName.trim(), authorEmail.trim());
        git.commit()
                .setMessage(message.trim())
                .setAuthor(gitIdentity)
                .setCommitter(gitIdentity)
                .call();
    }

    public List<CommitEntry> getHistory() throws IOException {
        return diffHistoryService.getHistory();
    }

    public List<StashEntry> getStashes() throws GitAPIException {
        List<RevCommit> commits = git.stashList().call().stream().toList();
        List<StashEntry> stashes = new java.util.ArrayList<>(commits.size());
        for (int index = 0; index < commits.size(); index++) {
            RevCommit stash = commits.get(index);
            stashes.add(new StashEntry("stash@{" + index + "}", stash.getShortMessage(),
                    stash.getName().substring(0, 7),
                    Instant.ofEpochSecond(stash.getCommitTime()), stash.getName()));
        }
        return List.copyOf(stashes);
    }

    public List<String> getStashFiles(String stashId) throws IOException {
        return diffHistoryService.getStashFiles(stashId);
    }

    public String getStashDiff(String stashId) throws IOException {
        return diffHistoryService.getStashDiff(stashId);
    }

    public String getStashFileDiff(String stashId, String path) throws IOException {
        return diffHistoryService.getStashFileDiff(stashId, path);
    }

    public void writeWorkingTreePatch(Path destination) throws IOException {
        String patch = diffHistoryService.getWorkingTreePatch();
        if (patch.isBlank()) {
            throw new IllegalArgumentException("There are no local changes to export.");
        }
        Files.writeString(destination, patch, java.nio.charset.StandardCharsets.UTF_8);
    }

    public List<String> getLocalBranches() throws GitAPIException {
        return git.branchList().call().stream()
                .map(ref -> Repository.shortenRefName(ref.getName()))
                .sorted()
                .toList();
    }

    public List<String> getRemoteBranches() throws GitAPIException {
        return git.branchList()
                .setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.REMOTE)
                .call().stream()
                .filter(ref -> !ref.isSymbolic())
                .map(ref -> ref.getName().substring(Constants.R_REMOTES.length()))
                .sorted()
                .toList();
    }

    public void createBranch(String name) throws GitAPIException {
        createBranch(name, "HEAD");
    }

    public void createBranch(String name, String startPoint) throws GitAPIException {
        validateBranchName(name);
        if (startPoint == null || startPoint.isBlank()) {
            throw new IllegalArgumentException("Select a branch or commit to start from.");
        }
        git.branchCreate().setName(name.trim()).setStartPoint(startPoint.trim()).call();
        git.checkout().setName(name.trim()).call();
    }

    public void checkout(String branch) throws GitAPIException {
        git.checkout().setName(branch).call();
    }

    public void deleteLocalBranch(String branch) throws GitAPIException, IOException {
        if (branch == null || branch.isBlank()) {
            throw new IllegalArgumentException("Select a local branch to delete.");
        }
        String currentBranch = git.getRepository().getBranch();
        if (currentBranch.equals(branch)) {
            throw new IllegalArgumentException("The current branch cannot be deleted.");
        }
        git.branchDelete().setBranchNames(branch.trim()).setForce(false).call();
    }

    public String checkoutRemoteBranch(String remoteBranch) throws GitAPIException {
        if (remoteBranch == null) {
            throw new IllegalArgumentException("Select a remote tracking branch.");
        }
        int separator = remoteBranch.indexOf('/');
        if (separator <= 0 || separator == remoteBranch.length() - 1) {
            throw new IllegalArgumentException("The selected remote branch name is invalid.");
        }
        String localBranch = remoteBranch.substring(separator + 1);
        List<String> localBranches = getLocalBranches();
        if (localBranches.contains(localBranch)) {
            checkout(localBranch);
            return "Switched to existing local branch " + localBranch + ".";
        }
        git.checkout()
                .setCreateBranch(true)
                .setName(localBranch)
                .setStartPoint(Constants.R_REMOTES + remoteBranch)
                .setUpstreamMode(org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode.TRACK)
                .call();
        return "Created and switched to " + localBranch + " tracking " + remoteBranch + ".";
    }

    public String merge(String branch) throws GitAPIException {
        MergeResult result = git.merge().include(resolveBranch(branch)).call();
        return "Merge " + result.getMergeStatus().toString().toLowerCase().replace('_', ' ');
    }

    public String rebase(String branch) throws GitAPIException {
        RebaseResult result = git.rebase().setUpstream(resolveBranch(branch)).call();
        return "Rebase " + result.getStatus().toString().toLowerCase().replace('_', ' ');
    }

    public String continueRebase() throws GitAPIException {
        RebaseResult result = git.rebase()
                .setOperation(org.eclipse.jgit.api.RebaseCommand.Operation.CONTINUE).call();
        return "Rebase " + result.getStatus().toString().toLowerCase().replace('_', ' ');
    }

    public String abortRebase() throws GitAPIException {
        RebaseResult result = git.rebase()
                .setOperation(org.eclipse.jgit.api.RebaseCommand.Operation.ABORT).call();
        return "Rebase " + result.getStatus().toString().toLowerCase().replace('_', ' ');
    }

    public String cherryPick(String commitId) throws GitAPIException {
        ObjectId commit = resolveCommit(commitId);
        var result = git.cherryPick().include(commit).call();
        return "Cherry-pick " + result.getStatus().toString().toLowerCase().replace('_', ' ');
    }

    public String revert(String commitId) throws GitAPIException {
        ObjectId commit = resolveCommit(commitId);
        RevCommit result = git.revert().include(commit).call();
        return result == null ? "Revert produced conflicts."
                : "Revert created " + result.getShortMessage();
    }

    public String stash() throws GitAPIException {
        RevCommit stashed = git.stashCreate().setIncludeUntracked(true).call();
        return stashed == null ? "There are no changes to stash." : "Changes stashed successfully.";
    }

    public String undoWorkingChanges() throws GitAPIException, IOException {
        Status status = git.status().call();
        if (status.isClean()) {
            throw new IllegalArgumentException("There are no uncommitted changes to undo.");
        }
        if (!status.getConflicting().isEmpty()) {
            throw new IllegalArgumentException("Resolve merge conflicts before undoing changes.");
        }
        RevCommit snapshot = git.stashCreate().setIncludeUntracked(true).call();
        if (snapshot == null) {
            throw new IllegalStateException("Git could not save the current changes for undo.");
        }
        redoSnapshots.addLast(new UndoSnapshot(snapshot.getName(),
                git.getRepository().getBranch()));
        return "Changes are saved and removed from the working tree. Use Redo to restore them.";
    }

    public String redoWorkingChanges() throws GitAPIException, IOException {
        UndoSnapshot snapshot = redoSnapshots.peekLast();
        if (snapshot == null) {
            throw new IllegalArgumentException("There are no changes available to redo.");
        }
        if (!snapshot.branch().equals(git.getRepository().getBranch())) {
            throw new IllegalStateException("Switch back to branch " + snapshot.branch()
                    + " before redoing these changes.");
        }
        List<RevCommit> stashes = git.stashList().call().stream().toList();
        int stashIndex = -1;
        for (int index = 0; index < stashes.size(); index++) {
            if (stashes.get(index).getName().equals(snapshot.commitId())) {
                stashIndex = index;
                break;
            }
        }
        if (stashIndex < 0) {
            throw new IllegalStateException("The saved undo snapshot is no longer available.");
        }
        git.stashApply().setStashRef(snapshot.commitId()).call();
        git.stashDrop().setStashRef(stashIndex).call();
        redoSnapshots.removeLast();
        return "The saved uncommitted changes have been restored.";
    }

    public String applyLatestStash(boolean dropAfterApply) throws GitAPIException {
        List<RevCommit> stashes = git.stashList().call().stream().toList();
        if (stashes.isEmpty()) {
            throw new IllegalArgumentException("There are no stashed changes.");
        }
        var result = git.stashApply().setStashRef(stashes.get(0).getName()).call();
        if (dropAfterApply) {
            git.stashDrop().setStashRef(0).call();
        }
        return "Applied latest stash" + (result == null ? "" : ".");
    }

    public String fetch() throws GitAPIException {
        var command = git.fetch();
        if (credentialsProvider != null) {
            command.setCredentialsProvider(credentialsProvider);
        }
        command.call();
        return "Fetch completed.";
    }

    public String refreshRemoteBranches() throws GitAPIException {
        List<String> remotes = git.remoteList().call().stream()
                .map(RemoteConfig::getName)
                .toList();
        if (remotes.isEmpty()) {
            return "No remotes configured; showing local branches.";
        }
        for (String remote : remotes) {
            var command = git.fetch().setRemote(remote);
            if (credentialsProvider != null) {
                command.setCredentialsProvider(credentialsProvider);
            }
            command.call();
        }
        return "Remote branches refreshed.";
    }

    public String pull() throws GitAPIException {
        var command = git.pull();
        if (credentialsProvider != null) {
            command.setCredentialsProvider(credentialsProvider);
        }
        var result = command.call();
        if (result.getMergeResult() != null) {
            return "Pull " + result.getMergeResult().getMergeStatus()
                    .toString().toLowerCase().replace('_', ' ');
        }
        if (result.getRebaseResult() != null) {
            return "Pull " + result.getRebaseResult().getStatus()
                    .toString().toLowerCase().replace('_', ' ');
        }
        return result.isSuccessful() ? "Pull completed." : "Pull failed.";
    }

    public String push() throws GitAPIException, IOException {
        Repository repository = git.getRepository();
        String branch = repository.getBranch();
        String remote = repository.getConfig().getString("branch", branch, "remote");
        if (remote == null) {
            List<String> remotes = git.remoteList().call().stream()
                    .map(RemoteConfig::getName)
                    .toList();
            if (remotes.isEmpty()) {
                throw new IllegalArgumentException("Add a remote before pushing.");
            }
            remote = remotes.contains("origin") ? "origin" : remotes.get(0);
        }
        String destination = repository.getConfig().getString("branch", branch, "merge");
        if (destination == null) {
            destination = Constants.R_HEADS + branch;
        }
        var command = git.push()
                .setRemote(remote)
                .setRefSpecs(new RefSpec("HEAD:" + destination));
        if (credentialsProvider != null) {
            command.setCredentialsProvider(credentialsProvider);
        }
        var results = command.call();
        List<org.eclipse.jgit.transport.RemoteRefUpdate> remoteUpdates =
                StreamSupport.stream(results.spliterator(), false)
                .flatMap(result -> result.getRemoteUpdates().stream())
                .toList();
        String updates = remoteUpdates.stream()
                .map(update -> update.getRemoteName() + ": "
                        + update.getStatus().toString().toLowerCase().replace('_', ' '))
                .collect(java.util.stream.Collectors.joining("\n"));
        boolean pushSucceeded = remoteUpdates.stream().allMatch(update ->
                update.getStatus() == org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK
                        || update.getStatus()
                        == org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE);
        if (!pushSucceeded) {
            throw new IllegalStateException("Push did not complete successfully.\n" + updates);
        }
        repository.getConfig().setString("branch", branch, "remote", remote);
        repository.getConfig().setString("branch", branch, "merge", destination);
        repository.getConfig().save();
        return updates.isBlank() ? "Push completed." : "Push completed.\n" + updates;
    }

    private ObjectId resolveBranch(String branch) throws GitAPIException {
        try {
            ObjectId objectId = git.getRepository().resolve(branch);
            if (objectId == null) {
                throw new IllegalArgumentException("Branch not found: " + branch);
            }
            return objectId;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not resolve branch: " + branch, exception);
        }
    }

    private ObjectId resolveCommit(String commitId) {
        try {
            ObjectId commit = git.getRepository().resolve(commitId);
            if (commit == null) {
                throw new IllegalArgumentException("The selected commit could not be found.");
            }
            return commit;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not resolve the selected commit.", exception);
        }
    }

    private void validateBranchName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Enter a branch name.");
        }
        if (Repository.isValidRefName(Constants.R_HEADS + name.trim())) {
            return;
        }
        throw new IllegalArgumentException("Invalid branch name: " + name);
    }

    @Override
    public void close() {
        clearHttpsCredentials();
        git.close();
    }

    public record RepositoryState(String branch, List<String> unstaged, List<String> staged) {
    }

    public record ConflictContents(String base, String ours, String theirs, String working) {
    }

    public record CommitEntry(String message, String shortId, String author, Instant date,
                              String objectId) {
    }

    public record StashEntry(String reference, String message, String shortId, Instant date,
                             String objectId) {
    }

    private record UndoSnapshot(String commitId, String branch) {
    }
}
