package com.git.client;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Watches repository files and coalesces bursts of filesystem events. */
final class WorktreeWatcher implements AutoCloseable {
    private final Path workTree;
    private final Path gitDirectory;
    private final Runnable onChange;
    private final Consumer<IOException> onFailure;
    private final WatchService watchService;
    private final ScheduledExecutorService debounceExecutor =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "git-worktree-debounce");
                thread.setDaemon(true);
                return thread;
            });
    private final Thread watcherThread;
    private volatile boolean closed;
    private java.util.concurrent.ScheduledFuture<?> pendingRefresh;

    WorktreeWatcher(Path workTree, Path gitDirectory, Runnable onChange,
                    Consumer<IOException> onFailure) throws IOException {
        this.workTree = workTree.toAbsolutePath().normalize();
        this.gitDirectory = gitDirectory.toAbsolutePath().normalize();
        this.onChange = onChange;
        this.onFailure = onFailure;
        watchService = FileSystems.getDefault().newWatchService();
        watcherThread = new Thread(this::watch, "git-worktree-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    private void watch() {
        try {
            registerTree(workTree);
            registerGitMetadata();
            while (!closed) {
                WatchKey key = watchService.take();
                Path directory = (Path) key.watchable();
                for (var event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        scheduleRefresh();
                        continue;
                    }
                    if (!isRelevantEvent(directory, event.context())) continue;
                    Path changed = directory.resolve((Path) event.context());
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                            && Files.isDirectory(changed)) {
                        try {
                            if (changed.toAbsolutePath().normalize()
                                    .equals(gitDirectory.resolve("refs"))) {
                                registerGitRefs();
                            } else if (!isGitDirectory(changed)) {
                                registerTree(changed);
                            }
                        } catch (IOException exception) {
                            onFailure.accept(exception);
                        }
                    }
                    scheduleRefresh();
                }
                if (!key.reset()) {
                    onFailure.accept(new IOException(
                            "Stopped watching repository directory: " + directory));
                }
            }
        } catch (ClosedWatchServiceException exception) {
            // Closing the watcher is the normal shutdown path.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            if (!closed) onFailure.accept(exception);
        }
    }

    private void registerGitMetadata() throws IOException {
        gitDirectory.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
        registerGitRefs();
    }

    private void registerGitRefs() throws IOException {
        Path refs = gitDirectory.resolve("refs");
        if (Files.isDirectory(refs)) {
            Files.walkFileTree(refs, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory,
                                                         BasicFileAttributes attributes)
                        throws IOException {
                    directory.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_DELETE,
                            StandardWatchEventKinds.ENTRY_MODIFY);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private boolean isRelevantEvent(Path directory, Object context) {
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        if (!normalizedDirectory.equals(gitDirectory)
                && !normalizedDirectory.startsWith(gitDirectory)) {
            return true;
        }
        if (normalizedDirectory.equals(gitDirectory)) {
            String name = context.toString();
            return name.equals("HEAD") || name.equals("index") || name.equals("packed-refs")
                    || name.equals("config") || name.equals("FETCH_HEAD")
                    || name.equals("refs");
        }
        return normalizedDirectory.startsWith(gitDirectory.resolve("refs"));
    }

    private void registerTree(Path root) throws IOException {
        if (isGitDirectory(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (isGitDirectory(directory)) return FileVisitResult.SKIP_SUBTREE;
                directory.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean isGitDirectory(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.equals(gitDirectory) || normalized.startsWith(gitDirectory);
    }

    private synchronized void scheduleRefresh() {
        if (closed) return;
        if (pendingRefresh != null) pendingRefresh.cancel(false);
        pendingRefresh = debounceExecutor.schedule(onChange, 250, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        closed = true;
        try {
            watchService.close();
        } catch (IOException exception) {
            onFailure.accept(exception);
        }
        debounceExecutor.shutdownNow();
        watcherThread.interrupt();
    }
}
