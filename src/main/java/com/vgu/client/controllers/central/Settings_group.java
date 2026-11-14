package com.vgu.client.controllers.central;

import com.vgu.client.controllers.auth.Session;
import com.vgu.client.models.storage.ContactDao;
import com.vgu.client.models.messaging.ConversationItem;
import com.vgu.client.models.messaging.GroupService;
import com.vgu.client.models.network.ApiException;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.Node;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.time.Instant;
import java.util.concurrent.Callable;

public class Settings_group extends Handling {

    @FXML private TextField groupNameField;
    @FXML private Label groupIdLabel;
    @FXML private VBox memberListContainer;

    @FXML private Label inviteCodeLabel;
    @FXML private Label countdownLabel;
    @FXML private Button regenCodeBtn;

    @FXML private TextField inviteCodeInputField;

    // Create / join dialog: exactly one of the two sections is shown, and Submit does only that one thing.
    @FXML private Node createSection;
    @FXML private Node joinSection;
    @FXML private Button createModeBtn;
    @FXML private Button joinModeBtn;
    @FXML private Button createBtn;
    private boolean joinMode;

    private ConversationItem targetGroup;
    private Timeline countdownTimeline;
    private Instant inviteExpiresAt;

    /**
     * Called by Chat after the FXML has loaded. {@code initialize()} runs earlier, while there is
     * no group yet, so everything that depends on the group is loaded here.
     */
    public void setTargetGroup(ConversationItem group) {
        this.targetGroup = group;
        refresh();
    }

    @Override
    protected void onModalInitialized() {
        refresh(); // no-op for the create/join dialog, which has no target group
    }

    private void refresh() {
        applyMode();
        populateGroupInfo();

        if (memberListContainer != null && targetGroup != null) {
            memberListContainer.getChildren().clear();
            Session session = Session.current();
            for (ContactDao.Member member : session.contacts().listMembers(targetGroup.id(), session.userId())) {
                memberListContainer.getChildren().add(buildMemberRow(member));
            }
        }

        showInviteState();
    }

    private boolean isOwner() {
        return targetGroup != null
                && "OWNER".equals(Session.current().conversations().myRole(targetGroup.id()));
    }

    private void populateGroupInfo() {
        if (targetGroup != null) {
            if (groupNameField != null) groupNameField.setText(targetGroup.title());
            if (groupIdLabel != null) groupIdLabel.setText(String.valueOf(targetGroup.id()));
        }
    }

    private void showInviteState() {
        if (inviteCodeLabel == null || targetGroup == null) return;
        Session session = Session.current();
        boolean owner = "OWNER".equals(session.conversations().myRole(targetGroup.id()));
        if (regenCodeBtn != null) regenCodeBtn.setDisable(!owner);
        if (!owner) {
            inviteCodeLabel.setText("-");
            setCountdownText("Only the group owner can create invite codes.");
            return;
        }
        var saved = session.conversations().activeInvite(targetGroup.id(), session.serverNow()); // null if none or expired
        if (saved == null) {
            showNoCode("No active code. Press Regenerate to create one.");
        } else {
            inviteCodeLabel.setText(GroupService.formatCode(saved.code()));
            startCountdown(saved.expiresAt());
        }
    }

    @FXML
    private void handleRegenerateCode() {
        if (targetGroup == null) return;
        long groupId = targetGroup.id();
        runOnServer(() -> Session.current().groups().mintInvite(groupId), invite -> {
            inviteCodeLabel.setText(GroupService.formatCode(invite.code()));
            startCountdown(Instant.parse(invite.expiresAt()));
        });
    }

    private void showNoCode(String hint) {
        stopCountdown();
        inviteCodeLabel.setText("-");
        setCountdownText(hint);
    }

    private void stopCountdown() {
        if (countdownTimeline != null) {
            countdownTimeline.stop();
            countdownTimeline = null;
        }
    }

