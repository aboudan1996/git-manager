package com.git.client.platform;

import com.git.client.platform.WorktreeWatcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration tests for the NIO worktree monitor. */
class WorktreeWatcherTest {
    @TempDir
    Path workTree;

    @Test
    void detectsChangesInNewlyCreatedDirectories() throws Exception {
        Path gitDirectory = Files.createDirectory(workTree.resolve(".git"));
        CountDownLatch directoryChanged = new CountDownLatch(1);
        CountDownLatch nestedFileChanged = new CountDownLatch(1);
        AtomicInteger changeCount = new AtomicInteger();
        AtomicReference<IOException> failure = new AtomicReference<>();
        try (WorktreeWatcher ignored = new WorktreeWatcher(workTree, gitDirectory,
                () -> {
                    if (changeCount.incrementAndGet() == 1) directoryChanged.countDown();
                    else nestedFileChanged.countDown();
                }, failure::set)) {
            Thread.sleep(300);
            Path nested = Files.createDirectory(workTree.resolve("src"));
            assertTrue(directoryChanged.await(5, TimeUnit.SECONDS),
                    "Expected notification for the new directory");
            Files.writeString(nested.resolve("Main.java"), "class Main {}");

            assertTrue(nestedFileChanged.await(5, TimeUnit.SECONDS),
                    "Expected notification for a change inside the new directory");
            assertNull(failure.get());
        }
    }
}
