package com.git.client.platform;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void defaultsToPublicInstancesAndUsesEnterpriseApiPaths() throws IOException {
        assertEquals("https://github.com",
                GitAccountService.Provider.GITHUB.defaultInstanceUrl());
        assertEquals("https://gitlab.com",
                GitAccountService.Provider.GITLAB.defaultInstanceUrl());
        assertEquals("https://api.github.com/user",
                GitAccountService.Provider.GITHUB
                        .userEndpoint("https://github.com").toString());
        assertEquals("https://git.example.com/api/v3/user",
                GitAccountService.Provider.GITHUB
                        .userEndpoint("https://git.example.com/").toString());
        assertEquals("https://code.example.com/git/api/v4/user",
                GitAccountService.Provider.GITLAB
                        .userEndpoint("https://code.example.com/git").toString());
    }

    @Test
    void validatesHttpsProviderUrlAndMatchesCredentialsToExactInstanceHost() throws IOException {
        assertEquals("https://git.example.com/base",
                GitAccountService.validateInstanceUrl(" https://git.example.com/base/ "));
        assertThrows(IOException.class,
                () -> GitAccountService.validateInstanceUrl("http://git.example.com"));
        assertThrows(IOException.class,
                () -> GitAccountService.validateInstanceUrl("https://user@git.example.com"));
        assertThrows(IOException.class,
                () -> GitAccountService.validateInstanceUrl("https://git.example.com?token=x"));

        try (GitAccountService.Account account = new GitAccountService.Account(
                GitAccountService.Provider.GITHUB, "developer", "https://git.example.com",
                new char[]{'t'})) {
            assertTrue(account.supportsHost("git.example.com"));
            assertFalse(account.supportsHost("other.example.com"));
        }
        try (GitAccountService.Account account = new GitAccountService.Account(
                GitAccountService.Provider.GITHUB, "developer", "https://git.example.com:8443",
                new char[]{'t'})) {
            assertTrue(account.supportsHost("git.example.com", 8443));
            assertFalse(account.supportsHost("git.example.com", 443));
        }
    }
}
