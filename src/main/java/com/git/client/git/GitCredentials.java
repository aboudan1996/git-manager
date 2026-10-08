package com.git.client.git;

/** Minimal credential data required by a repository transport; secrets are returned as copies. */
public interface GitCredentials {
    String username();
    char[] token();

    default boolean supportsHost(String host) {
        return false;
    }

    default boolean supportsHost(String host, int port) {
        return supportsHost(host);
    }
}
