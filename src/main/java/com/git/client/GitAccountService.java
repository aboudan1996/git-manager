package com.git.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates and securely persists GitHub and GitLab personal access tokens. */
final class GitAccountService {
    private static final String CREDENTIAL_TARGET = "GitPilot:AuthenticatedAccount";
    private static final Pattern GITHUB_LOGIN =
            Pattern.compile("\"login\"\\s*:\\s*\"([^\"\\\\]+)\"");
    private static final Pattern GITLAB_USERNAME =
            Pattern.compile("\"username\"\\s*:\\s*\"([^\"\\\\]+)\"");

    private final WindowsCredentialStore credentialStore = new WindowsCredentialStore();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    Account loadStoredAccount() throws IOException {
        if (!credentialStore.isSupported()) return null;
        WindowsCredentialStore.StoredCredentials stored = credentialStore.load(CREDENTIAL_TARGET);
        if (stored == null) return null;
        try {
            int separator = stored.username().indexOf('|');
            if (separator <= 0 || separator == stored.username().length() - 1) {
                credentialStore.delete(CREDENTIAL_TARGET);
                return null;
            }
            Provider provider = Provider.fromId(stored.username().substring(0, separator));
            String username = stored.username().substring(separator + 1);
            return new Account(provider, username, stored.secret());
        } finally {
            Arrays.fill(stored.secret(), '\0');
        }
    }

    Account signIn(Provider provider, char[] token) throws IOException, InterruptedException {
        String username = verify(provider, token);
        if (credentialStore.isSupported()) {
            credentialStore.save(CREDENTIAL_TARGET, provider.id() + "|" + username, token);
        }
        return new Account(provider, username, token);
    }

    Account verifyStored(Account stored) throws IOException, InterruptedException {
        char[] token = stored.token();
        try {
            String verifiedUsername = verify(stored.provider(), token);
            return new Account(stored.provider(), verifiedUsername, token);
        } finally {
            Arrays.fill(token, '\0');
        }
    }

    boolean supportsPersistence() {
        return credentialStore.isSupported();
    }

    void signOut() throws IOException {
        if (credentialStore.isSupported()) credentialStore.delete(CREDENTIAL_TARGET);
    }

    private String verify(Provider provider, char[] token) throws IOException, InterruptedException {
        if (provider == null || token == null || token.length == 0) {
            throw new IllegalArgumentException("Choose a provider and enter a personal access token.");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(provider.userEndpoint())
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", "GitPilot");
        if (provider == Provider.GITHUB) {
            request.header("Authorization", "Bearer " + new String(token));
            request.header("X-GitHub-Api-Version", "2022-11-28");
        } else {
            request.header("PRIVATE-TOKEN", new String(token));
        }
        HttpResponse<String> response = httpClient.send(request.GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 401) {
            throw new InvalidTokenException("The token is invalid or does not have permission "
                    + "to read the account profile.");
        }
        if (response.statusCode() == 403) {
            throw new IOException("The token is recognized but cannot read the account profile. "
                    + "Check its account/profile permissions and try again.");
        }
        if (response.statusCode() != 200) {
            throw new IOException(provider.displayName() + " could not verify the account "
                    + "(HTTP " + response.statusCode() + ").");
        }
        Pattern usernamePattern = provider == Provider.GITHUB ? GITHUB_LOGIN : GITLAB_USERNAME;
        Matcher matcher = usernamePattern.matcher(response.body());
        if (!matcher.find()) {
            throw new IOException("The provider returned an unexpected account profile.");
        }
        return matcher.group(1);
    }

    enum Provider {
        GITHUB("github", "GitHub", URI.create("https://api.github.com/user")),
        GITLAB("gitlab", "GitLab", URI.create("https://gitlab.com/api/v4/user"));

        private final String id;
        private final String displayName;
        private final URI userEndpoint;

        Provider(String id, String displayName, URI userEndpoint) {
            this.id = id;
            this.displayName = displayName;
            this.userEndpoint = userEndpoint;
        }

        String id() { return id; }
        String displayName() { return displayName; }
        URI userEndpoint() { return userEndpoint; }

        boolean supportsHost(String host) {
            return host != null && switch (this) {
                case GITHUB -> host.equalsIgnoreCase("github.com")
                        || host.toLowerCase(Locale.ROOT).endsWith(".github.com");
                case GITLAB -> host.equalsIgnoreCase("gitlab.com")
                        || host.toLowerCase(Locale.ROOT).endsWith(".gitlab.com");
            };
        }

        static Provider fromId(String id) throws IOException {
            for (Provider provider : values()) {
                if (provider.id.equalsIgnoreCase(id)) return provider;
            }
            throw new IOException("The saved Git provider is not supported.");
        }
    }

    static final class Account implements GitCredentials, AutoCloseable {
        private final Provider provider;
        private final String username;
        private final char[] token;

        Account(Provider provider, String username, char[] token) {
            this.provider = provider;
            this.username = username;
            this.token = token.clone();
        }

        Provider provider() { return provider; }
        @Override public String providerId() { return provider.id(); }
        @Override public String username() { return username; }
        @Override public char[] token() { return token.clone(); }

        @Override public void close() {
            Arrays.fill(token, '\0');
        }
    }

    static final class InvalidTokenException extends IOException {
        InvalidTokenException(String message) {
            super(message);
        }
    }
}
