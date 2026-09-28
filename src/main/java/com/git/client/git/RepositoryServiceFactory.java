package com.git.client.git;

import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.nio.file.Path;

/** Creates repository-operation contracts without exposing construction to the UI. */
public interface RepositoryServiceFactory {
    RepositoryOperations open(Path directory) throws IOException;

    RepositoryOperations cloneRepository(String uri, Path destination,
                                         String username, char[] password)
            throws GitAPIException, IOException;
}
