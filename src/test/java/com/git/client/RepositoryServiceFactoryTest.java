package com.git.client;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RepositoryServiceFactoryTest {
    @TempDir
    Path directory;

    @Test
    void createsRepositoryOperationsWithoutExposingJGitToCaller() throws Exception {
        try (Git ignored = Git.init().setDirectory(directory.toFile()).call();
             RepositoryOperations repository =
                     new JGitRepositoryServiceFactory().open(directory)) {
            assertEquals(directory.toAbsolutePath().normalize(), repository.workTreePath());
            assertEquals(directory.getFileName().toString(), repository.repositoryName());
            assertFalse(repository.getState().branch().isBlank());
        }
    }
}
