package com.git.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@EnabledOnOs(OS.WINDOWS)
class WindowsCredentialStoreTest {
    @Test
    void savesAndLoadsCredentialsUsingWindowsCredentialManager() throws IOException {
        WindowsCredentialStore store = new WindowsCredentialStore();
        String target = "GitDesk:Test:" + UUID.randomUUID();
        String username = "gitdesk-test-user";
        char[] secret = "gitdesk-test-secret".toCharArray();
        try {
            store.save(target, username, secret);
            WindowsCredentialStore.StoredCredentials stored = store.load(target);
            assertNotNull(stored);
            try {
                assertEquals(username, stored.username());
                assertArrayEquals(secret, stored.secret());
            } finally {
                if (stored != null) {
                    Arrays.fill(stored.secret(), '\0');
                }
            }
        } finally {
            Arrays.fill(secret, '\0');
            store.delete(target);
        }
    }
}
