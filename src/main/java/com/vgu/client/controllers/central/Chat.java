package com.vgu.client.controllers.central;

import com.vgu.client.database_dummy.DummyDatabase;
import com.vgu.client.models.messaging.Conversation_dummy;
import com.vgu.client.models.messaging.Message_dummy;
import com.vgu.client.models.identity.User_dummy;
import com.vgu.client.controllers.central.Settings_group;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.AnchorPane;
import javafx.animation.FadeTransition;
import javafx.util.Duration;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import org.kordamp.ikonli.javafx.FontIcon;
import java.io.IOException;

import java.time.format.DateTimeFormatter;
import java.util.List;

public class Chat {

    // Left panel
    @FXML private TextField searchField;
    @FXML private VBox contactListContainer;
    @FXML private Label currentUserNameLabel;

    // Right panel header
    @FXML private Label activeChatUser;
    @FXML private Circle activeChatStatus;
    @FXML private Label activeChatStatusText;

    // Messages and input
    @FXML private ScrollPane messagesScrollPane;
    @FXML private VBox messagesContainer;
    @FXML private TextArea messageInput;
    @FXML private Button attachmentsBtn;

    // Floating menus
    @FXML private VBox usersAccounts;
    @FXML private VBox attachmentsMenu;
    @FXML private VBox usersMenu;
    @FXML private VBox messagesMenu;
    @FXML private VBox groupsMenu;
    @FXML private VBox usersProfile;

    // Floating profile fields
    @FXML private Label profileDisplayName;
    @FXML private Label profileUsername;
    @FXML private Label profileBio;
    @FXML private Circle profileStatusDot;

    // Group notification
    @FXML private Button toggleBell;
    @FXML private FontIcon bell;
    private boolean isToggled = false;

    @FXML
    private AnchorPane rootPane;
    @FXML
    private SplitPane mainSplitPane;
    private Parent currentOverlay;

    private Conversation_dummy activeConversation;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("hh:mm a");

