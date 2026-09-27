package com.git.client;

/** Minimal credential data required by a repository transport; secrets are returned as copies. */
interface GitCredentials {
    String providerId();
    String username();
    char[] token();
}
