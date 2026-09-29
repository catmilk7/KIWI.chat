package com.vgu.client.ui.controllers.auth;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.Label;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.event.ActionEvent;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

import java.io.IOException;
import java.util.Random;

public class secure {

    @FXML
    private TextField usernameField;
    @FXML
    private PasswordField passwordField;
    @FXML
    private PasswordField confirmField;
    @FXML
    private Label errorMessage;
    @FXML
    private Label quoteMessage;

    private final String[] quotes = {
            "Secure. Private. Yours.", // English
            "Sécurisé. Confidentiel. À vous.", // French
            "Sicher. Privat. Deins.", // German
            "Säkert. Privat. Ditt.", // Swedish
            "Надежно. Конфиденциально. Ваше.", // Russian
            "Bezpieczne. Prywatne. Twoje.", // Polish
            "Aman. Pribadi. Milik Anda.", // Indonesian
            "Bảo mật. Riêng tư. Của bạn." // Vietnamese
    };

    private int currentIndex;

    @FXML
    public void initialize() {
        if (errorMessage != null) {
            errorMessage.setVisible(false);
        }

        Random random = new Random();

        if (quoteMessage != null && quotes.length > 0) {
            quoteMessage.setText(quotes[random.nextInt(quotes.length)]);
        }

        Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(5), event -> {
            if (quoteMessage != null && quotes.length > 1) {
                FadeTransition fadeOut = new FadeTransition(Duration.millis(300), quoteMessage);
                fadeOut.setFromValue(1.0);
                fadeOut.setToValue(0.0);

                fadeOut.setOnFinished(e -> {
                    int nextIndex;
                    String currentText = quoteMessage.getText();

                    do {
                        nextIndex = random.nextInt(quotes.length);
                    } while (quotes[nextIndex].equals(currentText));
                    // Note: Same length = itself!

                    quoteMessage.setText(quotes[nextIndex]);

                    FadeTransition fadeIn = new FadeTransition(Duration.millis(300), quoteMessage);
                    fadeIn.setFromValue(0.0);
                    fadeIn.setToValue(1.0);
                    fadeIn.play();
                });

                fadeOut.play();
            }
        }));

        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
    }

    @FXML
    private void switchRegister(ActionEvent event) {
        switchScene(event, "/com/vgu/client/ui/views/auth/register.view.fxml");
    }

    @FXML
    private void switchLogin(ActionEvent event) {
        switchScene(event, "/com/vgu/client/ui/views/auth/login.view.fxml");
    }

    private void switchScene(ActionEvent event, String fxmlFile) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlFile));
            Parent root = loader.load();

            root.setOpacity(0.0);

            Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();

            boolean isFullScreen = stage.isFullScreen();
            boolean isMaximized = stage.isMaximized();
            double width = stage.getWidth();
            double height = stage.getHeight();
            double x = stage.getX();
            double y = stage.getY();

            Scene scene = new Scene(root);
            stage.setScene(scene);

            if (isFullScreen) {
                stage.setFullScreen(true);
            } else if (isMaximized) {
                stage.setMaximized(true);
            } else {
                stage.setWidth(width);
                stage.setHeight(height);
                stage.setX(x);
                stage.setY(y);
            }

            FadeTransition fade = new FadeTransition(Duration.millis(400), root);
            fade.setFromValue(0.0);
            fade.setToValue(1.0);
            fade.play();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void handleLogin(ActionEvent event) {
        String username = usernameField.getText();
        String password = passwordField.getText();

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            errorMessage.setText("Please enter both a username and password.");
            errorMessage.setVisible(true);
        } else {
            errorMessage.setVisible(false);
            System.out.println("Logged in as: " + username);
        }
    }

    @FXML
    private void handleRegister(ActionEvent event) {
        String username = usernameField.getText();
        String password = passwordField.getText();
        String confirmPassword = confirmField.getText();

        if (username == null || username.isBlank() || password == null || password.isBlank() || confirmPassword == null || confirmPassword.isBlank()){
            errorMessage.setText("Please fill in all required fields.");
            errorMessage.setVisible(true);
        } else if (!password.equals(confirmPassword)) {
            errorMessage.setText("Passwords do not match. Please try again.");
            errorMessage.setVisible(true);
        } else {
            errorMessage.setVisible(false);
            switchLogin(event);
            System.out.println("Account registered successfully.");
        }
    }

    // TO DO:
}