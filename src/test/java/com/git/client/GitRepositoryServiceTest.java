package com.git.client;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitRepositoryServiceTest {
    @TempDir
    Path directory;

    @TempDir
    Path remoteDirectory;

    @TempDir
    Path cloneDirectory;

    @BeforeEach
    void initializeRepository() throws Exception {
        try (Git git = Git.init().setDirectory(directory.toFile()).call()) {
            git.getRepository().getConfig().setString("user", null, "name", "Test User");
            git.getRepository().getConfig().setString("user", null, "email", "test@example.com");
            git.getRepository().getConfig().save();
        }
    }

    @Test
    void stagesUnstagesAndCommitsChanges() throws Exception {
        Path readme = directory.resolve("README.md");
        Files.writeString(readme, "GitDesk");

        try (GitRepositoryService service = GitRepositoryService.open(directory)) {
            service.stage(List.of("README.md"));
            assertTrue(service.getState().staged().contains("README.md"));

            service.unstage(List.of("README.md"));
            assertTrue(Files.exists(readme));
            assertFalse(service.getState().staged().contains("README.md"));
            assertTrue(service.getState().unstaged().contains("README.md"));

            service.stageAll();
            service.commit("Add README");

            assertTrue(service.getState().staged().isEmpty());
            assertTrue(service.getState().unstaged().isEmpty());
            assertEquals("Add README", service.getHistory().get(0).message());
            assertEquals("Test User", service.getHistory().get(0).author());
        }
    }

    @Test
    void refusesToCommitWithoutConfiguredGitIdentity() throws Exception {
        Path readme = directory.resolve("README.md");
        Files.writeString(readme, "GitDesk");
        try (Git git = Git.open(directory.toFile())) {
            git.getRepository().getConfig().setString("user", null, "name", "");
            git.getRepository().getConfig().setString("user", null, "email", "");
            git.getRepository().getConfig().save();
            git.add().addFilepattern("README.md").call();
        }

        try (GitRepositoryService service = GitRepositoryService.open(directory)) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> service.commit("Add README"));

            assertTrue(error.getMessage().contains("user.name and user.email"));
        }
    }

    @Test
    void stagesDeletionsWithoutDeletingAdditionalFiles() throws Exception {
        Path trackedFile = directory.resolve("tracked.txt");
        Files.writeString(trackedFile, "tracked");
        try (Git git = Git.open(directory.toFile())) {
            git.add().addFilepattern("tracked.txt").call();
            git.commit().setMessage("Add tracked file").call();
        }

        Files.delete(trackedFile);
        Path newFile = directory.resolve("new.txt");
        Files.writeString(newFile, "new");

        try (GitRepositoryService service = GitRepositoryService.open(directory)) {
            service.stageAll();

            assertFalse(Files.exists(trackedFile));
            assertTrue(Files.exists(newFile));
            assertTrue(service.getState().staged().containsAll(
                    List.of("tracked.txt", "new.txt")));

            service.unstageAll();
            assertFalse(Files.exists(trackedFile));
            assertTrue(Files.exists(newFile));
            assertTrue(service.getState().staged().isEmpty());
        }
    }

    @Test
    void createsAndChecksOutBranchesAndCherryPicksCommits() throws Exception {
        Path file = directory.resolve("change.txt");
        Files.writeString(file, "base");
        try (Git git = Git.open(directory.toFile())) {
            git.add().addFilepattern("change.txt").call();
            git.commit().setMessage("Base").call();
        }

        String featureCommit;
        try (GitRepositoryService service = GitRepositoryService.open(directory)) {
            service.createBranch("feature/demo");
            Files.writeString(file, "feature");
            service.stageAll();
            service.commit("Feature change");
            featureCommit = service.getHistory().get(0).objectId();
            service.checkout("master");
            service.cherryPick(featureCommit);

            assertEquals("Feature change", service.getHistory().get(0).message());
            assertEquals("feature", Files.readString(file));
        }
    }

    @Test
    void createsBranchFromSelectedCommitInsteadOfCurrentHead() throws Exception {
        Path file = directory.resolve("branch-point.txt");
        Files.writeString(file, "base");
        try (Git git = Git.open(directory.toFile())) {
            git.add().addFilepattern("branch-point.txt").call();
            git.commit().setMessage("Base point").call();
            Files.writeString(file, "later");
            git.add().addFilepattern("branch-point.txt").call();
            git.commit().setMessage("Later commit").call();
        }

        try (GitRepositoryService service = GitRepositoryService.open(directory)) {
            service.createBranch("from-base", "HEAD~1");

            assertEquals("Base point", service.getHistory().get(0).message());
            assertEquals("base", Files.readString(file));
            assertEquals("from-base", service.getState().branch());
        }
    }

    @Test
    void undoesAndRedoesStagedAndUntrackedWorkingChanges() throws Exception {
        Path tracked = directory.resolve("undo.txt");
        Files.writeString(tracked, "base");
        try (Git git = Git.open(directory.toFile())) {
            git.add().addFilepattern("undo.txt").call();
            git.commit().setMessage("Base").call();
        }

        Path untracked = directory.resolve("new-undo.txt");
        Files.writeString(tracked, "staged change");
        Files.writeString(untracked, "untracked change");
        try (GitRepositoryService service = GitRepositoryService.open(directory)) {
            service.stage(List.of("undo.txt"));

            assertTrue(service.undoWorkingChanges().contains("Use Redo"));
            assertEquals("base", Files.readString(tracked));
            assertFalse(Files.exists(untracked));
            assertTrue(service.getState().staged().isEmpty());
            assertTrue(service.getState().unstaged().isEmpty());

            assertTrue(service.redoWorkingChanges().contains("restored"));
            assertEquals("staged change", Files.readString(tracked));
            assertTrue(Files.exists(untracked));
            assertTrue(service.getState().staged().contains("undo.txt"));
            assertTrue(service.getState().unstaged().contains("new-undo.txt"));
        }
    }

    @Test
    void deletesOnlyNonCurrentLocalBranch() throws Exception {
        Path file = directory.resolve("branch-delete.txt");
        Files.writeString(file, "base");
        try (Git git = Git.open(directory.toFile())) {
            git.add().addFilepattern("branch-delete.txt").call();
            git.commit().setMessage("Base").call();
        }

        try (GitRepositoryService service = GitRepositoryService.open(directory)) {
            service.createBranch("feature/delete");
            service.checkout("master");
            service.deleteLocalBranch("feature/delete");

            assertFalse(service.getLocalBranches().contains("feature/delete"));
            assertThrows(IllegalArgumentException.class,
                    () -> service.deleteLocalBranch("master"));
        }
    }

    @Test
    void stashesAndRestoresUntrackedFiles() throws Exception {
        Path file = directory.resolve("work.txt");
        Files.writeString(directory.resolve("base.txt"), "base");
        try (Git git = Git.open(directory.toFile())) {
            git.add().addFilepattern("base.txt").call();
            git.commit().setMessage("Base").call();
        }

        Files.writeString(file, "work in progress");
        try (GitRepositoryService service = GitRepositoryService.open(directory)) {
            service.stash();
            assertFalse(Files.exists(file));

            service.applyLatestStash(true);

            assertTrue(Files.exists(file));
            assertEquals("work in progress", Files.readString(file));
        }
    }

    @Test
    void pushesCurrentBranchToConfiguredRemoteAndSetsUpstream() throws Exception {
        try (Git remote = Git.init().setBare(true).setDirectory(remoteDirectory.toFile()).call();
             Git local = Git.open(directory.toFile())) {
            local.remoteAdd().setName("origin")
                    .setUri(new URIish(remoteDirectory.toUri().toString())).call();
            Files.writeString(directory.resolve("pushed.txt"), "push");
            local.add().addFilepattern("pushed.txt").call();
            local.commit().setMessage("Push commit").call();
        }

        try (GitRepositoryService service = GitRepositoryService.open(directory);
             Git remote = Git.open(remoteDirectory.toFile())) {
            assertTrue(service.push().contains("Push completed"));
            assertTrue(remote.getRepository().resolve("refs/heads/master") != null);
            assertEquals("origin", service.getRepository().getConfig()
                    .getString("branch", "master", "remote"));
        }
    }

    @Test
    void listsRemoteBranchesAndChecksOutTrackingBranch() throws Exception {
        try (Git remote = Git.init().setBare(true).setDirectory(remoteDirectory.toFile()).call();
             Git local = Git.open(directory.toFile())) {
            Files.writeString(directory.resolve("remote-branch.txt"), "branch");
            local.add().addFilepattern("remote-branch.txt").call();
            local.commit().setMessage("Initial commit").call();
            local.remoteAdd().setName("origin")
                    .setUri(new URIish(remoteDirectory.toUri().toString())).call();
            local.push().setRemote("origin").setRefSpecs(new org.eclipse.jgit.transport.RefSpec(
                    "refs/heads/master:refs/heads/master")).call();
            var remoteBranch = remote.getRepository().updateRef("refs/heads/feature/remote");
            remoteBranch.setNewObjectId(local.getRepository().resolve("refs/heads/master"));
            remoteBranch.update();
        }

        try (GitRepositoryService service = GitRepositoryService.open(directory)) {
            assertTrue(service.getRemoteBranches().stream()
                    .noneMatch(branch -> branch.equals("origin/feature/remote")));
            assertTrue(service.refreshRemoteBranches().contains("refreshed"));
            assertTrue(service.getRemoteBranches().contains("origin/feature/remote"));
            assertTrue(service.checkoutRemoteBranch("origin/feature/remote")
                    .contains("tracking origin/feature/remote"));
            assertEquals("feature/remote", service.getState().branch());
            assertEquals("origin", service.getRepository().getConfig()
                    .getString("branch", "feature/remote", "remote"));
            assertEquals("refs/heads/feature/remote", service.getRepository().getConfig()
                    .getString("branch", "feature/remote", "merge"));
        }
    }

    @Test
    void revertsACommitWithoutRewritingHistory() throws Exception {
        Path file = directory.resolve("revert.txt");
        Files.writeString(file, "initial");
        try (Git git = Git.open(directory.toFile())) {
            git.add().addFilepattern("revert.txt").call();
            git.commit().setMessage("Initial content").call();
            Files.writeString(file, "updated");
            git.add().addFilepattern("revert.txt").call();
            git.commit().setMessage("Update content").call();
        }

        try (GitRepositoryService service = GitRepositoryService.open(directory)) {
            String commitToRevert = service.getHistory().get(0).objectId();
            service.revert(commitToRevert);

            assertEquals("initial", Files.readString(file));
            assertEquals("Revert \"Update content\"", service.getHistory().get(0).message());
            assertEquals(3, service.getHistory().size());
        }
    }

    @Test
    void mergesAndRebasesLocalBranches() throws Exception {
        Files.writeString(directory.resolve("base.txt"), "base");
        try (Git git = Git.open(directory.toFile())) {
            git.add().addFilepattern("base.txt").call();
            git.commit().setMessage("Base").call();
        }

        try (GitRepositoryService service = GitRepositoryService.open(directory)) {
            service.createBranch("feature/merge");
            Files.writeString(directory.resolve("merge.txt"), "merged");
            service.stageAll();
            service.commit("Merge feature");
            service.checkout("master");
            assertTrue(service.merge("feature/merge").toLowerCase().contains("fast"));

            service.createBranch("feature/rebase");
            Files.writeString(directory.resolve("rebased.txt"), "rebased");
            service.stageAll();
            service.commit("Rebase feature");
            service.checkout("master");
            Files.writeString(directory.resolve("main.txt"), "main");
            service.stageAll();
            service.commit("Advance main");
            service.checkout("feature/rebase");

            String result = service.rebase("master").toLowerCase();

            assertFalse(result.contains("conflict"));
            assertTrue(Files.exists(directory.resolve("main.txt")));
            assertEquals("Rebase feature", service.getHistory().get(0).message());
        }
    }

    @Test
    void clonesRepositoryAndReadsItsHistory() throws Exception {
        Files.writeString(directory.resolve("clone.txt"), "clone");
        try (Git git = Git.open(directory.toFile())) {
            git.add().addFilepattern("clone.txt").call();
            git.commit().setMessage("Clone me").call();
        }

        Path destination = cloneDirectory.resolve("cloned-project");
        try (GitRepositoryService clone = GitRepositoryService.cloneRepository(
                directory.toUri().toString(), destination)) {
            assertEquals("Clone me", clone.getHistory().get(0).message());
            assertEquals("clone", Files.readString(destination.resolve("clone.txt")));
        }
    }

    @Test
    void showsUnstagedAndStagedDiffAgainstTheIndexAndHead() throws Exception {
        Path file = directory.resolve("diff.txt");
        Files.writeString(file, "old line\n");
        try (Git git = Git.open(directory.toFile())) {
            git.add().addFilepattern("diff.txt").call();
            git.commit().setMessage("Initial diff file").call();
        }

        Files.writeString(file, "new line\n");
        try (GitRepositoryService service = GitRepositoryService.open(directory)) {
            String unstagedDiff = service.getDiff("diff.txt", false);
            assertTrue(unstagedDiff.contains("-old line"));
            assertTrue(unstagedDiff.contains("+new line"));

            service.stage(List.of("diff.txt"));
            String stagedDiff = service.getDiff("diff.txt", true);
            assertTrue(stagedDiff.contains("-old line"));
            assertTrue(stagedDiff.contains("+new line"));
        }
    }

    @Test
    void showsUntrackedFileDiffAgainstEmptyIndex() throws Exception {
        Files.writeString(directory.resolve("new-file.txt"), "new content\n");

        try (GitRepositoryService service = GitRepositoryService.open(directory)) {
            String diff = service.getDiff("new-file.txt", false);

            assertTrue(diff.contains("+new content"));
        }
    }

    @Test
    void showsSelectedCommitChangesAgainstItsParent() throws Exception {
        Path file = directory.resolve("commit-diff.txt");
        Files.writeString(file, "before\n");
        String commitId;
        try (Git git = Git.open(directory.toFile())) {
            git.add().addFilepattern("commit-diff.txt").call();
            git.commit().setMessage("Add original file").call();
            Files.writeString(file, "after\n");
            git.add().addFilepattern("commit-diff.txt").call();
            commitId = git.commit().setMessage("Update selected file").call().getName();
        }

        try (GitRepositoryService service = GitRepositoryService.open(directory)) {
            String diff = service.getCommitDiff(commitId);

            assertTrue(diff.contains("-before"));
            assertTrue(diff.contains("+after"));
            assertEquals(List.of("commit-diff.txt"), service.getCommitFiles(commitId));
            String fileDiff = service.getCommitFileDiff(commitId, "commit-diff.txt");
            assertTrue(fileDiff.contains("-before"));
            assertTrue(fileDiff.contains("+after"));
        }
    }

    @Test
    void resolvesMergeConflictAndStagesResolution() throws Exception {
        Path file = directory.resolve("conflict.txt");
        Files.writeString(file, "base\n");
        try (Git git = Git.open(directory.toFile())) {
            git.add().addFilepattern("conflict.txt").call();
            git.commit().setMessage("Base").call();
        }

        try (GitRepositoryService service = GitRepositoryService.open(directory)) {
            service.createBranch("feature/conflict");
            Files.writeString(file, "theirs\n");
            service.stageAll();
            service.commit("Theirs");
            service.checkout("master");
            Files.writeString(file, "ours\n");
            service.stageAll();
            service.commit("Ours");

            service.merge("feature/conflict");
            assertEquals(List.of("conflict.txt"), service.getConflictPaths());
            GitRepositoryService.ConflictContents conflict =
                    service.getConflictContents("conflict.txt");
            assertEquals("base\n", conflict.base());
            assertEquals("ours\n", conflict.ours());
            assertEquals("theirs\n", conflict.theirs());

            service.resolveConflict("conflict.txt", "resolved\n");

            assertTrue(service.getConflictPaths().isEmpty());
            assertTrue(service.getState().staged().contains("conflict.txt"));
            assertEquals("resolved\n", Files.readString(file));
        }
    }

    @Test
    void resolvesConflictAndContinuesRebase() throws Exception {
        Path file = directory.resolve("rebase-conflict.txt");
        Files.writeString(file, "base\n");
        try (Git git = Git.open(directory.toFile())) {
            git.add().addFilepattern("rebase-conflict.txt").call();
            git.commit().setMessage("Base").call();
        }

        try (GitRepositoryService service = GitRepositoryService.open(directory)) {
            service.createBranch("feature/rebase-conflict");
            Files.writeString(file, "feature\n");
            service.stageAll();
            service.commit("Feature version");
            service.checkout("master");
            Files.writeString(file, "main\n");
            service.stageAll();
            service.commit("Main version");
            service.checkout("feature/rebase-conflict");

            service.rebase("master");
            assertEquals(List.of("rebase-conflict.txt"), service.getConflictPaths());
            service.resolveConflict("rebase-conflict.txt", "resolved\n");
            service.continueRebase();

            assertTrue(service.getConflictPaths().isEmpty());
            assertEquals("Feature version", service.getHistory().get(0).message());
            assertEquals("resolved\n", Files.readString(file));
        }
    }
}
