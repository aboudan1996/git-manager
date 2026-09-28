package com.git.client.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Default monitor adapter built on the JDK filesystem WatchService API. */
public final class NioRepositoryChangeMonitorFactory implements RepositoryChangeMonitorFactory {
    @Override
    public RepositoryChangeMonitor watch(Path workTree, Path gitDirectory, Runnable onChange,
                                         Consumer<IOException> onFailure) throws IOException {
        return new WorktreeWatcher(workTree, gitDirectory, onChange, onFailure);
    }
}
