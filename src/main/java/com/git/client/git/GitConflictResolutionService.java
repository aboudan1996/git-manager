package com.git.client.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.diff.RawText;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Reads conflict stages and safely writes/ stages a text conflict resolution. */
final class GitConflictResolutionService {
    private final Git git;

    GitConflictResolutionService(Git git) {
        this.git = git;
    }

    List<String> getConflictPaths() throws GitAPIException {
        return git.status().call().getConflicting().stream().sorted().toList();
    }

    RepositoryOperations.ConflictContents getConflictContents(String path)
            throws IOException, GitAPIException {
        validateConflictPath(path);
        DirCache cache = git.getRepository().readDirCache();
        String base = null;
        String ours = null;
        String theirs = null;
        for (int index = 0; index < cache.getEntryCount(); index++) {
            DirCacheEntry entry = cache.getEntry(index);
            if (!path.equals(entry.getPathString())) {
                continue;
            }
            byte[] bytes = git.getRepository().open(entry.getObjectId()).getBytes();
            if (RawText.isBinary(bytes)) {
                throw new IOException("Binary conflicts cannot be edited in the text resolver: " + path);
            }
            String contents = new String(bytes, StandardCharsets.UTF_8);
            switch (entry.getStage()) {
                case 1 -> base = contents;
                case 2 -> ours = contents;
                case 3 -> theirs = contents;
                default -> { }
            }
        }
        Path workTree = git.getRepository().getWorkTree().toPath().toRealPath();
        Path file = resolveSafeWorkTreePath(workTree, path);
        String working = "";
        if (Files.exists(file)) {
            byte[] bytes = Files.readAllBytes(file);
            if (RawText.isBinary(bytes)) {
                throw new IOException("Binary conflicts cannot be edited in the text resolver: " + path);
            }
            working = new String(bytes, StandardCharsets.UTF_8);
        }
        return new RepositoryOperations.ConflictContents(base, ours, theirs, working);
    }

    void resolveConflict(String path, String contents) throws IOException, GitAPIException {
        if (contents == null) {
            throw new IllegalArgumentException("Enter the resolved file contents.");
        }
        validateConflictPath(path);
        Path workTree = git.getRepository().getWorkTree().toPath().toRealPath();
        Path target = resolveSafeWorkTreePath(workTree, path);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(target, contents, StandardCharsets.UTF_8);
        git.add().addFilepattern(path).call();
    }

    private void validateConflictPath(String path) throws GitAPIException {
        if (path == null || !getConflictPaths().contains(path)) {
            throw new IllegalArgumentException("The selected file is not currently conflicted.");
        }
    }

    private Path resolveSafeWorkTreePath(Path workTree, String path) {
        Path target = workTree.resolve(path).normalize();
        if (!target.startsWith(workTree)) {
            throw new IllegalArgumentException("Conflict path is outside the repository.");
        }
        Path current = workTree;
        for (Path segment : workTree.relativize(target)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Cannot access conflict files through symbolic links.");
            }
        }
        return target;
    }
}
