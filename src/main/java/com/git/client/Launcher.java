package com.git.client;

import com.git.client.ui.GitDeskApplication;
import javafx.application.Application;

/** JavaFX-safe entry point that delegates startup to the UI application package. */
public class Launcher {
    public static void main(String[] args) {
        Application.launch(GitDeskApplication.class, args);
    }
}
