package com.git.client;

/** Lifecycle contract for observing filesystem changes in one open repository. */
interface RepositoryChangeMonitor extends AutoCloseable {
    @Override
    void close();
}
