package com.git.client.ui;

import com.git.client.platform.ApplicationEvent;

import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;

import java.net.URL;
import java.util.function.BiConsumer;

final class DialogStyler {
    private DialogStyler() {
    }

    static void apply(Dialog<?> dialog, BiConsumer<ApplicationEvent, String> eventLogger) {
        URL stylesheet = DialogStyler.class.getResource("styles.css");
        if (stylesheet == null) {
            throw new IllegalStateException("Could not load the GitPilot stylesheet.");
        }
        dialog.getDialogPane().getStylesheets().add(stylesheet.toExternalForm());
        dialog.getDialogPane().getStyleClass().add("gitdesk-dialog");
        // Centralize modal lifecycle logging so every styled dialog follows the same policy.
        dialog.addEventHandler(DialogEvent.DIALOG_SHOWN, event ->
                eventLogger.accept(ApplicationEvent.DIALOG_OPENED, dialog.getTitle()));
        dialog.addEventHandler(DialogEvent.DIALOG_HIDDEN, event ->
                eventLogger.accept(ApplicationEvent.DIALOG_CLOSED, dialog.getTitle()));
    }
}