    /** Counts down to the real expiry time, so reopening this window never resets it. */
    private void startCountdown(Instant expiresAt) {
        stopCountdown();
        inviteExpiresAt = expiresAt;
        tickCountdown();
        if (inviteExpiresAt == null) return; // already expired
        countdownTimeline = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), e -> tickCountdown()));
        countdownTimeline.setCycleCount(Timeline.INDEFINITE);
        countdownTimeline.play();
    }

    private void tickCountdown() {
        long remaining = (java.time.Duration.between(Session.current().serverNow(), inviteExpiresAt).toMillis() + 999) / 1000;
        if (remaining <= 0) {
            // Expired: remove the code from the stored conversation row too.
            Session.current().conversations().clearInvite(targetGroup.id());
            inviteExpiresAt = null;
            showNoCode("Code expired. Press Regenerate to create a new one.");
            return;
        }
        setCountdownText(String.format("Expires in %02d:%02d:%02d", remaining / 3600, (remaining % 3600) / 60, remaining % 60));
    }

    private void setCountdownText(String text) {
        if (countdownLabel != null) countdownLabel.setText(text);
    }

    private HBox buildMemberRow(ContactDao.Member user) {
        HBox row = new HBox(12.0);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPrefHeight(48.0);
        row.setStyle("-fx-padding: 6 12; -fx-background-color: -color-bg-default; -fx-background-radius: 8; -fx-border-color: #f1f5f9; -fx-border-radius: 8;");

        ImageView avatar = createAvatar(36.0);
        Label nameLabel = new Label(user.handle());
        nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button actionBtn = new Button("Kick");
        actionBtn.setMinWidth(Region.USE_PREF_SIZE);
        actionBtn.setStyle("-fx-background-color: #fef2f2; -fx-text-fill: #ef4444; -fx-border-color: #fecaca; -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 4 12; -fx-font-size: 12px; -fx-font-weight: bold; -fx-cursor: hand;");
        actionBtn.setOnAction(e -> showConfirm("Remove " + user.handle() + "?",
                "They will be removed from the group and its current invite code stops working.",
                () -> runOnServer(() -> {
                    Session.current().groups().kick(targetGroup.id(), user.userId());
                    return user.userId();
                }, done -> memberListContainer.getChildren().remove(row))));

        row.getChildren().addAll(avatar, nameLabel, spacer);
        if (isOwner()) { // only the owner can remove people
            row.getChildren().add(actionBtn);
        }
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
    private void showCreateMode() {
        joinMode = false;
        applyMode();
    }

    @FXML
    private void showJoinMode() {
        joinMode = true;
        applyMode();
    }

    private void applyMode() {
        if (createSection == null || joinSection == null) return; // the settings dialog has no modes
        setShown(createSection, !joinMode);
        setShown(joinSection, joinMode);
        String active = "-fx-background-color: -color-accent-emphasis; -fx-text-fill: white; -fx-background-radius: 6; -fx-cursor: hand; -fx-font-weight: bold; -fx-padding: 6 16;";
        String idle = "-fx-background-color: transparent; -fx-border-color: lightgray; -fx-border-radius: 6; -fx-background-radius: 6; -fx-cursor: hand; -fx-padding: 6 16; -fx-text-fill: #475569;";
        if (createModeBtn != null) createModeBtn.setStyle(joinMode ? idle : active);
        if (joinModeBtn != null) joinModeBtn.setStyle(joinMode ? active : idle);
        if (createBtn != null) createBtn.setText(joinMode ? "Join" : "Create");
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    /** Submit: creates a group (name only, no code involved) or joins one (code only), never both. */
    @FXML
    private void handleCreateGroup() {
        if (joinMode) {
            String code = GroupService.normalizeCode(inviteCodeInputField != null ? inviteCodeInputField.getText() : "");
            if (!GroupService.hasValidShape(code)) {
                showError("Invalid invite code", GroupService.INVALID_CODE_MESSAGE);
                return;
            }
            runOnServer(() -> Session.current().groups().join(code), id -> handleClose());
        } else {
            String name = groupNameField != null ? groupNameField.getText().trim() : "";
            if (name.isEmpty()) {
                showError("Group name required", "Please enter a name for the new group.");
                return;
            }
            runOnServer(() -> Session.current().groups().createGroup(name), id -> handleClose());
        }
    }

    @FXML
    private void handleCopyCode() {
        if (targetGroup == null) return;
        var invite = Session.current().conversations().activeInvite(targetGroup.id(), Session.current().serverNow());
        if (invite == null) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(GroupService.formatCode(invite.code()));
        Clipboard.getSystemClipboard().setContent(content);
        setCountdownText("Code copied to the clipboard.");
    }

    /** Runs a server call off the JavaFX thread; shows the server's reason in a dialog if it fails. */
    private <T> void runOnServer(Callable<T> call, java.util.function.Consumer<T> onSuccess) {
        if (!Session.current().online()) {
            showError("You are offline", "Log in again once the server is reachable.");
            return;
        }
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return call.call();
            }
        };
        task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));
        task.setOnFailed(e -> {
            Throwable error = task.getException();
            if (!(error instanceof ApiException)) {
                error.printStackTrace();
            }
            showError("Request failed", error instanceof ApiException ? error.getMessage()
                    : "Unexpected error: " + error);
        });
        Thread thread = new Thread(task, "server-call");
        thread.setDaemon(true);
        thread.start();
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