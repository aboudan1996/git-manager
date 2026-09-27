package com.git.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitAccountServiceTest {
    @Test
    void matchesAccountCredentialsOnlyToTheSelectedProviderHosts() {
        assertTrue(GitAccountService.Provider.GITHUB.supportsHost("github.com"));
        assertTrue(GitAccountService.Provider.GITHUB.supportsHost("uploads.github.com"));
        assertFalse(GitAccountService.Provider.GITHUB.supportsHost("gitlab.com"));
        assertTrue(GitAccountService.Provider.GITLAB.supportsHost("gitlab.com"));
        assertTrue(GitAccountService.Provider.GITLAB.supportsHost("code.gitlab.com"));
        assertFalse(GitAccountService.Provider.GITLAB.supportsHost("github.com"));
    }
}
