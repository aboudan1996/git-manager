package com.git.client.git;

import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Application-facing contract for repository operations.
 * UI and coordination code depend on this contract rather than JGit's concrete service.
 */
public interface RepositoryOperations extends AutoCloseable {
    @Override
    void close();

    void setAuthenticatedAccount(GitCredentials account);
    void clearAuthenticatedAccount();
    boolean setHttpsCredentials(String username, char[] password) throws IOException;
    void addRemote(String name, String uri) throws GitAPIException;
    RepositoryState getState() throws GitAPIException, IOException;
    Path workTreePath();
    Path gitDirectoryPath();
    String repositoryName();
    String getDiff(String path, boolean staged) throws IOException;
    String getCommitDiff(String commitId) throws IOException;
    List<String> getCommitFiles(String commitId) throws IOException;
    String getCommitFileDiff(String commitId, String path) throws IOException;
    List<String> getConflictPaths() throws GitAPIException;
    ConflictContents getConflictContents(String path) throws IOException, GitAPIException;
    void resolveConflict(String path, String contents) throws IOException, GitAPIException;
    void stage(Collection<String> paths) throws GitAPIException;
    void stageAll() throws GitAPIException;
    void unstage(Collection<String> paths) throws GitAPIException, IOException;
    void unstageAll() throws GitAPIException, IOException;
    void commit(String message) throws GitAPIException;
    List<CommitEntry> getHistory() throws IOException;
    List<StashEntry> getStashes() throws GitAPIException;
    List<String> getStashFiles(String stashId) throws IOException;
    String getStashDiff(String stashId) throws IOException;
    String getStashFileDiff(String stashId, String path) throws IOException;
    void writeWorkingTreePatch(Path destination) throws IOException;
    List<String> getLocalBranches() throws GitAPIException;
    List<String> getRemoteBranches() throws GitAPIException;
    void createBranch(String name) throws GitAPIException;
    void createBranch(String name, String startPoint) throws GitAPIException;
    void checkout(String branch) throws GitAPIException;
    void deleteLocalBranch(String branch) throws GitAPIException, IOException;
    String checkoutRemoteBranch(String remoteBranch) throws GitAPIException;
    String merge(String branch) throws GitAPIException;
    String rebase(String branch) throws GitAPIException;
    String continueRebase() throws GitAPIException;
    String abortRebase() throws GitAPIException;
    String cherryPick(String commitId) throws GitAPIException;
    String revert(String commitId) throws GitAPIException;
    String stash() throws GitAPIException;
    String undoWorkingChanges() throws GitAPIException, IOException;
    String redoWorkingChanges() throws GitAPIException, IOException;
    String applyLatestStash(boolean dropAfterApply) throws GitAPIException;
    String applyStash(String stashId, boolean dropAfterApply) throws GitAPIException;
    String fetch() throws GitAPIException;
    String refreshRemoteBranches() throws GitAPIException;
    String pull() throws GitAPIException;
    String push() throws GitAPIException, IOException;

    record RepositoryState(String branch, List<String> unstaged, List<String> staged) { }

    record ConflictContents(String base, String ours, String theirs, String working) { }

    record CommitEntry(String message, String shortId, String author, Instant date,
                       String objectId) { }

    record StashEntry(String reference, String message, String shortId, Instant date,
                      String objectId) { }
}
