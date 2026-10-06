package com.vgu.client.ui.controllers.auth;

import javafx.fxml.FXML;
import javafx.scene.*;
import javafx.scene.control.*;
import org.kordamp.ikonli.javafx.FontIcon;

public class chat {
    @FXML
    private Button toggleBell;
    @FXML
    private FontIcon bell;

    private boolean isToggled = false;

    @FXML
    private void handleNotification() {
        isToggled = !isToggled;

        if (isToggled) {
            toggleBell.setText("Unmute");
            bell.setIconLiteral("fas-bell");
        } else {
            toggleBell.setText("Mute");
            bell.setIconLiteral("fas-bell-slash");
        }
    }
}