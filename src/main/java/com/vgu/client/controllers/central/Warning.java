package com.vgu.client.controllers.central;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

public class Warning extends Handling {

    @FXML private Label warningMessageLabel;
    @FXML private Label warningSubtextLabel;
    @FXML private Button cancelBtn;
    @FXML private Button confirmBtn;

    private Runnable onConfirmCallback;

    // For error, use this function
    public void setupAsError(String errorMessage, String subtext) {
        if (warningMessageLabel != null) warningMessageLabel.setText(errorMessage);
        if (warningSubtextLabel != null) warningSubtextLabel.setText(subtext);
        if (cancelBtn != null) {
            cancelBtn.setVisible(false);
            cancelBtn.setManaged(false);
        }
    }

    // For action, use this function instead
    public void setupAsConfirm(String message, String subtext, Runnable onConfirm) {
        if (warningMessageLabel != null) warningMessageLabel.setText(message);
        if (warningSubtextLabel != null) warningSubtextLabel.setText(subtext);
        this.onConfirmCallback = onConfirm;
    }

    @FXML
    private void handleConfirm() {
        if (onConfirmCallback != null) {
            onConfirmCallback.run();
        }
        handleClose();
    }
}