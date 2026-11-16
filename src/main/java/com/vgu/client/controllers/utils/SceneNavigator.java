package com.vgu.client.controllers.utils;

import javafx.animation.FadeTransition;
import javafx.event.ActionEvent;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Swaps the root screen of the current window with a fade, keeping its size and position. */
public final class SceneNavigator {

    public static final String LOGIN = "/com/vgu/client/ui/views/auth/login.view.fxml";
    public static final String REGISTER = "/com/vgu/client/ui/views/auth/register.view.fxml";
    public static final String CHAT = "/com/vgu/client/ui/views/chat/chat.view.fxml";

    private static final Duration FADE = Duration.millis(400);

    private SceneNavigator() {
    }

    public static void goTo(ActionEvent event, String fxml) {
        Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
        Parent root;
        try {
            root = FXMLLoader.load(SceneNavigator.class.getResource(fxml));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load " + fxml, e);
        }
        root.setOpacity(0.0);

        boolean fullScreen = stage.isFullScreen();
        boolean maximized = stage.isMaximized();
        double width = stage.getWidth(), height = stage.getHeight();
        double x = stage.getX(), y = stage.getY();

        stage.setScene(new Scene(root));

        if (fullScreen) {
            stage.setFullScreen(true);
        } else if (maximized) {
            stage.setMaximized(true);
        } else {
            stage.setWidth(width);
            stage.setHeight(height);
            stage.setX(x);
            stage.setY(y);
        }

        FadeTransition fade = new FadeTransition(FADE, root);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.play();
    }
}
