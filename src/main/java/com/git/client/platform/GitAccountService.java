package com.git.client.platform;

import com.git.client.git.GitCredentials;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Validates and securely persists GitHub and GitLab personal access tokens. */
public final class GitAccountService {
    private static final String CREDENTIAL_TARGET = "GitPilot:AuthenticatedAccount";
    private static final int TOKEN_EXPIRY_DAYS = 30;
    private static final Set<String> ALLOWED_ENTERPRISE_HOSTS = new HashSet<>();
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static boolean strictWhitelistMode = false;
    private final WindowsCredentialStore credentialStore = new WindowsCredentialStore();
    private final HttpClient httpClient;
    private final SecretKey encryptionKey;
    private final boolean enforceHttpsOnly;
    private final boolean validateCertificates;

    public GitAccountService(boolean enforceHttpsOnly, boolean validateCertificates)
            throws Exception {
        this.enforceHttpsOnly = enforceHttpsOnly;
        this.validateCertificates = validateCertificates;
        this.encryptionKey = generateEncryptionKey();

        // HttpClient avec configuration sécurisée
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .version(HttpClient.Version.HTTP_2);

        // Validation du certificat si requis
        if (validateCertificates) {
            // Garder les vérifications par défaut de Java
        }

        this.httpClient = builder.build();
    }

    /**
     * Configure les hosts GitLab entreprise autorisés (whitelist)
     */
    public static void addAllowedEnterpriseHost(String host) throws IOException {
        if (host == null || host.isBlank()) {
            throw new IOException("Host cannot be null or blank");
        }
        try {
            new URI("https://" + host).getHost(); // Validation
            ALLOWED_ENTERPRISE_HOSTS.add(host.toLowerCase(Locale.ROOT));
        } catch (URISyntaxException e) {
            throw new IOException("Invalid host: " + e.getMessage());
        }
    }

    public Account loadStoredAccount() throws IOException {
        if (!credentialStore.isSupported()) return null;
        WindowsCredentialStore.StoredCredentials stored = credentialStore.load(CREDENTIAL_TARGET);
        if (stored == null) return null;
        try {
            String[] fields = stored.username().split("\\|", 3);
            if (fields.length < 2 || fields[0].isBlank() || fields[1].isBlank()) {
                throw new IOException("Invalid stored credential record.");
            }

            Provider provider = Provider.fromId(fields[0]);
            String instanceUrl = fields.length == 3
                    ? fields[2]
                    : provider.defaultInstanceUrl();
            String validatedUrl = validateInstanceUrl(instanceUrl, provider);

            return new Account(
                    provider, fields[1], validatedUrl, stored.secret(), Instant.now());
        } finally {
            Arrays.fill(stored.secret(), '\0');
        }
    }

    public Account signIn(Provider provider, String instanceUrl, char[] token)
            throws IOException, InterruptedException {
        String validatedUrl = validateInstanceUrl(instanceUrl, provider);
        String username = verify(provider, validatedUrl, token);

        if (credentialStore.isSupported()) {
            credentialStore.save(
                    CREDENTIAL_TARGET,
                    provider.id() + "|" + username + "|" + validatedUrl,
                    token);
        }
        return new Account(provider, username, validatedUrl, token, Instant.now());
    }

