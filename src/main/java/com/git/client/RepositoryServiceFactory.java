package com.git.client;

import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.nio.file.Path;

/** Creates repository-operation contracts without exposing construction to the UI. */
interface RepositoryServiceFactory {
    RepositoryOperations open(Path directory) throws IOException;

    RepositoryOperations cloneRepository(String uri, Path destination,
                                         String username, char[] password)
            throws GitAPIException, IOException;
}
