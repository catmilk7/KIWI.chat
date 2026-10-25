package com.vgu.client.controllers.central;

import com.vgu.client.database_dummy.DummyDatabase;
import com.vgu.client.models.identity.User_dummy;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

public class Settings_user extends Handling {

    @FXML private TextField usernameField;
    @FXML private Label uuidLabel;
    @FXML private TextArea bioTextArea;
    @FXML private PasswordField newPasswordField;

    @Override
    protected void onModalInitialized() {
        User_dummy currentUser = DummyDatabase.getCurrentUser();
        if (currentUser != null) {
            if (usernameField != null) usernameField.setText(currentUser.getDisplayName());
            if (uuidLabel != null) uuidLabel.setText(currentUser.getId());
            if (bioTextArea != null) bioTextArea.setText(currentUser.getBio());
        }
    }

    @FXML
    private void handleSaveChanges() {
        handleClose();
    }
}