package com.vgu.client.controllers.central;

import com.vgu.client.controllers.auth.Session;
import com.vgu.client.models.storage.AccountDao;
import com.vgu.client.models.network.ApiException;
import com.vgu.client.models.network.FileService;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.FileChooser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;

public class Settings_user extends Handling {

    @FXML private Label usernameLabel;
    @FXML private Label uuidLabel;
    @FXML private PasswordField newPasswordField;
    @FXML private ImageView userAvatarImg;

    private static final long MAX_AVATAR_BYTES = 2 * 1024 * 1024;

    @Override
    protected void onModalInitialized() {
        Session session = Session.current();
        if (usernameLabel != null) usernameLabel.setText(session.handle());
        var avatar = session.account().avatar();
        if (avatar != null) showAvatar(avatar.image());
        if (uuidLabel != null) {
            uuidLabel.setText(session.userId() == AccountDao.LOCAL_USER_ID
                    ? "Not registered on a server yet"
                    : String.valueOf(session.userId()));
        }
    }

    /** Picks an image, uploads it encrypted to the server, and keeps a local copy as the avatar. */
    @FXML
    private void handleChooseAvatar() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose an avatar");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif"));
        File file = chooser.showOpenDialog(modalCard.getScene().getWindow());
        if (file == null) return;

        Session session = Session.current();
        if (!session.online()) {
            showError("You are offline", "Log in again once the server is reachable.");
            return;
        }

        Task<byte[]> task = new Task<>() {
            @Override
            protected byte[] call() throws Exception {
                if (file.length() > MAX_AVATAR_BYTES) {
                    throw new IllegalArgumentException("Please choose an image smaller than 2 MB.");
                }
                byte[] image = Files.readAllBytes(file.toPath());
                if (new Image(new ByteArrayInputStream(image)).isError()) {
                    throw new IllegalArgumentException("That file is not a readable image.");
                }
                FileService.FileRef ref = session.files().upload(image);
                session.account().saveAvatar(image, ref.fileId(), ref.keyBase64());
                return image;
            }
        };
        task.setOnSucceeded(e -> showAvatar(task.getValue()));
        task.setOnFailed(e -> {
            Throwable error = task.getException();
            showError("Avatar upload failed", error instanceof ApiException || error instanceof IllegalArgumentException
                    ? error.getMessage() : "Something went wrong. Please try again.");
        });
        Thread thread = new Thread(task, "avatar-upload");
        thread.setDaemon(true);
        thread.start();
    }

    private void showAvatar(byte[] image) {
        if (userAvatarImg != null && image != null) {
            userAvatarImg.setImage(new Image(new ByteArrayInputStream(image)));
        }
    }

    @FXML
    private void handleSaveChanges() {
        handleClose();
    }
}
