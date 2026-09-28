package com.git.client.git;

import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.nio.file.Path;

/** JGit-backed adapter selected as the application's repository implementation. */
public final class JGitRepositoryServiceFactory implements RepositoryServiceFactory {
    @Override
    public RepositoryOperations open(Path directory) throws IOException {
        return GitRepositoryService.open(directory);
    }

    @Override
    public RepositoryOperations cloneRepository(String uri, Path destination,
                                                String username, char[] password)
            throws GitAPIException, IOException {
        return GitRepositoryService.cloneRepository(uri, destination, username, password);
    }
}
