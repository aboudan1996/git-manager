package com.git.client;

import javafx.scene.control.Dialog;

import java.net.URL;

final class DialogStyler {
    private DialogStyler() {
    }

    static void apply(Dialog<?> dialog) {
        URL stylesheet = DialogStyler.class.getResource("styles.css");
        if (stylesheet == null) {
            throw new IllegalStateException("Could not load the GitPilot stylesheet.");
        }
        dialog.getDialogPane().getStylesheets().add(stylesheet.toExternalForm());
        dialog.getDialogPane().getStyleClass().add("gitdesk-dialog");
    }
}
