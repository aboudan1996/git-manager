package com.git.client.ui;

import com.git.client.platform.ApplicationEvent;

import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.DialogPane;
import javafx.geometry.Pos;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

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
        dialog.initStyle(StageStyle.UNDECORATED);
        dialog.getDialogPane().getStylesheets().add(stylesheet.toExternalForm());
        dialog.getDialogPane().getStyleClass().add("gitdesk-dialog");
        dialog.addEventHandler(DialogEvent.DIALOG_SHOWING, event -> installTitleBar(dialog));
        // Centralize modal lifecycle logging so every styled dialog follows the same policy.
        dialog.addEventHandler(DialogEvent.DIALOG_SHOWN, event ->
                eventLogger.accept(ApplicationEvent.DIALOG_OPENED, dialog.getTitle()));
        dialog.addEventHandler(DialogEvent.DIALOG_HIDDEN, event ->
                eventLogger.accept(ApplicationEvent.DIALOG_CLOSED, dialog.getTitle()));
    }

    private static void installTitleBar(Dialog<?> dialog) {
        DialogPane dialogPane = dialog.getDialogPane();
        Node existingHeader = dialogPane.getHeader();
        String headerText = dialogPane.getHeaderText();
        if (existingHeader == null && headerText != null && !headerText.isBlank()) {
            existingHeader = new Label(headerText);
        }
        Label title = new Label(dialog.getTitle());
        title.getStyleClass().add("dialog-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        FontIcon closeIcon = new FontIcon(FontAwesomeSolid.TIMES);
        closeIcon.getStyleClass().add("dialog-close-icon");
        Button close = new Button();
        close.setGraphic(closeIcon);
        close.setAccessibleText("Close");
        close.setTooltip(new javafx.scene.control.Tooltip("Close"));
        close.getStyleClass().add("dialog-close-button");
        close.setOnAction(action -> dialog.close());

        HBox titleBar = new HBox(title, spacer, close);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.getStyleClass().add("dialog-title-bar");
        VBox header = existingHeader == null ? new VBox(titleBar) : new VBox(titleBar, existingHeader);
        header.getStyleClass().add("dialog-header");
        dialogPane.setHeaderText(null);
        dialogPane.setHeader(header);

        double[] dragOffset = new double[2];
        titleBar.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (isButtonTarget(event.getTarget())) return;
            Stage window = (Stage) dialogPane.getScene().getWindow();
            dragOffset[0] = event.getScreenX() - window.getX();
            dragOffset[1] = event.getScreenY() - window.getY();
        });
        titleBar.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            if (isButtonTarget(event.getTarget())) return;
            Stage window = (Stage) dialogPane.getScene().getWindow();
            window.setX(event.getScreenX() - dragOffset[0]);
            window.setY(event.getScreenY() - dragOffset[1]);
        });
    }

    private static boolean isButtonTarget(Object target) {
        Node node = target instanceof Node selected ? selected : null;
        while (node != null) {
            if (node instanceof Button) return true;
            node = node.getParent();
        }
        return false;
    }
}
