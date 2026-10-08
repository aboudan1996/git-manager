package com.git.client.platform;

import com.git.client.git.GitCredentials;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
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
public final class GitAccountService {
    private static final String CREDENTIAL_TARGET = "GitPilot:AuthenticatedAccount";
    private static final Pattern GITHUB_LOGIN =
            Pattern.compile("\"login\"\\s*:\\s*\"([^\"\\\\]+)\"");
    private static final Pattern GITLAB_USERNAME =
            Pattern.compile("\"username\"\\s*:\\s*\"([^\"\\\\]+)\"");

    private final WindowsCredentialStore credentialStore = new WindowsCredentialStore();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public Account loadStoredAccount() throws IOException {
        if (!credentialStore.isSupported()) return null;
        WindowsCredentialStore.StoredCredentials stored = credentialStore.load(CREDENTIAL_TARGET);
        if (stored == null) return null;
        try {
            String[] fields = stored.username().split("\\|", 3);
            if (fields.length < 2 || fields[0].isBlank() || fields[1].isBlank()) {
                credentialStore.delete(CREDENTIAL_TARGET);
                return null;
            }
            Provider provider = Provider.fromId(fields[0]);
            String instanceUrl = fields.length == 3 ? fields[2] : provider.defaultInstanceUrl();
            return new Account(provider, fields[1], validateInstanceUrl(instanceUrl), stored.secret());
        } finally {
            Arrays.fill(stored.secret(), '\0');
        }
    }

    public Account signIn(Provider provider, String instanceUrl, char[] token)
            throws IOException, InterruptedException {
        String validatedUrl = validateInstanceUrl(instanceUrl);
        String username = verify(provider, validatedUrl, token);
        if (credentialStore.isSupported()) {
            credentialStore.save(CREDENTIAL_TARGET,
                    provider.id() + "|" + username + "|" + validatedUrl, token);
        }
        return new Account(provider, username, validatedUrl, token);
    }

    public Account verifyStored(Account stored) throws IOException, InterruptedException {
        char[] token = stored.token();
        try {
            String verifiedUsername = verify(stored.provider(), stored.instanceUrl(), token);
            return new Account(stored.provider(), verifiedUsername, stored.instanceUrl(), token);
        } finally {
            Arrays.fill(token, '\0');
        }
    }

    public boolean supportsPersistence() {
        return credentialStore.isSupported();
    }

    public void signOut() throws IOException {
        if (credentialStore.isSupported()) credentialStore.delete(CREDENTIAL_TARGET);
    }

    public static String validateInstanceUrl(String instanceUrl) throws IOException {
        if (instanceUrl == null || instanceUrl.isBlank()) {
            throw new IOException("Enter the base URL of your Git provider.");
        }
        try {
            URI uri = new URI(instanceUrl.trim()).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IOException("The provider URL must be an HTTPS base URL without "
                        + "credentials, query parameters, or a fragment.");
            }
            String normalized = uri.toASCIIString();
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        } catch (URISyntaxException exception) {
            throw new IOException("Enter a valid HTTPS provider URL.", exception);
        }
    }

    private String verify(Provider provider, String instanceUrl, char[] token)
            throws IOException, InterruptedException {
        if (provider == null || token == null || token.length == 0) {
            throw new IllegalArgumentException("Choose a provider and enter a personal access token.");
        }
        URI userEndpoint = provider.userEndpoint(instanceUrl);
        HttpRequest.Builder request = HttpRequest.newBuilder(userEndpoint)
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

    public enum Provider {
        GITHUB("github", "GitHub", "https://github.com"),
        GITLAB("gitlab", "GitLab", "https://gitlab.com");

        private final String id;
        private final String displayName;
        private final String defaultInstanceUrl;

        Provider(String id, String displayName, String defaultInstanceUrl) {
            this.id = id;
            this.displayName = displayName;
            this.defaultInstanceUrl = defaultInstanceUrl;
        }

        public String id() { return id; }
        public String displayName() { return displayName; }
        public String defaultInstanceUrl() { return defaultInstanceUrl; }

        URI userEndpoint(String instanceUrl) throws IOException {
            String base = validateInstanceUrl(instanceUrl);
            if (this == GITHUB && base.equalsIgnoreCase("https://github.com")) {
                return URI.create("https://api.github.com/user");
            }
            String apiPath = this == GITHUB ? "/api/v3/user" : "/api/v4/user";
            return URI.create(base + apiPath);
        }

        public boolean supportsHost(String host) {
            return host != null && switch (this) {
                case GITHUB -> host.equalsIgnoreCase("github.com")
                        || host.toLowerCase(Locale.ROOT).endsWith(".github.com");
                case GITLAB -> host.equalsIgnoreCase("gitlab.com")
                        || host.toLowerCase(Locale.ROOT).endsWith(".gitlab.com");
            };
        }

        public static Provider fromId(String id) throws IOException {
            for (Provider provider : values()) {
                if (provider.id.equalsIgnoreCase(id)) return provider;
            }
            throw new IOException("The saved Git provider is not supported.");
        }
    }

    public static final class Account implements GitCredentials, AutoCloseable {
        private final Provider provider;
        private final String username;
        private final char[] token;

        public Account(Provider provider, String username, char[] token) {
            this(provider, username, provider.defaultInstanceUrl(), token);
        }

        public Account(Provider provider, String username, String instanceUrl, char[] token) {
            this.provider = provider;
            this.username = username;
            this.instanceUrl = instanceUrl;
            this.token = token.clone();
        }

        private final String instanceUrl;

        public Provider provider() { return provider; }
        public String instanceUrl() { return instanceUrl; }
        @Override public String username() { return username; }
        @Override public char[] token() { return token.clone(); }
        @Override public boolean supportsHost(String host) {
            return host != null && host.equalsIgnoreCase(URI.create(instanceUrl).getHost());
        }
        @Override public boolean supportsHost(String host, int port) {
            if (!supportsHost(host)) return false;
            int instancePort = URI.create(instanceUrl).getPort();
            return (port < 0 ? 443 : port) == (instancePort < 0 ? 443 : instancePort);
        }

        @Override public void close() {
            Arrays.fill(token, '\0');
        }
    }

    public static final class InvalidTokenException extends IOException {
        public InvalidTokenException(String message) {
            super(message);
        }
    }
}
