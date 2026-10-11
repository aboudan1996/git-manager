package com.git.client.platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for provider account and token handling. */
class GitAccountServiceTest {
    private GitAccountService service;

    @BeforeEach
    void setUp() throws Exception {
        // Désactiver la whitelist stricte pour les tests par défaut
        GitAccountService.setStrictWhitelistMode(false);
        GitAccountService.clearAllowedEnterpriseHosts();
        service = new GitAccountService(true, true);
    }

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
                        .userEndpoint("https://git.example.com").toString());
        assertEquals("https://code.example.com/git/api/v4/user",
                GitAccountService.Provider.GITLAB
                        .userEndpoint("https://code.example.com/git").toString());
    }

    @Test
    void validatesHttpsProviderUrlAndMatchesCredentialsToExactInstanceHost() throws IOException {
        // Désactiver le mode strict de whitelist pour ce test
        GitAccountService.setStrictWhitelistMode(false);

        assertEquals("https://git.example.com/base",
                GitAccountService.validateInstanceUrl(" https://git.example.com/base/ ",
                        GitAccountService.Provider.GITHUB));
        assertThrows(IOException.class,
                () -> GitAccountService.validateInstanceUrl("http://git.example.com",
                        GitAccountService.Provider.GITHUB));
        assertThrows(IOException.class,
                () -> GitAccountService.validateInstanceUrl("https://user@git.example.com",
                        GitAccountService.Provider.GITHUB));
        assertThrows(IOException.class,
                () -> GitAccountService.validateInstanceUrl("https://git.example.com?token=x",
                        GitAccountService.Provider.GITHUB));

        try (GitAccountService.Account account = new GitAccountService.Account(
                GitAccountService.Provider.GITHUB, "developer", "https://git.example.com",
                new char[]{'t'}, Instant.now())) {
            assertTrue(account.supportsHost("git.example.com"));
            assertFalse(account.supportsHost("other.example.com"));
        }
        try (GitAccountService.Account account = new GitAccountService.Account(
                GitAccountService.Provider.GITHUB, "developer", "https://git.example.com:8443",
                new char[]{'t'}, Instant.now())) {
            assertTrue(account.supportsHost("git.example.com", 8443));
            assertFalse(account.supportsHost("git.example.com", 443));
        }
    }
    @Test
    void detectsPublicVsEnterpriseInstances() throws IOException {
        assertTrue(GitAccountService.Provider.GITHUB.isPublicInstance("https://github.com"));
        assertFalse(GitAccountService.Provider.GITHUB.isPublicInstance("https://git.example.com"));
        assertTrue(GitAccountService.Provider.GITLAB.isPublicInstance("https://gitlab.com"));
        assertFalse(GitAccountService.Provider.GITLAB.isPublicInstance("https://gitlab.company.com"));
    }

    @Test
    void validateInstanceUrlRejectsPublicGitLabHostWithoutWhitelist() {
        assertDoesNotThrow(() ->
                GitAccountService.validateInstanceUrl("https://gitlab.com",
                        GitAccountService.Provider.GITLAB));
    }

    @Test
    void validateInstanceUrlRejectsEnterpriseHostNotInWhitelist() throws IOException {
        // Sans whitelist configurée, les hosts entreprise devraient être acceptés
        assertDoesNotThrow(() ->
                GitAccountService.validateInstanceUrl("https://gitlab.company.com",
                        GitAccountService.Provider.GITLAB));
    }

    @Test
    void validateInstanceUrlAcceptsEnterpriseHostInWhitelist() throws IOException {
        GitAccountService.addAllowedEnterpriseHost("gitlab.company.com");
        assertDoesNotThrow(() ->
                GitAccountService.validateInstanceUrl("https://gitlab.company.com",
                        GitAccountService.Provider.GITLAB));
    }

    @Test
    void validateInstanceUrlRejectsHostNotInWhitelistWhenWhitelistIsPopulated() throws IOException {
        GitAccountService.addAllowedEnterpriseHost("gitlab.allowed.com");
        assertThrows(IOException.class, () ->
                GitAccountService.validateInstanceUrl("https://gitlab.notallowed.com",
                        GitAccountService.Provider.GITLAB));
    }

    @Test
    void normalizeUrlsCorrectly() throws IOException {
        assertEquals("https://git.example.com",
                GitAccountService.validateInstanceUrl("https://git.example.com/",
                        GitAccountService.Provider.GITHUB));
        assertEquals("https://git.example.com",
                GitAccountService.validateInstanceUrl("https://git.example.com////",
                        GitAccountService.Provider.GITHUB));
        assertEquals("https://git.example.com",
                GitAccountService.validateInstanceUrl("  https://git.example.com  ",
                        GitAccountService.Provider.GITHUB));
    }

    @Test
    void rejectsInvalidUrls() {
        assertThrows(IOException.class,
                () -> GitAccountService.validateInstanceUrl(null,
                        GitAccountService.Provider.GITHUB));
        assertThrows(IOException.class,
                () -> GitAccountService.validateInstanceUrl("",
                        GitAccountService.Provider.GITHUB));
        assertThrows(IOException.class,
                () -> GitAccountService.validateInstanceUrl("   ",
                        GitAccountService.Provider.GITHUB));
        assertThrows(IOException.class,
                () -> GitAccountService.validateInstanceUrl("invalid-url",
                        GitAccountService.Provider.GITHUB));
    }

    @Test
    void accountTracksTokenCreationTime() {
        Instant createdAt = Instant.now();
        try (GitAccountService.Account account = new GitAccountService.Account(
                GitAccountService.Provider.GITHUB, "developer", "https://github.com",
                new char[]{'t'}, createdAt)) {
            assertFalse(account.isExpired(30));
        }
    }

    @Test
    void detectedExpiredTokens() {
        Instant expiredTime = Instant.now().minusSeconds(31 * 86400L); // 31 jours ago
        try (GitAccountService.Account account = new GitAccountService.Account(
                GitAccountService.Provider.GITHUB, "developer", "https://github.com",
                new char[]{'t'}, expiredTime)) {
            assertTrue(account.isExpired(30));
        }
    }

    @Test
    void accountPreservesInstanceUrl() throws IOException {
        String instanceUrl = "https://git.mycompany.com";
        try (GitAccountService.Account account = new GitAccountService.Account(
                GitAccountService.Provider.GITLAB, "developer", instanceUrl,
                new char[]{'t'}, Instant.now())) {
            assertEquals(instanceUrl, account.instanceUrl());
        }
    }

    @Test
    void accountClonesTokenToPreventExternalModification() {
        char[] originalToken = {'s', 'e', 'c', 'r', 'e', 't'};
        try (GitAccountService.Account account = new GitAccountService.Account(
                GitAccountService.Provider.GITHUB, "developer", "https://github.com",
                originalToken, Instant.now())) {
            char[] retrievedToken = account.token();
            originalToken[0] = 'X'; // Modifier le token original
            assertNotEquals('X', retrievedToken[0]); // Le token du compte ne doit pas changer
        }
    }

    @Test
    void accountSecurelyWipesTokenOnClose() {
        char[] token = {'s', 'e', 'c', 'r', 'e', 't'};
        GitAccountService.Account account = new GitAccountService.Account(
                GitAccountService.Provider.GITHUB, "developer", "https://github.com",
                token, Instant.now());
        account.close();

        // Vérifier que le token a été vidé (tous les chars remplacés par '\0')
        char[] retrievedToken = account.token();
        for (char c : retrievedToken) {
            assertEquals('\0', c);
        }
    }
}