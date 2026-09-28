package com.git.client.git;

/** Minimal credential data required by a repository transport; secrets are returned as copies. */
public interface GitCredentials {
    String providerId();
    String username();
    char[] token();
}
