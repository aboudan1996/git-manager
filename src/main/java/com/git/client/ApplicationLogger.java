package com.git.client;

import java.nio.file.Path;

/** Logging port used by application code; implementations own output and redaction. */
interface ApplicationLogger extends AutoCloseable {
    Path logFile();
    void info(String message);
    void warning(String message);
    void error(String message, Throwable error);
    void event(ApplicationEvent event, String detail);
    @Override void close();
}
