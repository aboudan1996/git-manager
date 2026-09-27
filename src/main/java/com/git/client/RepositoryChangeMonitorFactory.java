package com.git.client;

import java.io.IOException;
import java.nio.file.Path;

/** Creates a monitor for the repository currently selected by the user. */
interface RepositoryChangeMonitorFactory {
    RepositoryChangeMonitor watch(Path workTree, Path gitDirectory, Runnable onChange,
                                  java.util.function.Consumer<IOException> onFailure)
            throws IOException;
}