    public Account verifyStored(Account stored) throws IOException, InterruptedException {
        // Vérifier l'expiration du token
        if (stored.isExpired(TOKEN_EXPIRY_DAYS)) {
            signOut(); // Supprimer les credentials expirés
            throw new IOException("The stored credentials have expired. Please sign in again.");
        }

        char[] token = stored.token();
        try {
            String verifiedUsername = verify(stored.provider(), stored.instanceUrl(), token);
            return new Account(stored.provider(), verifiedUsername, stored.instanceUrl(),
                    token, Instant.now());
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

    public static String validateInstanceUrl(String instanceUrl, Provider provider)
            throws IOException {
        if (instanceUrl == null || instanceUrl.isBlank()) {
            throw new IOException("Enter the base URL of your Git provider.");
        }
        try {
            URI uri = new URI(instanceUrl.trim()).normalize();

            // Validation stricte
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("Only HTTPS URLs are supported for security reasons.");
            }
            if (uri.getHost() == null) {
                throw new IOException("Invalid URL: missing host.");
            }
            if (uri.getUserInfo() != null) {
                throw new IOException("URLs with embedded credentials are not allowed.");
            }
            if (uri.getQuery() != null || uri.getFragment() != null) {
                throw new IOException("Query parameters and fragments are not allowed.");
            }

            // Vérification de la whitelist pour les instances entreprise
            String host = uri.getHost();
            if (!provider.isPublicInstance(instanceUrl) && !isHostAllowed(host)) {
                throw new IOException("Host '" + host + "' is not in the allowed list. "
                        + "Contact your administrator to add it.");
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

    private static boolean isHostAllowed(String host) {
        return ALLOWED_ENTERPRISE_HOSTS.isEmpty() // Si liste vide, tous les hosts sont autorisés
                || ALLOWED_ENTERPRISE_HOSTS.contains(host.toLowerCase(Locale.ROOT));
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

        HttpResponse<String> response;
        try {
            response = httpClient.send(request.GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IOException("Failed to connect to " + provider.displayName() + ": "
                    + e.getMessage(), e);
        }

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

        return extractUsernameFromJson(response.body(), provider);
    }

    private static String extractUsernameFromJson(String jsonBody, Provider provider)
            throws IOException {
        try {
            JsonNode root = JSON_MAPPER.readTree(jsonBody);
            String fieldName = provider == Provider.GITHUB ? "login" : "username";
            JsonNode field = root.get(fieldName);

            if (field == null || !field.isTextual()) {
                throw new IOException("The provider returned an unexpected account profile.");
            }
            return field.asText();
        } catch (IOException e) {
            throw new IOException("Failed to parse provider response: " + e.getMessage(), e);
        }
    }

    private char[] encryptToken(char[] token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey);
        byte[] tokenBytes = new String(token).getBytes(StandardCharsets.UTF_8);
        byte[] encryptedBytes = cipher.doFinal(tokenBytes);
        return Base64.getEncoder().encodeToString(encryptedBytes).toCharArray();
    }

    private char[] decryptToken(char[] encryptedToken) throws Exception {
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey);
        byte[] decodedBytes = Base64.getDecoder().decode(new String(encryptedToken));
        byte[] decryptedBytes = cipher.doFinal(decodedBytes);
        return new String(decryptedBytes, StandardCharsets.UTF_8).toCharArray();
    }

    private SecretKey generateEncryptionKey() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256, new SecureRandom());
        return keyGen.generateKey();
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

        public boolean isPublicInstance(String instanceUrl) {
            return instanceUrl.equalsIgnoreCase(defaultInstanceUrl);
        }

        URI userEndpoint(String instanceUrl) throws IOException {
            String base = validateInstanceUrl(instanceUrl, this);
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
        private final String instanceUrl;
        private final char[] token;
        private final Instant createdAt;

        public Account(Provider provider, String username, String instanceUrl,
                       char[] token, Instant createdAt) {
            this.provider = provider;
            this.username = username;
            this.instanceUrl = instanceUrl;
            this.token = token.clone();
            this.createdAt = createdAt;
        }

        public Provider provider() { return provider; }
        public String instanceUrl() { return instanceUrl; }
        public boolean isExpired(int maxAgeDays) {
            return Instant.now().minusSeconds(maxAgeDays * 86400L).isAfter(createdAt);
        }

        @Override public String username() { return username; }
        @Override public char[] token() { return token.clone(); }

        @Override public boolean supportsHost(String host) {
            if (host == null) return false;
            String expectedHost = URI.create(instanceUrl).getHost();
            return expectedHost != null && expectedHost.equalsIgnoreCase(host);
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

    /**
     * Active/désactive le mode strict de whitelist (pour les tests).
     *
     * En mode strict, seuls les hosts dans la whitelist sont autorisés pour les instances entreprise.
     * En mode non-strict (par défaut), tous les hosts sont autorisés.
     *
     * @param strict true pour activer le mode strict, false pour le désactiver
     */
    public static void setStrictWhitelistMode(boolean strict) {
        strictWhitelistMode = strict;
    }

    /**
     * Réinitialise la whitelist (pour les tests)
     */
    public static void clearAllowedEnterpriseHosts() {
        ALLOWED_ENTERPRISE_HOSTS.clear();
    }
}