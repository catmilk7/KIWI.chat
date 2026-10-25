package com.vgu.client.controllers.central;

import com.vgu.client.database_dummy.DummyDatabase;
import com.vgu.client.models.identity.User_dummy;
import com.vgu.client.models.messaging.Conversation_dummy;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.util.List;

public class Settings_group extends Handling {

    @FXML private TextField groupNameField;
    @FXML private Label groupIdLabel;
    @FXML private VBox friendsListContainer;
    @FXML private VBox memberListContainer;
    @FXML private VBox addFriendsContainer;

    private Conversation_dummy targetGroup;

    public void setTargetGroup(Conversation_dummy group) {
        this.targetGroup = group;
        populateGroupInfo();
    }

    @Override
    protected void onModalInitialized() {
        populateGroupInfo();

        List<User_dummy> allUsers = DummyDatabase.getAllUsers();
        User_dummy currentUser = DummyDatabase.getCurrentUser();

        if (friendsListContainer != null) {
            friendsListContainer.getChildren().clear();
            for (User_dummy user : allUsers) {
                if (currentUser != null && user.getId().equals(currentUser.getId())) continue;
                friendsListContainer.getChildren().add(buildCreateGroupFriendRow(user));
            }
        }

        if (memberListContainer != null && addFriendsContainer != null) {
            memberListContainer.getChildren().clear();
            addFriendsContainer.getChildren().clear();

            for (int i = 0; i < allUsers.size(); i++) {
                User_dummy user = allUsers.get(i);
                if (currentUser != null && user.getId().equals(currentUser.getId())) continue;

                // This is just a simulation, will implement later on!
                if (i % 2 == 0) {
                    memberListContainer.getChildren().add(buildMemberRow(user, true));
                } else {
                    addFriendsContainer.getChildren().add(buildMemberRow(user, false));
                }
            }
        }
    }

    private void populateGroupInfo() {
        if (targetGroup != null) {
            if (groupNameField != null) groupNameField.setText(targetGroup.getTitle());
            if (groupIdLabel != null) groupIdLabel.setText(targetGroup.getId());
        }
    }

    private HBox buildCreateGroupFriendRow(User_dummy user) {
        HBox row = new HBox(12.0);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPrefHeight(52.0);
        row.setStyle("-fx-padding: 6 12; -fx-background-color: -color-bg-default; -fx-background-radius: 8; -fx-border-color: #f1f5f9; -fx-border-radius: 8; -fx-cursor: hand;");

        ImageView avatar = createAvatar(38.0);
        Label nameLabel = new Label(user.getDisplayName());
        nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        CheckBox checkBox = new CheckBox();
        row.setOnMouseClicked(e -> {
            if (e.getTarget() != checkBox) {
                checkBox.setSelected(!checkBox.isSelected());
            }
        });

        row.getChildren().addAll(avatar, nameLabel, spacer, checkBox);
        return row;
    }

    private HBox buildMemberRow(User_dummy user, boolean isCurrentMember) {
        HBox row = new HBox(12.0);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPrefHeight(48.0);
        row.setStyle("-fx-padding: 6 12; -fx-background-color: -color-bg-default; -fx-background-radius: 8; -fx-border-color: #f1f5f9; -fx-border-radius: 8;");

        ImageView avatar = createAvatar(36.0);
        Label nameLabel = new Label(user.getUsername());
        nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button actionBtn = new Button(isCurrentMember ? "Kick" : "Add");
        actionBtn.setMinWidth(Region.USE_PREF_SIZE); // Chống bị biến thành dấu "..."
        if (isCurrentMember) {
            actionBtn.setStyle("-fx-background-color: #fef2f2; -fx-text-fill: #ef4444; -fx-border-color: #fecaca; -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 4 12; -fx-font-size: 12px; -fx-font-weight: bold; -fx-cursor: hand;");
            actionBtn.setOnAction(e -> {
                memberListContainer.getChildren().remove(row);
                addFriendsContainer.getChildren().add(buildMemberRow(user, false));
            });
        } else {
            actionBtn.setStyle("-fx-background-color: #f0fdf4; -fx-text-fill: #16a34a; -fx-border-color: #bbf7d0; -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 4 14; -fx-font-size: 12px; -fx-font-weight: bold; -fx-cursor: hand;");
            actionBtn.setOnAction(e -> {
                addFriendsContainer.getChildren().remove(row);
                memberListContainer.getChildren().add(buildMemberRow(user, true));
            });
        }

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

    @FXML
    private void handleCreateGroup() {
        String groupName = groupNameField != null ? groupNameField.getText().trim() : "";
        if (groupName.isEmpty()) {
            showError("Group name cannot be empty!", "Please enter a valid name for your group chat.");
            return;
        }

        System.out.println("Created group: " + groupName);
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