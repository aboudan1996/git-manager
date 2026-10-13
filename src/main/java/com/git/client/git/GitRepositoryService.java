package com.git.client.git;

import com.git.client.platform.ApplicationLogger;
import com.git.client.platform.LogService;
import com.git.client.platform.WindowsCredentialStore;

import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.PatchApplyException;
import org.eclipse.jgit.api.errors.PatchFormatException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.Patch;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

/**
 * JGit adapter and repository facade. Keep JGit command details behind {@link RepositoryOperations}.
 */
public final class GitRepositoryService implements RepositoryOperations {
    private static final Pattern SENSITIVE_REMOTE_QUERY_PARAMETER = Pattern.compile(
            "(?i)[?&](?:access_token|private_token|token|password|secret|api_key|key)=");
    private final Git git;
    private final GitDiffHistoryService diffHistoryService;
    private final GitConflictResolutionService conflictResolutionService;
    private final WindowsCredentialStore credentialStore = new WindowsCredentialStore();
    private final String credentialTarget;
    private CredentialsProvider credentialsProvider;
    private AccountCredentialsProvider accountCredentialsProvider;
    private boolean credentialsPersisted;
    private final Deque<UndoSnapshot> redoSnapshots = new ArrayDeque<>();
    private static final ApplicationLogger logService = new LogService();