    @FXML
    public void initialize() {
        hideAllFloatingMenus();

        User_dummy currentUser = DummyDatabase.getCurrentUser();
        if (currentUserNameLabel != null && currentUser != null) {
            currentUserNameLabel.setText(currentUser.getDisplayName());
        }

        List<Conversation_dummy> allConvs = DummyDatabase.getConversations("");
        renderContactList(allConvs);

        if (!allConvs.isEmpty()) {
            selectConversation(allConvs.get(0));
        }

        if (searchField != null) {
            searchField.textProperty().addListener((obs, oldVal, newVal) -> {
                renderContactList(DummyDatabase.getConversations(newVal));
            });
        }

        if (messageInput != null) {
            messageInput.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) {
                    event.consume();
                    handleSendMessage();
                }
            });
        }

        if (messagesContainer != null) {
            messagesContainer.setOnMouseClicked(e -> hideAllFloatingMenus());
        }
    }

    private void renderContactList(List<Conversation_dummy> conversations) {
        if (contactListContainer == null) return;

        Node createGroupBtn = contactListContainer.getChildren().isEmpty() ? null : contactListContainer.getChildren().get(0);
        contactListContainer.getChildren().clear();
        if (createGroupBtn != null) {
            contactListContainer.getChildren().add(createGroupBtn);
        }

        for (Conversation_dummy conv : conversations) {
            HBox row = buildContactRow(conv);
            contactListContainer.getChildren().add(row);
        }
    }

    private HBox buildContactRow(Conversation_dummy conv) {
        HBox row = new HBox(12.0);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPrefHeight(60.0);
        boolean isSelected = activeConversation != null && activeConversation.getId().equals(conv.getId());
        row.setStyle("-fx-cursor: hand; -fx-padding: 5; -fx-background-radius: 8; -fx-background-color: "
                + (isSelected ? "-color-bg-subtle;" : "transparent;"));

        ImageView avatar = createCircularAvatar("/com/vgu/client/ui/assets/default/anon_sender.png", 45.0);

        VBox textCol = new VBox(2.0);
        textCol.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(textCol, Priority.ALWAYS);

        Label titleLabel = new Label(conv.getTitle());
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Label previewLabel = new Label(conv.getLastMessagePreview());
        previewLabel.setTextFill(Color.GRAY);
        previewLabel.setStyle("-fx-font-size: 12px;");
        textCol.getChildren().addAll(titleLabel, previewLabel);

        VBox metaCol = new VBox(5.0);
        metaCol.setAlignment(Pos.TOP_RIGHT);
        Label timeLabel = new Label("Now");
        timeLabel.setTextFill(Color.GRAY);
        timeLabel.setStyle("-fx-font-size: 10px;");
        metaCol.getChildren().add(timeLabel);

        if (conv.getUnreadCount() > 0) {
            Label badge = new Label(String.valueOf(conv.getUnreadCount()));
            badge.setStyle("-fx-background-color: #ef4444; -fx-text-fill: white; -fx-font-size: 10px; -fx-font-weight: bold; -fx-padding: 1 6; -fx-background-radius: 10;");
            metaCol.getChildren().add(badge);
        }

        row.getChildren().addAll(avatar, textCol, metaCol);
        row.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                selectConversation(conv);
            }
        });

        if (conv.isGroup()) {
            row.setOnContextMenuRequested(e -> {
                showMenuAtCursor(groupsMenu, e.getSceneX(), e.getSceneY());
                e.consume();
            });
        }
        return row;
    }

    private void selectConversation(Conversation_dummy conv) {
        this.activeConversation = conv;
        conv.setUnreadCount(0);
        hideAllFloatingMenus();

        if (activeChatUser != null) {
            activeChatUser.setText(conv.getTitle());
        }

        if (activeChatStatusText != null && activeChatStatus != null) {
            if (conv.isGroup()) {
                activeChatStatusText.setText("Group Chat");
                activeChatStatus.setFill(Color.web("#3b82f6"));
            } else {
                boolean isOnline = conv.getTargetUser() != null && conv.getTargetUser().isOnline();
                activeChatStatusText.setText(isOnline ? "Online" : "Offline");
                activeChatStatus.setFill(isOnline ? Color.web("#2ecc71") : Color.GRAY);
            }
        }

        renderContactList(DummyDatabase.getConversations(searchField != null ? searchField.getText() : ""));
        renderMessages(conv);
    }

    private void renderMessages(Conversation_dummy conv) {
        if (messagesContainer == null) return;
        messagesContainer.getChildren().clear();
        messagesContainer.getChildren().add(buildDateSeparator("Today"));

        User_dummy currentUser = DummyDatabase.getCurrentUser();
        for (Message_dummy msg : conv.getMessages()) {
            boolean isMe = currentUser != null && msg.getSender().getId().equals(currentUser.getId());
            messagesContainer.getChildren().add(buildMessageBubble(msg, isMe));
        }

        Platform.runLater(() -> {
            if (messagesScrollPane != null) messagesScrollPane.setVvalue(1.0);
        });
    }

    private HBox buildMessageBubble(Message_dummy msg, boolean isMe) {
        HBox row = new HBox(10.0);
        row.setAlignment(isMe ? Pos.TOP_RIGHT : Pos.TOP_LEFT);

        String avatarPath = isMe
                ? "/com/vgu/client/ui/assets/default/anon_user.png"
                : "/com/vgu/client/ui/assets/default/anon_sender.png";
        ImageView avatar = createCircularAvatar(avatarPath, 40.0);
        avatar.setStyle("-fx-cursor: hand;");
        avatar.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                showUserProfile(msg.getSender(), e.getSceneX(), e.getSceneY());
                e.consume();
            }
        });

        VBox contentBox = new VBox(5.0);
        contentBox.setAlignment(isMe ? Pos.TOP_RIGHT : Pos.TOP_LEFT);
        contentBox.setMaxWidth(600.0);

        HBox metaRow = new HBox(8.0);
        metaRow.setAlignment(isMe ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        Label nameLbl = new Label(msg.getSender().getDisplayName());
        nameLbl.setTextFill(Color.GRAY);
        nameLbl.setStyle("-fx-font-size: 11px;");

        Label timeLbl = new Label(msg.getTimestamp().format(timeFormatter));
        timeLbl.setTextFill(Color.web("#a1a1aa"));
        timeLbl.setStyle("-fx-font-size: 10px;");

        if (isMe) metaRow.getChildren().addAll(timeLbl, nameLbl);
        else metaRow.getChildren().addAll(nameLbl, timeLbl);

        Label bubble = new Label(msg.getContent());
        bubble.setWrapText(true);
        if (isMe) {
            bubble.setStyle("-fx-background-color: -color-accent-emphasis; -fx-text-fill: white; -fx-padding: 10 15; -fx-background-radius: 15 0 15 15; -fx-font-size: 14px;");
        } else {
            bubble.setStyle("-fx-background-color: -color-bg-subtle; -fx-padding: 10 15; -fx-background-radius: 0 15 15 15; -fx-font-size: 14px;");
        }

        bubble.setOnContextMenuRequested(e -> {
            showMenuAtCursor(messagesMenu, e.getSceneX(), e.getSceneY());
            e.consume();
        });

        contentBox.getChildren().addAll(metaRow, bubble);
        if (isMe) row.getChildren().addAll(contentBox, avatar);
        else row.getChildren().addAll(avatar, contentBox);
        return row;
    }

    private HBox buildDateSeparator(String dateText) {
        HBox row = new HBox(10.0);
        row.setAlignment(Pos.CENTER);
        row.setPadding(new Insets(5.0, 0, 5.0, 0));

        Separator leftSep = new Separator();
        HBox.setHgrow(leftSep, Priority.ALWAYS);

        Label dateLbl = new Label(dateText);
        dateLbl.setTextFill(Color.web("#71717a"));
        dateLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");

        Separator rightSep = new Separator();
        HBox.setHgrow(rightSep, Priority.ALWAYS);

        row.getChildren().addAll(leftSep, dateLbl, rightSep);
        return row;
    }

    private void showUserProfile(User_dummy user, double sceneX, double sceneY) {
        if (user == null || usersProfile == null) return;
        if (profileDisplayName != null) profileDisplayName.setText(user.getDisplayName());
        if (profileUsername != null) profileUsername.setText(user.getUsername());
        if (profileBio != null) profileBio.setText(user.getBio());
        if (profileStatusDot != null) {
            profileStatusDot.setFill(user.isOnline() ? Color.web("#2ecc71") : Color.GRAY);
        }
        showMenuAtCursor(usersProfile, sceneX, sceneY);
    }

    private void renderAccountsMenu() {
        if (usersAccounts == null) return;
        usersAccounts.getChildren().clear();

        List<User_dummy> accounts = DummyDatabase.getAllUsers();
        User_dummy currentUser = DummyDatabase.getCurrentUser();

        for (User_dummy user : accounts) {
            boolean isCurrent = currentUser != null && currentUser.getId().equals(user.getId());
            Button accBtn = buildAccountButton(user, isCurrent);
            usersAccounts.getChildren().add(accBtn);
        }
    }

    private Button buildAccountButton(User_dummy user, boolean isCurrent) {
        Button btn = new Button(user.getDisplayName());
        btn.setGraphicTextGap(12.0);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setAlignment(Pos.BASELINE_LEFT);

        FontIcon icon = new FontIcon(isCurrent ? "fas-user-check" : "fas-user");
        icon.setIconColor(isCurrent ? Color.web("#2ecc71") : Color.web("#808080"));
        btn.setGraphic(icon);

        String baseStyle = "-fx-padding: 10 15; -fx-cursor: hand; -fx-font-size: 13px; -fx-background-radius: 6; ";
        if (isCurrent) {
            btn.setStyle(baseStyle + "-fx-background-color: -color-bg-subtle; -fx-font-weight: bold;");
        } else {
            btn.setStyle(baseStyle + "-fx-background-color: transparent;");
        }

        btn.setOnAction(e -> switchUserAccount(user));
        return btn;
    }

    private void switchUserAccount(User_dummy selectedUser) {
        DummyDatabase.setCurrentUser(selectedUser);

        if (currentUserNameLabel != null) {
            currentUserNameLabel.setText(selectedUser.getDisplayName());
        }

        hideAllFloatingMenus();
        renderContactList(DummyDatabase.getConversations(searchField != null ? searchField.getText() : ""));
        if (activeConversation != null) {
            renderMessages(activeConversation);
        }
    }

    private void showMenuAtCursor(Region targetMenu, double sceneX, double sceneY) {
        if (targetMenu == null || targetMenu.getParent() == null) return;

        hideAllFloatingMenus();

        Parent parent = targetMenu.getParent();
        Point2D localPoint = parent.sceneToLocal(sceneX, sceneY);

        double x = localPoint.getX();
        double y = localPoint.getY();

        double menuWidth = targetMenu.getPrefWidth() > 0 ? targetMenu.getPrefWidth() : 200.0;
        double menuHeight = targetMenu.prefHeight(-1) > 0 ? targetMenu.prefHeight(-1) : 180.0;

        double parentWidth = parent.getLayoutBounds().getWidth();
        double parentHeight = parent.getLayoutBounds().getHeight();

        if (x + menuWidth > parentWidth) {
            x = Math.max(10.0, parentWidth - menuWidth - 10.0);
        }
        if (y + menuHeight > parentHeight) {
            y = Math.max(10.0, parentHeight - menuHeight - 10.0);
        }

        AnchorPane.clearConstraints(targetMenu);
        targetMenu.setLayoutX(x);
        targetMenu.setLayoutY(y);

        setMenuState(targetMenu, true);
    }

    @FXML
    private void handleSendMessage() {
        if (activeConversation == null || messageInput == null) return;
        String text = messageInput.getText().trim();
        if (text.isEmpty()) return;

        DummyDatabase.addMessage(activeConversation.getId(), text);
        messageInput.clear();

        renderMessages(activeConversation);
        renderContactList(DummyDatabase.getConversations(searchField != null ? searchField.getText() : ""));
    }

    private void hideAllFloatingMenus() {
        setMenuState(usersAccounts, false);
        setMenuState(attachmentsMenu, false);
        setMenuState(usersMenu, false);
        setMenuState(messagesMenu, false);
        setMenuState(groupsMenu, false);
        setMenuState(usersProfile, false);
    }

    private void setMenuState(Node menu, boolean visible) {
        if (menu != null) {
            menu.setVisible(visible);
            menu.setManaged(visible);
        }
    }

    private void toggleMenuVisibility(Node targetMenu) {
        if (targetMenu == null) return;
        boolean currentlyVisible = targetMenu.isVisible();
        hideAllFloatingMenus();
        setMenuState(targetMenu, !currentlyVisible);
    }

    @FXML
    private void toggleAttachmentsMenu() {
        toggleMenuVisibility(attachmentsMenu);
    }

    @FXML
    private void toggleAccountsMenu() {
        renderAccountsMenu();
        toggleMenuVisibility(usersAccounts);
    }

    @FXML
    private void toggleHeaderMenu() {
        if (activeConversation != null && activeConversation.isGroup()) {
            // Đặt vị trí cố định ở góc phải dưới Header nếu mở từ nút 3 chấm
            AnchorPane.clearConstraints(groupsMenu);
            AnchorPane.setTopAnchor(groupsMenu, 70.0);
            AnchorPane.setRightAnchor(groupsMenu, 20.0);
            toggleMenuVisibility(groupsMenu);
        } else {
            toggleMenuVisibility(usersMenu);
        }
    }

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

    private ImageView createCircularAvatar(String resourcePath, double size) {
        ImageView imageView = new ImageView();
        imageView.setFitHeight(size);
        imageView.setFitWidth(size);
        imageView.setPickOnBounds(true);
        imageView.setPreserveRatio(true);
        try {
            var stream = getClass().getResourceAsStream(resourcePath);
            if (stream != null) imageView.setImage(new Image(stream));
        } catch (Exception ignored) {}
        Circle clip = new Circle(size / 2.0, size / 2.0, size / 2.0);
        imageView.setClip(clip);
        return imageView;
    }

    @FXML
    private void openCreateGroupModal() {
        hideAllFloatingMenus();
        showModal("group_create.view.fxml");
    }

    @FXML
    private void openUserSettingsModal() {
        hideAllFloatingMenus();
        showModal("settings_user.view.fxml");
    }

    @FXML
    private void openGroupSettingsModal() {
        hideAllFloatingMenus();
        showModal("settings_group.view.fxml");
    }

    @FXML
    private void openWarningModal() {
        hideAllFloatingMenus();
        showModal("warning.view.fxml");
    }

    public void showModal(String fxmlFileName) {
        try {
            String path = "/com/vgu/client/ui/views/settings/" + fxmlFileName;
            var resourceUrl = getClass().getResource(path);
            if (resourceUrl == null) {
                System.err.println("Không tìm thấy file FXML: " + path);
                return;
            }

            FXMLLoader loader = new FXMLLoader(resourceUrl);
            currentOverlay = loader.load();

            Object controller = loader.getController();
            if (controller instanceof Handling baseController) {
                baseController.setOnCloseCallback(this::closeOverlay);
            }

            AnchorPane.setTopAnchor(currentOverlay, 0.0);
            AnchorPane.setBottomAnchor(currentOverlay, 0.0);
            AnchorPane.setLeftAnchor(currentOverlay, 0.0);
            AnchorPane.setRightAnchor(currentOverlay, 0.0);

            mainSplitPane.setEffect(new GaussianBlur(12.0));

            if (!rootPane.getChildren().contains(currentOverlay)) {
                currentOverlay.setOpacity(0);
                rootPane.getChildren().add(currentOverlay);

                FadeTransition fadeIn = new FadeTransition(Duration.millis(180), currentOverlay);
                fadeIn.setFromValue(0.0);
                fadeIn.setToValue(1.0);
                fadeIn.play();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void closeOverlay() {
        if (currentOverlay != null && rootPane.getChildren().contains(currentOverlay)) {
            FadeTransition fadeOut = new FadeTransition(Duration.millis(150), currentOverlay);
            fadeOut.setFromValue(1.0);
            fadeOut.setToValue(0.0);
            fadeOut.setOnFinished(e -> {
                rootPane.getChildren().remove(currentOverlay);
                mainSplitPane.setEffect(null);
            });
            fadeOut.play();
        }
    }

    public void showErrorModal(String errorMessage, String subtext) {
        hideAllFloatingMenus();
        try {
            String path = "/com/vgu/client/ui/views/settings/warning.view.fxml";
            FXMLLoader loader = new FXMLLoader(getClass().getResource(path));
            currentOverlay = loader.load();

            if (loader.getController() instanceof Warning warningController) {
                warningController.setupAsError(errorMessage, subtext);
                warningController.setOnCloseCallback(this::closeOverlay);
            }

            AnchorPane.setTopAnchor(currentOverlay, 0.0);
            AnchorPane.setBottomAnchor(currentOverlay, 0.0);
            AnchorPane.setLeftAnchor(currentOverlay, 0.0);
            AnchorPane.setRightAnchor(currentOverlay, 0.0);

            mainSplitPane.setEffect(new GaussianBlur(12.0));
            rootPane.getChildren().add(currentOverlay);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}