package com.vgu.client.controllers.central;

import com.vgu.client.database_dummy.DummyDatabase;
import com.vgu.client.models.identity.User_dummy;
import com.vgu.client.models.messaging.Conversation_dummy;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.util.List;

public class Settings_group extends Handling {

    @FXML private TextField groupNameField;
    @FXML private Label groupIdLabel;
    @FXML private VBox memberListContainer;

    @FXML private Label inviteCodeLabel;
    @FXML private Label countdownLabel;
    @FXML private Button regenCodeBtn;

    @FXML private TextField inviteCodeInputField;

    private Conversation_dummy targetGroup;
    private Timeline countdownTimeline;
    private int remainingSeconds;

    public void setTargetGroup(Conversation_dummy group) {
        this.targetGroup = group;
        populateGroupInfo();
    }

    @Override
    protected void onModalInitialized() {
        populateGroupInfo();

        List<User_dummy> allUsers = DummyDatabase.getAllUsers();
        User_dummy currentUser = DummyDatabase.getCurrentUser();

        if (memberListContainer != null) {
            memberListContainer.getChildren().clear();

            for (int i = 0; i < allUsers.size(); i++) {
                User_dummy user = allUsers.get(i);
                if (currentUser != null && user.getId().equals(currentUser.getId())) continue;

                if (i % 2 == 0) {
                    memberListContainer.getChildren().add(buildMemberRow(user));
                }
            }
        }

        if (inviteCodeLabel != null) {
            generateNewInviteCode();
        }
    }

    private void populateGroupInfo() {
        if (targetGroup != null) {
            if (groupNameField != null) groupNameField.setText(targetGroup.getTitle());
            if (groupIdLabel != null) groupIdLabel.setText(targetGroup.getId());
        }
    }

    @FXML
    private void handleRegenerateCode() {
        generateNewInviteCode();
    }

    private void generateNewInviteCode() {
        startCountdown();
    }

    private void generateRandomCode() {
    }

    private void startCountdown() {
        if (countdownTimeline != null) {
            countdownTimeline.stop();
        }
        remainingSeconds = 3600*12;
        updateCountdownDisplay();

        countdownTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            remainingSeconds--;
            if (remainingSeconds <= 0) {
                remainingSeconds = 0;
                updateCountdownDisplay();
                if (inviteCodeLabel != null) {
                    inviteCodeLabel.setText("EXPIRED");
                }
                if (countdownLabel != null) {
                    countdownLabel.setText("Code expired. Please regenerate.");
                }
                countdownTimeline.stop();
            } else {
                updateCountdownDisplay();
            }
        }));
        countdownTimeline.setCycleCount(Timeline.INDEFINITE);
        countdownTimeline.play();
    }

    private void updateCountdownDisplay() {
        if (countdownLabel != null) {
            int hours = remainingSeconds / 3600;
            int minutes = (remainingSeconds % 3600) / 60;
            int seconds = remainingSeconds % 60;
            countdownLabel.setText(String.format("Expires in %02d:%02d:%02d", hours, minutes, seconds));
        }
    }

    private HBox buildMemberRow(User_dummy user) {
        HBox row = new HBox(12.0);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPrefHeight(48.0);
        row.setStyle("-fx-padding: 6 12; -fx-background-color: -color-bg-default; -fx-background-radius: 8; -fx-border-color: #f1f5f9; -fx-border-radius: 8;");

        ImageView avatar = createAvatar(36.0);
        Label nameLabel = new Label(user.getUsername());
        nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button actionBtn = new Button("Kick");
        actionBtn.setMinWidth(Region.USE_PREF_SIZE);
        actionBtn.setStyle("-fx-background-color: #fef2f2; -fx-text-fill: #ef4444; -fx-border-color: #fecaca; -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 4 12; -fx-font-size: 12px; -fx-font-weight: bold; -fx-cursor: hand;");
        actionBtn.setOnAction(e -> memberListContainer.getChildren().remove(row));

        row.getChildren().addAll(avatar, nameLabel, spacer, actionBtn);
        return row;
    }

    private ImageView createAvatar(double size) {
        ImageView imageView = new ImageView();
        imageView.setFitHeight(size);
        imageView.setFitWidth(size);
        imageView.setPickOnBounds(true);
        imageView.setPreserveRatio(true);
        try {
            var stream = getClass().getResourceAsStream("/com/vgu/client/ui/assets/default/anon_user.png");
            if (stream != null) imageView.setImage(new Image(stream));
        } catch (Exception ignored) {}
        imageView.setClip(new Circle(size / 2.0, size / 2.0, size / 2.0));
        return imageView;
    }

    @Override
    protected void handleClose() {
        if (countdownTimeline != null) {
            countdownTimeline.stop();
        }
        super.handleClose();
    }

    @FXML
    private void handleCreateGroup() {
        String groupName = groupNameField != null ? groupNameField.getText().trim() : "";
        String inviteCode = inviteCodeInputField != null ? inviteCodeInputField.getText().trim() : "";

        if (groupName.isEmpty() && inviteCode.isEmpty()) {
            showError("Input Required!", "Please enter a group name to create or an invite code to join.");
            return;
        }

        if (!inviteCode.isEmpty()) {
            System.out.println("Joining group with invite code: " + inviteCode);
        } else {
            System.out.println("Created group: " + groupName);
        }

        handleClose();
    }

    @FXML
    private void handleSaveChanges() {
        if (targetGroup != null && groupNameField != null) {
            String newName = groupNameField.getText().trim();
            if (!newName.isEmpty()) {
                System.out.println("Updated group name to: " + newName);
            }
        }
        handleClose();
    }
}