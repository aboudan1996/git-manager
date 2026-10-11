module com.git.client {
    requires javafx.graphics;
    requires javafx.controls;
    requires org.eclipse.jgit;
    requires java.prefs;
    requires java.logging;
    requires java.net.http;
    requires com.fasterxml.jackson.databind;
    exports com.git.client;
    exports com.git.client.ui;
    exports com.git.client.git;
    exports com.git.client.platform;
    requires javafx.base;

    // JUnit et test dependencies
    requires transitive org.junit.jupiter.api;
    requires transitive org.junit.platform.commons;

    // Exporter les packages publics

    // Ouvrir pour les tests
    opens com.git.client.platform to org.junit.platform.commons, org.junit.jupiter.engine;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.fontawesome5;


}