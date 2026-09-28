package com.git.client.platform;

/** Lifecycle contract for observing filesystem changes in one open repository. */
public interface RepositoryChangeMonitor extends AutoCloseable {
    @Override
    void close();
}
