package com.git.client;

import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Handles repository diffs and bounded commit-history queries. */
final class GitDiffHistoryService {
    private final Repository repository;

    GitDiffHistoryService(Repository repository) {
        this.repository = repository;
    }

    String getDiff(String path, boolean staged) throws IOException {
        try (var reader = repository.newObjectReader();
             ByteArrayOutputStream output = new ByteArrayOutputStream();
             DiffFormatter formatter = new DiffFormatter(output)) {
            AbstractTreeIterator oldTree;
            AbstractTreeIterator newTree;
            if (staged) {
                ObjectId head = repository.resolve(Constants.HEAD);
                if (head == null) {
                    oldTree = new EmptyTreeIterator();
                } else {
                    try (RevWalk walk = new RevWalk(repository)) {
                        CanonicalTreeParser headTree = new CanonicalTreeParser();
                        headTree.reset(reader, walk.parseCommit(head).getTree());
                        oldTree = headTree;
                    }
                }
                newTree = new DirCacheIterator(repository.readDirCache());
            } else {
                oldTree = new DirCacheIterator(repository.readDirCache());
                newTree = new FileTreeIterator(repository);
            }
            formatter.setRepository(repository);
            formatter.setDiffComparator(RawTextComparator.DEFAULT);
            formatter.setDetectRenames(true);
            List<DiffEntry> entries = formatter.scan(oldTree, newTree);
            boolean found = false;
            for (DiffEntry entry : entries) {
                if (path.equals(entry.getOldPath()) || path.equals(entry.getNewPath())) {
                    formatter.format(entry);
                    found = true;
                }
            }
            if (!found) {
                return "No textual differences for " + path + ".";
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    String getCommitDiff(String commitId) throws IOException {
        ObjectId commitObject = resolveCommit(commitId);
        try (var reader = repository.newObjectReader();
             RevWalk walk = new RevWalk(repository);
             ByteArrayOutputStream output = new ByteArrayOutputStream();
             DiffFormatter formatter = new DiffFormatter(output)) {
            RevCommit commit = walk.parseCommit(commitObject);
            AbstractTreeIterator oldTree = parentTree(reader, walk, commit);
            CanonicalTreeParser commitTree = new CanonicalTreeParser();
            commitTree.reset(reader, commit.getTree());
            formatter.setRepository(repository);
            formatter.setDiffComparator(RawTextComparator.DEFAULT);
            formatter.setDetectRenames(true);
            List<DiffEntry> entries = formatter.scan(oldTree, commitTree);
            if (entries.isEmpty()) {
                return "Commit " + commit.getName().substring(0, 7) + " has no file changes.";
            }
            for (DiffEntry entry : entries) {
                formatter.format(entry);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    List<String> getCommitFiles(String commitId) throws IOException {
        ObjectId commitObject = resolveCommit(commitId);
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(commitObject);
            try (var reader = repository.newObjectReader()) {
                AbstractTreeIterator oldTree = parentTree(reader, walk, commit);
                CanonicalTreeParser commitTree = new CanonicalTreeParser();
                commitTree.reset(reader, commit.getTree());
                try (DiffFormatter formatter = new DiffFormatter(new ByteArrayOutputStream())) {
                    formatter.setRepository(repository);
                    formatter.setDetectRenames(true);
                    return formatter.scan(oldTree, commitTree).stream()
                            .map(entry -> entry.getChangeType() == DiffEntry.ChangeType.DELETE
                                    ? entry.getOldPath() : entry.getNewPath())
                            .toList();
                }
            }
        }
    }

    String getCommitFileDiff(String commitId, String path) throws IOException {
        ObjectId commitObject = resolveCommit(commitId);
        try (var reader = repository.newObjectReader();
             RevWalk walk = new RevWalk(repository);
             ByteArrayOutputStream output = new ByteArrayOutputStream();
             DiffFormatter formatter = new DiffFormatter(output)) {
            RevCommit commit = walk.parseCommit(commitObject);
            AbstractTreeIterator oldTree = parentTree(reader, walk, commit);
            CanonicalTreeParser commitTree = new CanonicalTreeParser();
            commitTree.reset(reader, commit.getTree());
            formatter.setRepository(repository);
            formatter.setDiffComparator(RawTextComparator.DEFAULT);
            formatter.setDetectRenames(true);
            for (DiffEntry entry : formatter.scan(oldTree, commitTree)) {
                if (path.equals(entry.getOldPath()) || path.equals(entry.getNewPath())) {
                    formatter.format(entry);
                    return output.toString(StandardCharsets.UTF_8);
                }
            }
            return "No differences for " + path + " in this commit.";
        }
    }

    String getWorkingTreePatch() throws IOException {
        ObjectId head = repository.resolve(Constants.HEAD);
        try (var reader = repository.newObjectReader();
             ByteArrayOutputStream output = new ByteArrayOutputStream();
             DiffFormatter formatter = new DiffFormatter(output)) {
            AbstractTreeIterator oldTree;
            if (head == null) {
                oldTree = new EmptyTreeIterator();
            } else {
                try (RevWalk walk = new RevWalk(repository)) {
                    CanonicalTreeParser headTree = new CanonicalTreeParser();
                    headTree.reset(reader, walk.parseCommit(head).getTree());
                    oldTree = headTree;
                }
            }
            formatter.setRepository(repository);
            formatter.setDiffComparator(RawTextComparator.DEFAULT);
            formatter.setDetectRenames(true);
            for (DiffEntry entry : formatter.scan(oldTree, new FileTreeIterator(repository))) {
                formatter.format(entry);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    List<String> getStashFiles(String stashId) throws IOException {
        RevCommit stash = parseCommit(stashId);
        Set<String> paths = new LinkedHashSet<>(filesBetween(stash,
                stash.getParentCount() == 0 ? null : stash.getParent(0)));
        if (stash.getParentCount() > 2) {
            paths.addAll(filesBetween(stash.getParent(2), null));
        }
        return List.copyOf(paths);
    }

    String getStashDiff(String stashId) throws IOException {
        RevCommit stash = parseCommit(stashId);
        String tracked = diffBetween(stash,
                stash.getParentCount() == 0 ? null : stash.getParent(0), null);
        if (stash.getParentCount() <= 2) {
            return tracked;
        }
        String untracked = diffBetween(stash.getParent(2), null, null);
        return joinDiffs(tracked, untracked);
    }

    String getStashFileDiff(String stashId, String path) throws IOException {
        RevCommit stash = parseCommit(stashId);
        String tracked = diffBetween(stash,
                stash.getParentCount() == 0 ? null : stash.getParent(0), path);
        if (!tracked.isBlank() || stash.getParentCount() <= 2) {
            return tracked.isBlank() ? "No differences for " + path + " in this stash." : tracked;
        }
        String untracked = diffBetween(stash.getParent(2), null, path);
        return untracked.isBlank() ? "No differences for " + path + " in this stash." : untracked;
    }

    List<GitRepositoryService.CommitEntry> getHistory() throws IOException {
        List<GitRepositoryService.CommitEntry> commits = new ArrayList<>();
        ObjectId head = repository.resolve(Constants.HEAD);
        if (head == null) {
            return List.of();
        }
        try (RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(head));
            for (RevCommit commit : walk) {
                commits.add(new GitRepositoryService.CommitEntry(
                        commit.getShortMessage(),
                        commit.getName().substring(0, 7),
                        commit.getAuthorIdent().getName(),
                        Instant.ofEpochSecond(commit.getCommitTime()),
                        commit.getName()
                ));
                if (commits.size() == 100) break;
            }
        }
        return List.copyOf(commits);
    }

    private AbstractTreeIterator parentTree(ObjectReader reader,
                                            RevWalk walk, RevCommit commit) throws IOException {
        if (commit.getParentCount() == 0) {
            // Root commits have no parent tree, so compare them against an empty tree.
            return new EmptyTreeIterator();
        }
        CanonicalTreeParser parentTree = new CanonicalTreeParser();
        parentTree.reset(reader, walk.parseCommit(commit.getParent(0)).getTree());
        return parentTree;
    }

    private List<String> filesBetween(RevCommit newer, RevCommit older) throws IOException {
        try (var reader = repository.newObjectReader();
             RevWalk walk = new RevWalk(repository);
             DiffFormatter formatter = new DiffFormatter(new ByteArrayOutputStream())) {
            formatter.setRepository(repository);
            formatter.setDetectRenames(true);
            return formatter.scan(treeOf(reader, walk, older), treeOf(reader, walk, newer))
                    .stream()
                    .map(entry -> entry.getChangeType() == DiffEntry.ChangeType.DELETE
                            ? entry.getOldPath() : entry.getNewPath())
                    .toList();
        }
    }

    private String diffBetween(RevCommit newer, RevCommit older, String path) throws IOException {
        try (var reader = repository.newObjectReader();
             RevWalk walk = new RevWalk(repository);
             ByteArrayOutputStream output = new ByteArrayOutputStream();
             DiffFormatter formatter = new DiffFormatter(output)) {
            formatter.setRepository(repository);
            formatter.setDiffComparator(RawTextComparator.DEFAULT);
            formatter.setDetectRenames(true);
            for (DiffEntry entry : formatter.scan(
                    treeOf(reader, walk, older), treeOf(reader, walk, newer))) {
                if (path == null || path.equals(entry.getOldPath()) || path.equals(entry.getNewPath())) {
                    formatter.format(entry);
                }
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private AbstractTreeIterator treeOf(ObjectReader reader, RevWalk walk, RevCommit commit)
            throws IOException {
        if (commit == null) {
            return new EmptyTreeIterator();
        }
        CanonicalTreeParser tree = new CanonicalTreeParser();
        tree.reset(reader, walk.parseCommit(commit).getTree());
        return tree;
    }

    private RevCommit parseCommit(String commitId) throws IOException {
        ObjectId id = repository.resolve(commitId);
        if (id == null) {
            throw new IllegalArgumentException("The selected stash could not be found.");
        }
        try (RevWalk walk = new RevWalk(repository)) {
            return walk.parseCommit(id);
        }
    }

    private String joinDiffs(String first, String second) {
        if (first.isBlank()) return second;
        if (second.isBlank()) return first;
        return first + System.lineSeparator() + second;
    }

    private ObjectId resolveCommit(String commitId) {
        try {
            ObjectId commit = repository.resolve(commitId);
            if (commit == null) {
                throw new IllegalArgumentException("The selected commit could not be found.");
            }
            return commit;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not resolve the selected commit.", exception);
        }
    }
}