    private GitRepositoryService(Git git) throws IOException {
        this.git = git;
        this.diffHistoryService = new GitDiffHistoryService(git.getRepository());
        this.conflictResolutionService = new GitConflictResolutionService(git);
        this.credentialTarget = credentialStore.targetFor(git.getRepository().getWorkTree().toPath());
        if (credentialStore.isSupported()) {
            WindowsCredentialStore.StoredCredentials stored = credentialStore.load(credentialTarget);
            if (stored != null) {
                char[] savedSecret = stored.secret().clone();
                try {
                    credentialsProvider = new HttpsOnlyCredentialsProvider(
                            new UsernamePasswordCredentialsProvider(stored.username(), savedSecret));
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
        String safeUri = validateRemoteUrl(uri);
        boolean hasUsername = username != null && !username.isBlank();
        boolean hasPassword = password != null && password.length > 0;
        if (hasUsername != hasPassword) {
            throw new IllegalArgumentException("Enter both a username and an access token.");
        }
        if (hasUsername && !safeUri.regionMatches(true, 0, "https://", 0, 8)) {
            throw new IllegalArgumentException("Credentials may only be used with HTTPS remotes.");
        }
        CloneCommand command = Git.cloneRepository()
                .setURI(safeUri)
                .setDirectory(destination.toFile());
        UsernamePasswordCredentialsProvider credentials = null;
        if (hasUsername) {
            char[] passwordCopy = password.clone();
            credentials = new UsernamePasswordCredentialsProvider(username.trim(), passwordCopy);
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

    @Override
    public Path workTreePath() {
        return git.getRepository().getWorkTree().toPath();
    }

    @Override
    public Path gitDirectoryPath() {
        return git.getRepository().getDirectory().toPath();
    }

    @Override
    public String repositoryName() {
        return git.getRepository().getWorkTree().getName();
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
        clearAuthenticatedAccount();
        clearHttpsCredentials();
        char[] passwordCopy = password.clone();
        credentialsProvider = new HttpsOnlyCredentialsProvider(
                new UsernamePasswordCredentialsProvider(normalizedUsername, passwordCopy));
        credentialsPersisted = persistCredentials;
        return persistCredentials;
    }

    public void clearHttpsCredentials() {
        if (credentialsProvider instanceof HttpsOnlyCredentialsProvider provider) {
            provider.clear();
        }
        credentialsProvider = null;
        credentialsPersisted = false;
    }

    public void setAuthenticatedAccount(GitCredentials account) {
        clearAuthenticatedAccount();
        if (account == null) return;
        char[] token = account.token();
        try {
            accountCredentialsProvider = new AccountCredentialsProvider(
                    account, account.username(), token, credentialsProvider);
        } finally {
            java.util.Arrays.fill(token, '\0');
        }
    }

    public void clearAuthenticatedAccount() {
        if (accountCredentialsProvider != null) {
            accountCredentialsProvider.clear();
            accountCredentialsProvider = null;
        }
    }

    public void addRemote(String name, String uri) throws GitAPIException {
        if (name == null || name.isBlank() || uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("Enter both a remote name and URL.");
        }
        try {
            git.remoteAdd().setName(name.trim()).setUri(new URIish(validateRemoteUrl(uri))).call();
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid remote URL or name.", exception);
        }
    }

    static String validateRemoteUrl(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new IllegalArgumentException("Enter a repository URL.");
        }
        String normalized = remoteUrl.trim();
        try {
            URIish parsed = new URIish(normalized);
            String scheme = parsed.getScheme();
            boolean httpTransport = "http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme);
            if (parsed.getPass() != null || (httpTransport && parsed.getUser() != null)) {
                throw new IllegalArgumentException(
                        "Do not put credentials in a remote URL. Configure them in GitPilot instead.");
            }
            if (httpTransport && containsSensitiveQueryParameter(normalized)) {
                throw new IllegalArgumentException(
                        "Do not put tokens or passwords in remote URL parameters.");
            }
            return normalized;
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid remote URL.", exception);
        }
    }

    private void rejectEmbeddedCredentialsInRemotes() throws GitAPIException {
        for (RemoteConfig remote : git.remoteList().call()) {
            List<URIish> configuredUris = new ArrayList<>(remote.getURIs());
            configuredUris.addAll(remote.getPushURIs());
            for (URIish uri : configuredUris) {
                String scheme = uri.getScheme();
                boolean httpTransport = "http".equalsIgnoreCase(scheme)
                        || "https".equalsIgnoreCase(scheme);
                if (uri.getPass() != null || (httpTransport && uri.getUser() != null)) {
                    throw new IllegalArgumentException(
                            "This remote URL contains credentials. Remove them from the URL "
                                    + "and configure credentials in GitPilot.");
                }
                if (httpTransport && containsSensitiveQueryParameter(uri.toString())) {
                    throw new IllegalArgumentException(
                            "This remote URL contains a sensitive query parameter. Remove it "
                                    + "and configure credentials in GitPilot.");
                }
            }
        }
    }

    private static boolean containsSensitiveQueryParameter(String uri) {
        return SENSITIVE_REMOTE_QUERY_PARAMETER.matcher(uri).find();
    }

    public RepositoryOperations.RepositoryState getState() throws GitAPIException, IOException {
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
        return new RepositoryOperations.RepositoryState(
                branch, List.copyOf(unstaged), List.copyOf(staged));
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

    public RepositoryOperations.ConflictContents getConflictContents(String path)
            throws IOException, GitAPIException {
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

    public List<RepositoryOperations.CommitEntry> getHistory() throws IOException {
        return diffHistoryService.getHistory();
    }

    public List<RepositoryOperations.StashEntry> getStashes() throws GitAPIException {
        List<RevCommit> commits = git.stashList().call().stream().toList();
        List<RepositoryOperations.StashEntry> stashes = new java.util.ArrayList<>(commits.size());
        for (int index = 0; index < commits.size(); index++) {
            RevCommit stash = commits.get(index);
            stashes.add(new RepositoryOperations.StashEntry(
                    "stash@{" + index + "}", stash.getShortMessage(),
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
        return merge(branch, false);
    }

    public String merge(String branch, boolean noFastForward) throws GitAPIException {
        MergeCommand command = git.merge().include(resolveBranch(branch));
        if (noFastForward) {
            command.setFastForward(MergeCommand.FastForwardMode.NO_FF);
        }
        MergeResult result = command.call();
        return "Merge " + result.getMergeStatus().toString().toLowerCase().replace('_', ' ');
    }

    public String rebase(String branch) throws GitAPIException {
        RebaseResult result = git.rebase().setUpstream(resolveBranch(branch)).call();
        return "Rebase " + result.getStatus().toString().toLowerCase().replace('_', ' ');
    }

    public String continueRebase() throws GitAPIException {
        // Step 1: Check status to ensure all conflicts are resolved
        org.eclipse.jgit.api.Status statusResult = git.status().call();

        if (!statusResult.getConflicting().isEmpty()) {
            return "Cannot continue: Unresolved conflicts remain in: "
                    + String.join(", ", statusResult.getConflicting());
        }

        // Step 2: Proceed with the continue operation
        RebaseResult result = git.rebase()
                .setOperation(org.eclipse.jgit.api.RebaseCommand.Operation.CONTINUE)
                .call();

        RebaseResult.Status status = result.getStatus();

        // Step 3: Handle the final rebase state
        if (status == RebaseResult.Status.OK || status == RebaseResult.Status.FAST_FORWARD) {
            return "Rebase completed successfully!";
        } else if (status == RebaseResult.Status.STOPPED) {
            return "Rebase stopped again due to another conflict.";
        } else {
            return "Rebase status: " + status.toString().toLowerCase().replace('_', ' ');
        }
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
        return applyStash(stashes.get(0).getName(), dropAfterApply);
    }

    public String applyStash(String stashId, boolean dropAfterApply) throws GitAPIException {
        if (stashId == null || stashId.isBlank()) {
            throw new IllegalArgumentException("Select a stash to apply.");
        }
        List<RevCommit> stashes = git.stashList().call().stream().toList();
        int stashIndex = -1;
        for (int index = 0; index < stashes.size(); index++) {
            if (stashes.get(index).getName().equals(stashId)
                    || ("stash@{" + index + "}").equals(stashId)) {
                stashIndex = index;
                break;
            }
        }
        if (stashIndex < 0) {
            throw new IllegalArgumentException("The selected stash is no longer available.");
        }
        var result = git.stashApply().setStashRef(stashes.get(stashIndex).getName()).call();
        if (dropAfterApply) {
            git.stashDrop().setStashRef(stashIndex).call();
        }
        return (dropAfterApply ? "Popped " : "Applied ")
                + "stash@{" + stashIndex + "}" + (result == null ? "" : ".");
    }

    public String fetch() throws GitAPIException {
        rejectEmbeddedCredentialsInRemotes();
        var command = git.fetch();
        CredentialsProvider activeCredentials = activeCredentialsProvider();
        if (activeCredentials != null) {
            command.setCredentialsProvider(activeCredentials);
        }
        command.call();
        return "Fetch completed.";
    }

    public String refreshRemoteBranches() throws GitAPIException {
        rejectEmbeddedCredentialsInRemotes();
        List<String> remotes = git.remoteList().call().stream()
                .map(RemoteConfig::getName)
                .toList();
        if (remotes.isEmpty()) {
            return "No remotes configured; showing local branches.";
        }
        for (String remote : remotes) {
            var command = git.fetch().setRemote(remote);
            CredentialsProvider activeCredentials = activeCredentialsProvider();
            if (activeCredentials != null) {
                command.setCredentialsProvider(activeCredentials);
            }
            command.call();
        }
        return "Remote branches refreshed.";
    }

    public String pull() throws GitAPIException {
        rejectEmbeddedCredentialsInRemotes();
        var command = git.pull();
        CredentialsProvider activeCredentials = activeCredentialsProvider();
        if (activeCredentials != null) {
            command.setCredentialsProvider(activeCredentials);
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
        rejectEmbeddedCredentialsInRemotes();
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
        CredentialsProvider activeCredentials = activeCredentialsProvider();
        if (activeCredentials != null) {
            command.setCredentialsProvider(activeCredentials);
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

    private static final Pattern STASH_REF = Pattern.compile("stash@\\{(\\d+)}");

    private record StashItem(ObjectId id, PersonIdent who, String comment) { }

    @Override
    public String editStashMessage(String stashReference, String newMessage)
            throws IOException {
        String message = newMessage == null ? "" : newMessage.trim();
        if (message.isEmpty()) {
            throw new IllegalArgumentException("The stash message cannot be empty.");
        }
        int index = resolveStashIndex(stashReference);

        Repository repo = git.getRepository();
        ReflogReader reader = repo.getReflogReader(Constants.R_STASH);
        List<ReflogEntry> entries = reader == null ? List.of() : reader.getReverseEntries(); // du plus récent au plus ancien
        if (index >= entries.size()) {
            throw new IOException("Stash not found: " + stashReference);
        }

        // 1. Nouveau commit : même contenu, nouveau message
        ObjectId newId;
        try (RevWalk walk = new RevWalk(repo);
             ObjectInserter inserter = repo.newObjectInserter()) {
            RevCommit old = walk.parseCommit(entries.get(index).getNewId());
            if (message.equals(old.getFullMessage().trim())) {
                return "Stash message unchanged.";
            }
            CommitBuilder builder = new CommitBuilder();
            builder.setTreeId(old.getTree().getId());
            builder.setParentIds(old.getParents());
            builder.setAuthor(old.getAuthorIdent());
            builder.setCommitter(old.getCommitterIdent());
            builder.setMessage(message);
            newId = inserter.insert(builder);
            inserter.flush();
        }

        // 2. Pile d'origine et pile modifiée, de la plus ancienne à la plus récente
        List<StashItem> original = new ArrayList<>();
        List<StashItem> updated = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            ReflogEntry entry = entries.get(i);
            StashItem item = new StashItem(entry.getNewId(), entry.getWho(), entry.getComment());
            original.add(item);
            updated.add(i == index ? new StashItem(newId, entry.getWho(), message) : item);
        }

        // 3. Réécriture, avec retour arrière si quelque chose échoue
        try {
            rewriteStashStack(repo, updated);
        } catch (IOException | RuntimeException e) {
            try {
                rewriteStashStack(repo, original);
            } catch (IOException | RuntimeException restoreError) {
                e.addSuppressed(restoreError);
            }
            throw e;
        }
        return "Stash message updated.";
    }

    private void rewriteStashStack(Repository repo, List<StashItem> items) throws IOException {
        RefUpdate delete = repo.updateRef(Constants.R_STASH);
        delete.setForceUpdate(true);
        RefUpdate.Result deleted = delete.delete();
        if (deleted != RefUpdate.Result.FORCED && deleted != RefUpdate.Result.NO_CHANGE) {
            throw new IOException("Unable to rewrite the stash list: " + deleted);
        }

        for (StashItem item : items) {
            RefUpdate update = repo.updateRef(Constants.R_STASH);
            update.setNewObjectId(item.id());
            update.setRefLogIdent(item.who());
            update.setRefLogMessage(item.comment(), false);
            update.setForceRefLog(true);   // <-- manquant : crée le reflog de refs/stash
            update.setForceUpdate(true);
            RefUpdate.Result result = update.update();
            if (result != RefUpdate.Result.NEW
                    && result != RefUpdate.Result.FAST_FORWARD
                    && result != RefUpdate.Result.FORCED) {
                throw new IOException("Unable to restore stash " + item.id().abbreviate(7).name()
                        + ": " + result);
            }
        }
    }
    @Override
    public String applyPatch(Path patchFile) throws IOException, GitAPIException {
        if (patchFile == null || !Files.isRegularFile(patchFile)) {
            throw new IOException("Patch file does not exist: " + patchFile);
        }
        if (Files.size(patchFile) == 0) {
            throw new PatchException("The patch file is empty.");
        }

        // 1. Validation du format et liste des fichiers touchés
        Patch patch = new Patch();
        try (InputStream in = Files.newInputStream(patchFile)) {
            patch.parse(in);
        }
        if (!patch.getErrors().isEmpty()) {
            throw new PatchException("Invalid patch: " + patch.getErrors().get(0).getMessage());
        }
        if (patch.getFiles().isEmpty()) {
            throw new PatchException("This file is not a valid patch (no changes found).");
        }

        // 2. Recoupement avec les changements locaux
        Set<String> touched = new TreeSet<>();
        for (FileHeader fh : patch.getFiles()) {
            if (!DiffEntry.DEV_NULL.equals(fh.getOldPath())) touched.add(fh.getOldPath());
            if (!DiffEntry.DEV_NULL.equals(fh.getNewPath())) touched.add(fh.getNewPath());
        }

        Status status = git.status().call();
        Set<String> dirty = new HashSet<>();
        dirty.addAll(status.getModified());
        dirty.addAll(status.getChanged());
        dirty.addAll(status.getAdded());
        dirty.addAll(status.getRemoved());
        dirty.addAll(status.getMissing());
        dirty.addAll(status.getConflicting());
        dirty.addAll(status.getUntracked()); // un fichier créé par le patch peut déjà exister

        List<String> overlap = touched.stream().filter(dirty::contains).toList();
        if (!overlap.isEmpty()) {
            throw new PatchException("Cannot apply the patch: it touches files with local changes ("
                    + String.join(", ", overlap) + "). Commit or stash them first.");
        }

        // 3. Application, avec retour arrière si ça casse en cours de route
        try (InputStream in = Files.newInputStream(patchFile)) {
            ApplyResult result = git.apply().setPatch(in).call();
            return "Patch applied successfully. "
                    + result.getUpdatedFiles().size() + " file(s) updated.";
        } catch (PatchFormatException e) {
            rollbackPatch(patch);
            throw new PatchException("Invalid patch format: " + e.getMessage(), e);
        } catch (PatchApplyException e) {
            rollbackPatch(patch);
            throw new PatchException(
                    "The patch does not apply to the current state of the repository: "
                            + e.getMessage(), e);
        }
    }

    private int resolveStashIndex(String stashId) throws IOException {
        String ref = stashId == null ? "" : stashId.trim();
        if (ref.isEmpty()) {
            throw new IllegalArgumentException("No stash selected.");
        }

        // Format stash@{N}
        Matcher matcher = STASH_REF.matcher(ref);
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group(1));
        }

        // Format SHA (complet ou abrégé d'au moins 7 caractères)
        if (ref.length() >= 7) {
            ReflogReader reader = git.getRepository().getReflogReader(Constants.R_STASH);
            if (reader != null) {
                List<ReflogEntry> entries = reader.getReverseEntries(); // du plus récent au plus ancien
                for (int i = 0; i < entries.size(); i++) {
                    if (entries.get(i).getNewId().name().startsWith(ref)) {
                        return i;
                    }
                }
            }
        }
        throw new IllegalArgumentException("Stash not found: " + stashId);
    }

    @Override
    public String dropStash(String stashId) throws GitAPIException {
        int index;
        try {
            index = resolveStashIndex(stashId);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read the stash list: " + e.getMessage(), e);
        }

        try {
            git.stashDrop()
                    .setStashRef(index)
                    .call();
        } catch (JGitInternalException e) {
            throw new IllegalStateException("Unable to drop the stash: " + e.getMessage(), e);
        }
        return "Stash dropped.";
    }

    private void rollbackPatch(Patch patch) {
        Path workTree = git.getRepository().getWorkTree().toPath();
        for (FileHeader fh : patch.getFiles()) {
            try {
                switch (fh.getChangeType()) {
                    case ADD -> Files.deleteIfExists(workTree.resolve(fh.getNewPath()));
                    case RENAME, COPY -> {
                        Files.deleteIfExists(workTree.resolve(fh.getNewPath()));
                        if (fh.getChangeType() == DiffEntry.ChangeType.RENAME) {
                            git.checkout().addPath(fh.getOldPath()).call();
                        }
                    }
                    default -> git.checkout().addPath(fh.getOldPath()).call();
                }
            } catch (IOException | GitAPIException ex) {
                logService.warning("Rollback of " + fh.getNewPath() + " failed: " + ex.getMessage());
            }
        }
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

    private CredentialsProvider activeCredentialsProvider() {
        return accountCredentialsProvider == null ? credentialsProvider : accountCredentialsProvider;
    }

    @Override
    public void close() {
        clearAuthenticatedAccount();
        clearHttpsCredentials();
        git.close();
    }

    private static final class HttpsOnlyCredentialsProvider extends CredentialsProvider {
        private final UsernamePasswordCredentialsProvider delegate;

        private HttpsOnlyCredentialsProvider(UsernamePasswordCredentialsProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isInteractive() {
            return false;
        }

        @Override
        public boolean supports(CredentialItem... items) {
            return delegate.supports(items);
        }

        @Override
        public boolean get(URIish uri, CredentialItem... items)
                throws UnsupportedCredentialItem {
            return uri != null && "https".equalsIgnoreCase(uri.getScheme())
                    && delegate.get(uri, items);
        }

        private void clear() {
            delegate.clear();
        }
    }

    private static final class AccountCredentialsProvider extends CredentialsProvider {
        private final GitCredentials account;
        private final String username;
        private final char[] token;
        private final CredentialsProvider fallback;

        private AccountCredentialsProvider(GitCredentials account, String username,
                                          char[] token, CredentialsProvider fallback) {
            this.account = account;
            this.username = username;
            this.token = token.clone();
            this.fallback = fallback;
        }

        @Override
        public boolean isInteractive() {
            return false;
        }

        @Override
        public boolean supports(CredentialItem... items) {
            boolean accountItems = true;
            for (CredentialItem item : items) {
                if (!(item instanceof CredentialItem.Username)
                        && !(item instanceof CredentialItem.Password)) {
                    accountItems = false;
                    break;
                }
            }
            return accountItems || (fallback != null && fallback.supports(items));
        }

        @Override
        public boolean get(URIish uri, CredentialItem... items)
                throws UnsupportedCredentialItem {
            if (uri != null && "https".equalsIgnoreCase(uri.getScheme())
                    && account.supportsHost(uri.getHost(), uri.getPort())) {
                if (supportsAccountItems(items)) {
                    for (CredentialItem item : items) {
                        if (item instanceof CredentialItem.Username usernameItem) {
                            usernameItem.setValue(username);
                        } else if (item instanceof CredentialItem.Password passwordItem) {
                            passwordItem.setValue(token);
                        }
                    }
                    return true;
                }
            }
            return fallback != null && fallback.get(uri, items);
        }

        private boolean supportsAccountItems(CredentialItem... items) {
            for (CredentialItem item : items) {
                if (!(item instanceof CredentialItem.Username)
                        && !(item instanceof CredentialItem.Password)) return false;
            }
            return true;
        }

        private void clear() {
            java.util.Arrays.fill(token, '\0');
        }
    }

    private record UndoSnapshot(String commitId, String branch) {
    }

    public static class PatchException extends IOException {
        public PatchException(String message) { super(message); }
        public PatchException(String message, Throwable cause) { super(message, cause); }
    }
}
