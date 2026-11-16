package com.vgu.client.controllers.auth;

import com.vgu.client.controllers.utils.QuoteRotator;
import com.vgu.client.controllers.utils.SceneNavigator;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

import java.util.Arrays;

/** Controller for the login and register screens. Reads input and shows results; the work is in {@link AuthService}. */
public class AuthController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmField;
    @FXML private Label errorMessage;
    @FXML private Label quoteMessage;

    private final AuthService auth = new AuthService();

    @FXML
    public void initialize() {
        showError(null);
        QuoteRotator.start(quoteMessage);
    }

    @FXML
    private void switchRegister(ActionEvent event) {
        SceneNavigator.goTo(event, SceneNavigator.REGISTER);
    }

    @FXML
    private void switchLogin(ActionEvent event) {
        SceneNavigator.goTo(event, SceneNavigator.LOGIN);
    }

    @FXML
    private void handleLogin(ActionEvent event) {
        String username = usernameField.getText();
        char[] password = passwordField.getText().toCharArray();

        if (isBlank(username) || password.length == 0) {
            showError("Please enter both a username and password.");
            return;
        }
        runInBackground(event, () -> Session.start(auth.login(username, password)), password,
                () -> SceneNavigator.goTo(event, SceneNavigator.CHAT));
    }

    @FXML
    private void handleRegister(ActionEvent event) {
        String username = usernameField.getText();
        String confirm = confirmField.getText();
        char[] password = passwordField.getText().toCharArray();

        if (isBlank(username) || password.length == 0 || isBlank(confirm)) {
            showError("Please fill in all required fields.");
        } else if (AuthService.registrationProblem(username, password) != null) {
            showError(AuthService.registrationProblem(username, password));
        } else if (!new String(password).equals(confirm)) {
            showError("Passwords do not match. Please try again.");
        } else {
            runInBackground(event, () -> auth.register(username, password), password,
                    () -> SceneNavigator.goTo(event, SceneNavigator.LOGIN));
        }
    }

    /** Key generation and keystore access are slow, so keep them off the JavaFX thread. */
    private void runInBackground(ActionEvent event, AuthAction action, char[] password, Runnable onSuccess) {
        Node source = (Node) event.getSource();
        source.setDisable(true);
        showError(null);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                try {
                    action.run();
                    return null;
                } finally {
                    Arrays.fill(password, '\0');
                }
            }
        };
        task.setOnSucceeded(e -> onSuccess.run());
        task.setOnFailed(e -> {
            source.setDisable(false);
            Throwable error = task.getException();
            showError(error instanceof AuthException ? error.getMessage() : "Something went wrong. Please try again.");
        });

        Thread thread = new Thread(task, "auth");
        thread.setDaemon(true);
        thread.start();
    }

    private void showError(String message) {
        if (errorMessage == null) {
            return;
        }
        errorMessage.setText(message);
        errorMessage.setVisible(message != null);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @FunctionalInterface
    private interface AuthAction {
        void run() throws AuthException;
    }
}
