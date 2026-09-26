package com.git.client;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Writes application diagnostics to the console and a rotating file, redacting embedded credentials. */
final class LogService implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger("com.git.client");
    private static final Pattern URL_CREDENTIALS =
            Pattern.compile("(?i)(https?://)[^/@\\s]+@");
    private static final Pattern GITHUB_TOKEN =
            Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b");
    private static final Path LOG_FILE = Path.of(
            System.getProperty("user.home"), ".gitdesk", "logs", "gitdesk.log");

    private final Handler consoleHandler;
    private final FileHandler fileHandler;

    LogService() {
        Handler initializedConsole = new ColoredConsoleHandler();
        FileHandler initializedFile = null;
        synchronized (LOGGER) {
            LOGGER.setUseParentHandlers(false);
            LOGGER.setLevel(Level.ALL);
            for (var handler : LOGGER.getHandlers()) {
                LOGGER.removeHandler(handler);
                handler.close();
            }
            LOGGER.addHandler(initializedConsole);
            try {
                Files.createDirectories(LOG_FILE.getParent());
                try (var ignored = Files.newOutputStream(LOG_FILE,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    // Ensure the diagnostic file exists before FileHandler acquires its lock.
                }
                initializedFile = new FileHandler(LOG_FILE.toString(), 1_000_000, 3, true);
                initializedFile.setLevel(Level.ALL);
                initializedFile.setFormatter(new DiagnosticFormatter());
                LOGGER.addHandler(initializedFile);
            } catch (IOException exception) {
                LOGGER.log(Level.SEVERE, "Could not initialize the application log file.", exception);
            }
        }
        consoleHandler = initializedConsole;
        fileHandler = initializedFile;
    }

    Path logFile() {
        return LOG_FILE;
    }

    void info(String message) {
        LOGGER.info(message);
    }

    void warning(String message) {
        LOGGER.warning(message);
    }

    void error(String message, Throwable error) {
        LOGGER.log(Level.SEVERE, message, error);
    }

    @Override
    public void close() {
        LOGGER.removeHandler(consoleHandler);
        consoleHandler.close();
        if (fileHandler != null) {
            LOGGER.removeHandler(fileHandler);
            fileHandler.close();
        }
    }

    private static final class DiagnosticFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            StringBuilder entry = new StringBuilder()
                    .append(Instant.ofEpochMilli(record.getMillis()))
                    .append(" [").append(record.getLevel()).append("] ")
                    .append(formatMessage(record)).append(System.lineSeparator());
            if (record.getThrown() != null) {
                StringWriter stackTrace = new StringWriter();
                record.getThrown().printStackTrace(new PrintWriter(stackTrace));
                entry.append(stackTrace);
            }
            return redact(entry.toString());
        }

        private String redact(String text) {
            String withoutUrlCredentials = URL_CREDENTIALS.matcher(text).replaceAll("$1[REDACTED]@");
            return GITHUB_TOKEN.matcher(withoutUrlCredentials).replaceAll("[REDACTED_TOKEN]");
        }
    }

    private static final class ColoredConsoleHandler extends Handler {
        private static final String RESET = "\u001B[0m";
        private static final String GREEN = "\u001B[32m";
        private static final String YELLOW = "\u001B[33m";
        private static final String RED = "\u001B[31m";
        private final DiagnosticFormatter formatter = new DiagnosticFormatter();

        private ColoredConsoleHandler() {
            setLevel(Level.ALL);
        }

        @Override
        public synchronized void publish(LogRecord record) {
            if (!isLoggable(record)) {
                return;
            }
            String message = formatter.format(record);
            String color = colorFor(record, message);
            System.out.print(color.isEmpty() ? message : color + message + RESET);
            System.out.flush();
        }

        @Override
        public void flush() {
            System.out.flush();
        }

        @Override
        public void close() {
            flush();
        }

        private String colorFor(LogRecord record, String message) {
            if (record.getLevel().intValue() >= Level.SEVERE.intValue()
                    || message.toLowerCase().contains("failed")) {
                return RED;
            }
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                return YELLOW;
            }
            String normalized = message.toLowerCase();
            if (normalized.contains("completed successfully")
                    || normalized.contains("configured successfully")) {
                return GREEN;
            }
            return "";
        }
    }
}
