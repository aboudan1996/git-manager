package com.git.client.platform;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.List;

/** Stores Git credentials in the current Windows user's Credential Manager. */
public final class WindowsCredentialStore {
    private static final String POWERSHELL_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $ProgressPreference = 'SilentlyContinue'
            [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding
            [Console]::InputEncoding = New-Object System.Text.UTF8Encoding
            $source = @'
            using System;
            using System.Runtime.InteropServices;
            public static class GitDeskWinCredential {
                [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
                public struct CREDENTIAL {
                    public UInt32 Flags;
                    public UInt32 Type;
                    public string TargetName;
                    public string Comment;
                    public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
                    public UInt32 CredentialBlobSize;
                    public IntPtr CredentialBlob;
                    public UInt32 Persist;
                    public UInt32 AttributeCount;
                    public IntPtr Attributes;
                    public string TargetAlias;
                    public string UserName;
                }

                [DllImport("Advapi32.dll", EntryPoint = "CredWriteW", CharSet = CharSet.Unicode, SetLastError = true)]
                public static extern bool CredWrite(ref CREDENTIAL credential, UInt32 flags);

                [DllImport("Advapi32.dll", EntryPoint = "CredReadW", CharSet = CharSet.Unicode, SetLastError = true)]
                public static extern bool CredRead(string target, UInt32 type, UInt32 flags, out IntPtr credential);

                [DllImport("Advapi32.dll", EntryPoint = "CredDeleteW", CharSet = CharSet.Unicode, SetLastError = true)]
                public static extern bool CredDelete(string target, UInt32 type, UInt32 flags);

                [DllImport("Advapi32.dll", EntryPoint = "CredFree")]
                public static extern void CredFree(IntPtr credential);
            }
            '@
            Add-Type -TypeDefinition $source
            $payload = [Console]::In.ReadLine() | ConvertFrom-Json
            if ($payload.action -eq 'write') {
                $bytes = [Convert]::FromBase64String([string]$payload.secret)
                $blob = [IntPtr]::Zero
                try {
                    $blob = [Runtime.InteropServices.Marshal]::AllocCoTaskMem($bytes.Length)
                    [Runtime.InteropServices.Marshal]::Copy($bytes, 0, $blob, $bytes.Length)
                    $credential = New-Object GitDeskWinCredential+CREDENTIAL
                    $credential.Type = 1
                    $credential.TargetName = [string]$payload.target
                    $credential.CredentialBlobSize = [uint32]$bytes.Length
                    $credential.CredentialBlob = $blob
                    $credential.Persist = 2
                    $credential.UserName = [string]$payload.username
                    if (-not [GitDeskWinCredential]::CredWrite([ref]$credential, 0)) {
                        throw "Windows Credential Manager write failed with code $([Runtime.InteropServices.Marshal]::GetLastWin32Error())."
                    }
                    [Console]::WriteLine('OK')
                } finally {
                    [Array]::Clear($bytes, 0, $bytes.Length)
                    if ($blob -ne [IntPtr]::Zero) {
                        [Runtime.InteropServices.Marshal]::FreeCoTaskMem($blob)
                    }
                }
            } elseif ($payload.action -eq 'read') {
                $credentialPointer = [IntPtr]::Zero
                if (-not [GitDeskWinCredential]::CredRead([string]$payload.target, 1, 0, [ref]$credentialPointer)) {
                    $errorCode = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
                    if ($errorCode -eq 1168) {
                        [Console]::WriteLine('NOT_FOUND')
                    } else {
                        throw "Windows Credential Manager read failed with code $errorCode."
                    }
                } else {
                    $bytes = $null
                    try {
                        $credential = [Runtime.InteropServices.Marshal]::PtrToStructure(
                            $credentialPointer, [type][GitDeskWinCredential+CREDENTIAL])
                        $bytes = New-Object byte[] ([int]$credential.CredentialBlobSize)
                        [Runtime.InteropServices.Marshal]::Copy($credential.CredentialBlob, $bytes, 0, $bytes.Length)
                        [Console]::WriteLine([string]$credential.UserName)
                        [Console]::WriteLine([Convert]::ToBase64String($bytes))
                    } finally {
                        if ($null -ne $bytes) {
                            [Array]::Clear($bytes, 0, $bytes.Length)
                        }
                        [GitDeskWinCredential]::CredFree($credentialPointer)
                    }
                }
            } elseif ($payload.action -eq 'delete') {
                if (-not [GitDeskWinCredential]::CredDelete([string]$payload.target, 1, 0)) {
                    $errorCode = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
                    if ($errorCode -eq 1168) {
                        [Console]::WriteLine('NOT_FOUND')
                    } else {
                        throw "Windows Credential Manager delete failed with code $errorCode."
                    }
                } else {
                    [Console]::WriteLine('OK')
                }
            } else {
                throw 'Unsupported credential operation.'
            }
            """;

    public boolean isSupported() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    public String targetFor(Path repository) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    repository.toAbsolutePath().normalize().toString()
                            .toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return "GitDesk:Repository:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    public void save(String target, String username, char[] secret) throws IOException {
        byte[] secretBytes = new String(secret).getBytes(StandardCharsets.UTF_8);
        try {
            String request = "{\"action\":\"write\",\"target\":\"" + jsonEscape(target)
                    + "\",\"username\":\"" + jsonEscape(username)
                    + "\",\"secret\":\"" + Base64.getEncoder().encodeToString(secretBytes) + "\"}";
            String result = invoke(request);
            if (!result.lines().anyMatch("OK"::equals)) {
                throw new IOException("Windows Credential Manager did not confirm saving the credentials.");
            }
        } finally {
            Arrays.fill(secretBytes, (byte) 0);
        }
    }

    public StoredCredentials load(String target) throws IOException {
        String result = invoke("{\"action\":\"read\",\"target\":\"" + jsonEscape(target) + "\"}");
        List<String> lines = result.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
        if (lines.contains("NOT_FOUND")) {
            return null;
        }
        for (int index = lines.size() - 1; index > 0; index--) {
            byte[] secretBytes;
            try {
                secretBytes = Base64.getDecoder().decode(lines.get(index));
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            try {
                return new StoredCredentials(lines.get(index - 1),
                        new String(secretBytes, StandardCharsets.UTF_8).toCharArray());
            } finally {
                Arrays.fill(secretBytes, (byte) 0);
            }
        }
        throw new IOException("Windows Credential Manager returned an invalid credential record.");
    }

    public void delete(String target) throws IOException {
        invoke("{\"action\":\"delete\",\"target\":\"" + jsonEscape(target) + "\"}");
    }

    private String invoke(String request) throws IOException {
        String encodedScript = Base64.getEncoder().encodeToString(
                POWERSHELL_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-EncodedCommand", encodedScript).redirectErrorStream(true).start();
        try (OutputStream input = process.getOutputStream()) {
            input.write((request + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Could not access Windows Credential Manager (PowerShell exit "
                        + exitCode + ").");
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while accessing Windows Credential Manager.", exception);
        }
        return output;
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    public record StoredCredentials(String username, char[] secret) {
    }
}
