module com.git.client {
    requires javafx.graphics;
    requires javafx.controls;
    requires org.eclipse.jgit;
    requires java.prefs;
    requires java.logging;
    requires java.net.http;
    exports com.git.client;
    exports com.git.client.ui;
    exports com.git.client.git;
    exports com.git.client.platform;

}