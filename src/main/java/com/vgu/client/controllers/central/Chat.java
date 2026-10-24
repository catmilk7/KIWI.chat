package com.vgu.client.controllers.central;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import org.kordamp.ikonli.javafx.FontIcon;

public class Chat {
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