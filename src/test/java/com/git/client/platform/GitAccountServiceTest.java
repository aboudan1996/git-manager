package com.git.client.platform;

import com.git.client.platform.GitAccountService;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for provider account and token handling. */
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
