package com.git.client.ui;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

public final class ToastService {

    public enum Level {
        INFO(FontAwesomeSolid.INFO_CIRCLE, Duration.seconds(3), "toast-info"),
        SUCCESS(FontAwesomeSolid.CHECK_CIRCLE, Duration.seconds(3), "toast-success"),
        WARNING(FontAwesomeSolid.EXCLAMATION_TRIANGLE, Duration.seconds(5), "toast-warning"),
        ERROR(FontAwesomeSolid.TIMES_CIRCLE, Duration.seconds(8), "toast-error");

        private final Ikon icon;
        private final Duration duration;
        private final String styleClass;

        Level(Ikon icon, Duration duration, String styleClass) {
            this.icon = icon;
            this.duration = duration;
            this.styleClass = styleClass;
        }
    }

    private static final int MAX_VISIBLE = 4;
    private static final double MAX_TOAST_WIDTH = 380;

    private final VBox container = new VBox(8);

    public ToastService() {
        container.setPickOnBounds(false);                 // les clics passent à travers les zones vides
        container.setAlignment(Pos.BOTTOM_RIGHT);
        container.setPadding(new Insets(16));
        container.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(container, Pos.BOTTOM_RIGHT);
    }

    /** Couche à superposer au contenu de l'application (dernier enfant d'un StackPane). */
    public Node overlay() {
        return container;
    }

    public void info(String message)    { show(Level.INFO, message, null); }
    public void success(String message) { show(Level.SUCCESS, message, null); }
    public void warning(String message) { show(Level.WARNING, message, null); }
    public void error(String message)   { show(Level.ERROR, message, null); }

    public void error(String title, Throwable failure) {
        String reason = failure == null || failure.getMessage() == null
                ? "" : failure.getMessage().trim();
        error(reason.isEmpty() ? title : title + ": " + reason);
    }

    /** Affiche un toast. Peut être appelé depuis n'importe quel thread. */
    public void show(Level level, String message, Duration duration) {
        if (message == null || message.isBlank()) return;
        Duration shownFor = duration != null ? duration : level.duration;
        if (Platform.isFxApplicationThread()) {
            display(level, message.trim(), shownFor);
        } else {
            Platform.runLater(() -> display(level, message.trim(), shownFor));
        }
    }

    private void display(Level level, String message, Duration duration) {
        FontIcon icon = new FontIcon(level.icon);
        icon.getStyleClass().add("toast-icon");

        Label text = new Label(message);
        text.getStyleClass().add("toast-label");
        text.setWrapText(true);
        text.setMaxWidth(MAX_TOAST_WIDTH - 60);

        HBox toast = new HBox(10, icon, text);
        toast.getStyleClass().addAll("toast", level.styleClass);
        toast.setAlignment(Pos.CENTER_LEFT);
        toast.setMaxWidth(MAX_TOAST_WIDTH);
        toast.setAccessibleText(level.name().toLowerCase() + ": " + message);

        PauseTransition timer = new PauseTransition(duration);
        timer.setOnFinished(event -> dismiss(toast));
        toast.setOnMouseEntered(event -> timer.pause());   // lecture tranquille au survol
        toast.setOnMouseExited(event -> timer.play());
        toast.setOnMouseClicked(event -> {                 // un clic ferme immédiatement
            timer.stop();
            dismiss(toast);
        });

        container.getChildren().add(toast);
        while (container.getChildren().size() > MAX_VISIBLE) {
            container.getChildren().remove(0);             // supprime le plus ancien
        }

        toast.setOpacity(0);
        toast.setTranslateY(12);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(180), toast);
        fadeIn.setToValue(1);
        TranslateTransition slideIn = new TranslateTransition(Duration.millis(180), toast);
        slideIn.setToY(0);
        new ParallelTransition(fadeIn, slideIn).play();
        timer.play();
    }

    private void dismiss(Node toast) {
        if (toast.getProperties().putIfAbsent("closing", Boolean.TRUE) != null) return;
        FadeTransition fadeOut = new FadeTransition(Duration.millis(250), toast);
        fadeOut.setToValue(0);
        fadeOut.setOnFinished(event -> container.getChildren().remove(toast));
        fadeOut.play();
    }
}