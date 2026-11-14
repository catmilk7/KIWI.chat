package com.vgu.client.controllers.central;

import com.vgu.client.controllers.auth.AuthException;
import com.vgu.client.controllers.auth.AuthService;
import com.vgu.client.controllers.auth.Session;
import com.vgu.client.controllers.utils.SceneNavigator;
import com.vgu.client.enums.ConversationType;
import com.vgu.client.models.messaging.ConversationItem;
import com.vgu.client.models.messaging.MessageContent;
import com.vgu.client.models.messaging.TextContent;
import com.vgu.client.models.messaging.Message;

import com.vgu.client.enums.MessageType;
import com.vgu.client.models.messaging.FileAttachment;
import com.vgu.client.models.network.ApiException;
import com.vgu.client.models.network.FileService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
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
import javafx.scene.CacheHint;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import org.kordamp.ikonli.javafx.FontIcon;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class Chat {

    // Left panel
    @FXML private TextField searchField;
    @FXML private VBox contactListContainer;
    @FXML private Label currentUserNameLabel;

    // Right panel header
    @FXML private HBox chatHeader;
    @FXML private HBox inputBar;
    @FXML private ImageView activeChatAvatar;
    @FXML private Button groupSettingsBtn;
    @FXML private Button leaveMenuBtn;
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
    @FXML private Label profileHandle;

    // Group notification
    @FXML private Button toggleBell;
    @FXML private FontIcon bell;
    private boolean isToggled = false;

    @FXML
    private AnchorPane rootPane;
    @FXML
    private SplitPane mainSplitPane;
    private Parent currentOverlay;

    private final Session session = Session.current();
    private ConversationItem activeConversation;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("hh:mm a");

    @FXML
    public void initialize() {
        hideAllFloatingMenus();

        if (currentUserNameLabel != null) {
            currentUserNameLabel.setText(session.handle());
        }
        session.onChange(() -> Platform.runLater(this::refreshFromDatabase));

        List<ConversationItem> allConvs = session.conversations().list("");
        renderContactList(allConvs);

        if (!allConvs.isEmpty()) {
            selectConversation(allConvs.get(0));
        } else {
            showEmptyChatPanel();
        }

        if (searchField != null) {
            searchField.textProperty().addListener((obs, oldVal, newVal) -> {
                renderContactList(session.conversations().list(newVal));
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

    /** Something changed in the background (a message arrived, was sent, the connection changed). */
    private void refreshFromDatabase() {
        if (rootPane.getScene() == null) return; // this screen has already been replaced
        if (activeConversation != null) {
            session.conversations().markRead(activeConversation.id());
        }
        if (activeConversation != null && !session.conversations().exists(activeConversation.id())) {
            clearActiveConversation(); // we left it, were kicked, or it was deleted
        }
        renderContactList(session.conversations().list(searchField != null ? searchField.getText() : ""));
        if (activeConversation != null) {
            renderMessages(activeConversation);
        }
    }

    private void clearActiveConversation() {
        activeConversation = null;
        showEmptyChatPanel();
        closeOverlay(); // a settings window of the removed group may still be open
    }

    /** Nothing is open: hide the header (avatar, name, status, cog, menu) and the message box, say what to do. */
    private void showEmptyChatPanel() {
        setChatPanelShown(false);
        if (activeChatUser != null) activeChatUser.setText("");
        if (activeChatStatusText != null) activeChatStatusText.setText("");
        if (messagesContainer != null) {
            Label hint = new Label("Select a conversation, or create or join a group, to start chatting.");
            hint.setWrapText(true);
            hint.setStyle("-fx-text-fill: #64748b; -fx-font-size: 14px; -fx-background-color: -color-bg-default; -fx-background-radius: 8; -fx-padding: 12 16;");
            messagesContainer.getChildren().setAll(hint);
        }
    }

    private void setChatPanelShown(boolean shown) {
        for (Node part : new Node[]{chatHeader, inputBar}) {
            if (part != null) {
                part.setVisible(shown);
                part.setManaged(shown);
            }
        }
    }

    private void renderContactList(List<ConversationItem> conversations) {
        if (contactListContainer == null) return;

        Node createGroupBtn = contactListContainer.getChildren().isEmpty() ? null : contactListContainer.getChildren().get(0);
        contactListContainer.getChildren().clear();
        if (createGroupBtn != null) {
            contactListContainer.getChildren().add(createGroupBtn);
        }

        for (ConversationItem conv : conversations) {
            HBox row = buildContactRow(conv);
            contactListContainer.getChildren().add(row);
        }
    }

    private HBox buildContactRow(ConversationItem conv) {
        HBox row = new HBox(12.0);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPrefHeight(60.0);
        boolean isSelected = activeConversation != null && activeConversation.id() == conv.id();
        row.setStyle("-fx-cursor: hand; -fx-padding: 5; -fx-background-radius: 8; -fx-background-color: "
                + (isSelected ? "-color-bg-subtle;" : "transparent;"));

        ImageView avatar = createCircularAvatar(conv.avatarResource(), 45.0);
        avatar.setSmooth(true);

        VBox textCol = new VBox(2.0);
        textCol.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(textCol, Priority.ALWAYS);

        Label titleLabel = new Label(conv.title());
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Label previewLabel = new Label(conv.lastPreview());
        previewLabel.setTextFill(Color.GRAY);
        previewLabel.setStyle("-fx-font-size: 12px;");
        textCol.getChildren().addAll(titleLabel, previewLabel);

        VBox metaCol = new VBox(5.0);
        metaCol.setAlignment(Pos.TOP_RIGHT);
        Label timeLabel = new Label(formatListTime(conv));
        timeLabel.setTextFill(Color.GRAY);
        timeLabel.setStyle("-fx-font-size: 10px;");
        metaCol.getChildren().add(timeLabel);

        if (conv.unreadCount() > 0) {
            Label badge = new Label(String.valueOf(conv.unreadCount()));
            badge.setStyle("-fx-background-color: #ef4444; -fx-text-fill: white; -fx-font-size: 10px; -fx-font-weight: bold; -fx-padding: 1 6; -fx-background-radius: 10;");
            metaCol.getChildren().add(badge);
        }

        row.getChildren().addAll(avatar, textCol, metaCol);
        row.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                selectConversation(conv);
            }
        });

        if (conv.hasSettings()) {
            row.setOnContextMenuRequested(e -> {
                showMenuAtCursor(groupsMenu, e.getSceneX(), e.getSceneY());
                e.consume();
            });
        }
        return row;
    }

    private void selectConversation(ConversationItem conv) {
        session.conversations().markRead(conv.id());
        this.activeConversation = conv;
        hideAllFloatingMenus();
        setChatPanelShown(true);

        if (activeChatUser != null) {
            activeChatUser.setText(conv.title());
        }
        if (leaveMenuBtn != null && conv.leaveLabel(false) != null) {
            leaveMenuBtn.setText(conv.leaveLabel("OWNER".equals(session.conversations().myRole(conv.id()))));
        }
        if (groupSettingsBtn != null) {
            // Only conversations that have settings (members, invite code) show the cog.
            groupSettingsBtn.setVisible(conv.hasSettings());
            groupSettingsBtn.setManaged(conv.hasSettings());
        }
        if (activeChatAvatar != null) {
            var stream = getClass().getResourceAsStream(conv.avatarResource());
            if (stream != null) activeChatAvatar.setImage(new Image(stream));
        }

        if (activeChatStatusText != null && activeChatStatus != null) {
            activeChatStatus.setVisible(conv.isGroup());
            activeChatStatusText.setText(conv.statusText());
            activeChatStatus.setFill(Color.web("#3b82f6"));
        }

        renderContactList(session.conversations().list(searchField != null ? searchField.getText() : ""));
        renderMessages(conv);
    }

    private void renderMessages(ConversationItem conv) {
        if (messagesContainer == null) return;
        messagesContainer.getChildren().clear();

        LocalDate lastDay = null;
        for (Message msg : session.messages().list(conv.id())) {
            LocalDate day = msg.createdAt().atZone(ZoneId.systemDefault()).toLocalDate();
            if (!day.equals(lastDay)) {
                messagesContainer.getChildren().add(buildDateSeparator(formatDay(day)));
                lastDay = day;
            }
            messagesContainer.getChildren().add(buildMessageBubble(msg, msg.senderId() == session.userId()));
        }

        Platform.runLater(() -> {
            if (messagesScrollPane != null) messagesScrollPane.setVvalue(1.0);
        });
    }

    private static String formatDay(LocalDate day) {
        LocalDate today = LocalDate.now();
        if (day.equals(today)) return "Today";
        if (day.equals(today.minusDays(1))) return "Yesterday";
        return day.format(DateTimeFormatter.ofPattern("dd MMM yyyy"));
    }

    private String formatListTime(ConversationItem conv) {
        var time = conv.updatedAt().atZone(ZoneId.systemDefault());
        return time.toLocalDate().equals(LocalDate.now())
                ? time.format(timeFormatter)
                : time.format(DateTimeFormatter.ofPattern("dd MMM"));
    }

    private HBox buildMessageBubble(Message msg, boolean isMe) {
        HBox row = new HBox(10.0);
        row.setAlignment(isMe ? Pos.TOP_RIGHT : Pos.TOP_LEFT);

        String avatarPath = isMe
                ? "/com/vgu/client/ui/assets/default/anon_user.png"
                : "/com/vgu/client/ui/assets/default/anon_sender.png";

        ImageView avatar = createCircularAvatar(avatarPath, 40.0);
        avatar.setSmooth(true);
        avatar.setCache(true);
        avatar.setCacheHint(CacheHint.QUALITY);
        avatar.setStyle("-fx-cursor: hand;");
        avatar.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                showUserProfile(senderHandle(msg, isMe), e.getSceneX(), e.getSceneY());
                e.consume();
            }
        });

        VBox contentBox = new VBox(5.0);
        contentBox.setAlignment(isMe ? Pos.TOP_RIGHT : Pos.TOP_LEFT);
        contentBox.setMaxWidth(600.0);

        HBox metaRow = new HBox(8.0);
        metaRow.setAlignment(isMe ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        Label nameLbl = new Label(senderHandle(msg, isMe));
        nameLbl.setTextFill(Color.GRAY);
        nameLbl.setStyle("-fx-font-size: 11px;");

        Label timeLbl = new Label(msg.createdAt().atZone(ZoneId.systemDefault()).format(timeFormatter));
        timeLbl.setTextFill(Color.web("#a1a1aa"));
        timeLbl.setStyle("-fx-font-size: 10px;");

        if (isMe && msg.status() != com.vgu.client.enums.DeliveryStatus.SENT) {
            Label statusLbl = new Label(msg.status() == com.vgu.client.enums.DeliveryStatus.FAILED
                    ? "Not sent" : "Sending...");
            statusLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: "
                    + (msg.status() == com.vgu.client.enums.DeliveryStatus.FAILED ? "#ef4444" : "#9ca3af") + ";");
            metaRow.getChildren().add(statusLbl);
        }
        if (isMe) metaRow.getChildren().addAll(timeLbl, nameLbl);
        else metaRow.getChildren().addAll(nameLbl, timeLbl);

        MessageContent body = msg.body(); // text or attachment: the bubble does not care which
        Label bubble = new Label(body.bubbleText());
        bubble.setWrapText(true);
        body.attachment().ifPresent(file -> bubble.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) saveAttachment(file);
        }));
        String cursor = body.attachment().isPresent() ? "-fx-cursor: hand; " : "";
        if (isMe) {
            bubble.setStyle(cursor + "-fx-background-color: -color-accent-emphasis; -fx-text-fill: white; -fx-padding: 10 15; -fx-background-radius: 15 0 15 15; -fx-font-size: 14px;");
        } else {
            bubble.setStyle(cursor + "-fx-background-color: -color-bg-subtle; -fx-padding: 10 15; -fx-background-radius: 0 15 15 15; -fx-font-size: 14px;");
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

    private String senderHandle(Message msg, boolean isMe) {
        if (isMe) return session.handle();
        return "@" + (msg.senderUsername() != null ? msg.senderUsername() : "user#" + msg.senderId());
    }

    private void showUserProfile(String handle, double sceneX, double sceneY) {
        if (handle == null || usersProfile == null) return;
        if (profileHandle != null) profileHandle.setText(handle);
        showMenuAtCursor(usersProfile, sceneX, sceneY);
    }

    private void renderAccountsMenu() {
        if (usersAccounts == null) return;
        usersAccounts.getChildren().clear();

        usersAccounts.getChildren().add(accountButton(session.handle(), true, null));
        for (String username : new AuthService().localAccounts()) {
            if (!username.equals(session.username())) {
                usersAccounts.getChildren().add(accountButton("@" + username, false, e -> switchAccount(username, e)));
            }
        }

        Button logout = new Button("Log out");
        logout.setGraphicTextGap(12.0);
        logout.setMaxWidth(Double.MAX_VALUE);
        logout.setAlignment(Pos.BASELINE_LEFT);
        logout.setGraphic(new FontIcon("fas-sign-out-alt"));
        logout.setStyle("-fx-padding: 10 15; -fx-cursor: hand; -fx-font-size: 13px; -fx-background-radius: 6; -fx-background-color: transparent;");
        logout.setOnAction(e -> {
            Session.end();
            SceneNavigator.goTo(e, SceneNavigator.LOGIN);
        });
        usersAccounts.getChildren().add(logout);
    }

    private Button accountButton(String text, boolean current, javafx.event.EventHandler<javafx.event.ActionEvent> onClick) {
        Button button = new Button(text);
        button.setGraphicTextGap(12.0);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.BASELINE_LEFT);
        button.setGraphic(new FontIcon(current ? "fas-user-check" : "fas-user"));
        String base = "-fx-padding: 10 15; -fx-font-size: 13px; -fx-background-radius: 6; ";
        if (current) {
            button.setStyle(base + "-fx-background-color: -color-bg-subtle; -fx-font-weight: bold;");
            button.setDisable(true);
        } else {
            button.setStyle(base + "-fx-cursor: hand; -fx-background-color: transparent;");
            button.setOnAction(onClick);
        }
        return button;
    }

    /**
     * Asks for the other account's password (it unlocks that account's keys) in a roomy dialog with
     * the app icon. A wrong password is shown inside the dialog, which stays open; success switches.
     */
    private void switchAccount(String username, javafx.event.ActionEvent event) {
        hideAllFloatingMenus();
        Dialog<Session> dialog = new Dialog<>();
        dialog.initOwner(rootPane.getScene().getWindow());
        dialog.setTitle("Switch account");
        dialog.setResizable(false);

        ImageView logo = new ImageView(new Image(getClass().getResourceAsStream("/com/vgu/client/ui/assets/icon/icon-128.png")));
        logo.setFitWidth(64);
        logo.setFitHeight(64);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);

        Label title = new Label("Switch to @" + username);
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        Label subtitle = new Label("Enter this account's password to unlock it. You will be logged out of @"
                + session.username() + ".");
        subtitle.setWrapText(true);
        subtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");
        VBox heading = new VBox(4.0, title, subtitle);
        HBox.setHgrow(heading, Priority.ALWAYS);
        HBox header = new HBox(18.0, logo, heading);
        header.setAlignment(Pos.CENTER_LEFT);

        PasswordField password = new PasswordField();
        password.setPromptText("Password");
        password.setPrefHeight(40.0);
        password.setStyle("-fx-font-size: 14px;");
        Label error = new Label();
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);
        error.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 12px;");

        VBox content = new VBox(20.0, header, password, error);
        content.setPadding(new Insets(24.0, 28.0, 8.0, 28.0));
        content.setPrefWidth(520.0);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(520.0);
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/com/vgu/client/ui/assets/style.css").toExternalForm());

        ButtonType switchType = new ButtonType("Switch", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, switchType);
        Button switchButton = (Button) dialog.getDialogPane().lookupButton(switchType);
        switchButton.getStyleClass().add("accent");
        switchButton.setDisable(true);
        password.textProperty().addListener((obs, old, text) -> switchButton.setDisable(text.isEmpty()));

        // The app icon instead of the default window icon.
        dialog.setOnShown(e -> {
            Stage stage = (Stage) dialog.getDialogPane().getScene().getWindow();
            for (String size : new String[]{"32", "64", "128"}) {
                stage.getIcons().add(new Image(getClass().getResourceAsStream("/com/vgu/client/ui/assets/icon/icon-" + size + ".png")));
            }
            password.requestFocus();
        });

        // Log in without closing the dialog, so a wrong password can be corrected in place.
        switchButton.addEventFilter(javafx.event.ActionEvent.ACTION, click -> {
            click.consume();
            char[] chars = password.getText().toCharArray();
            switchButton.setDisable(true);
            password.setDisable(true);
            error.setVisible(false);
            error.setManaged(false);

            Task<Session> task = new Task<>() {
                @Override
                protected Session call() throws Exception {
                    try {
                        return new AuthService().login(username, chars);
                    } finally {
                        java.util.Arrays.fill(chars, '\0');
                    }
                }
            };
            task.setOnSucceeded(done -> {
                dialog.setResult(task.getValue());
                dialog.close();
            });
            task.setOnFailed(failed -> {
                Throwable cause = task.getException();
                error.setText(cause instanceof AuthException ? cause.getMessage() : "Something went wrong. Please try again.");
                error.setVisible(true);
                error.setManaged(true);
                password.setDisable(false);
                password.selectAll();
                password.requestFocus();
                switchButton.setDisable(password.getText().isEmpty());
            });
            startBackground(task, "switch-account");
        });

        dialog.showAndWait().ifPresent(next -> {
            Session.start(next); // closes (and logs out of) the previous account
            SceneNavigator.goTo(event, SceneNavigator.CHAT);
        });
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

        session.send(activeConversation.id(), new TextContent(text));
        messageInput.clear();

        renderMessages(activeConversation);
        renderContactList(session.conversations().list(searchField != null ? searchField.getText() : ""));
    }

    private static final long MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024; // the server's limit is 25 MiB

    /** Picks a file, uploads it encrypted, and adds an attachment message to the open conversation. */
    @FXML
    private void handleAttachFile() {
        hideAllFloatingMenus();
        if (activeConversation == null) {
            showErrorModal("No conversation selected", "Open a conversation before attaching a file.");
            return;
        }
        if (!session.online()) {
            showErrorModal("You are offline", "Log in again once the server is reachable.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Attach a file");
        File file = chooser.showOpenDialog(rootPane.getScene().getWindow());
        if (file == null) return;

        long conversationId = activeConversation.id();
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                if (file.length() > MAX_ATTACHMENT_BYTES) {
                    throw new IllegalArgumentException("Files can be at most 20 MB.");
                }
                byte[] bytes = Files.readAllBytes(file.toPath());
                FileService.FileRef ref = session.files().upload(bytes);
                session.sendFile(conversationId,
                        new FileAttachment(ref.fileId(), ref.keyBase64(), file.getName(), bytes.length));
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            if (activeConversation != null && activeConversation.id() == conversationId) renderMessages(activeConversation);
            renderContactList(session.conversations().list(searchField != null ? searchField.getText() : ""));
        });
        task.setOnFailed(e -> showErrorModal("Upload failed", describe(task.getException())));
        startBackground(task, "attach-upload");
    }

    /** Downloads an attachment, decrypts it and writes it where the user chooses. */
    private void saveAttachment(FileAttachment attachment) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save attachment");
        chooser.setInitialFileName(attachment.name());
        File target = chooser.showSaveDialog(rootPane.getScene().getWindow());
        if (target == null) return;

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                byte[] bytes = session.files().download(new FileService.FileRef(attachment.fileId(), attachment.key()));
                Files.write(target.toPath(), bytes);
                return null;
            }
        };
        task.setOnFailed(e -> showErrorModal("Download failed", describe(task.getException())));
        startBackground(task, "attach-download");
    }

    private static String describe(Throwable error) {
        return error instanceof ApiException || error instanceof IllegalArgumentException
                ? error.getMessage() : "Something went wrong. Please try again.";
    }

    private static void startBackground(Task<?> task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
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
        if (activeConversation != null && activeConversation.hasSettings()) {
            AnchorPane.clearConstraints(groupsMenu);
            AnchorPane.setTopAnchor(groupsMenu, 70.0);
            AnchorPane.setRightAnchor(groupsMenu, 20.0);
            toggleMenuVisibility(groupsMenu);
        } else {
            toggleMenuVisibility(usersMenu);
        }
    }

    /** The "..." menu: leave the open group (or delete it, for its owner), after a confirmation. */
    @FXML
    private void handleLeaveGroup() {
        hideAllFloatingMenus();
        if (activeConversation == null || activeConversation.leaveLabel(false) == null) return;
        long id = activeConversation.id();
        boolean owner = "OWNER".equals(session.conversations().myRole(id));
        showConfirmModal(owner ? "Delete this group?" : "Leave this group?",
                owner ? "The group and its messages will be removed for every member. This cannot be undone."
                        : "Its messages are deleted from this device and you need a new invite code to come back.",
                () -> {
                    if (!session.online()) {
                        showErrorModal("You are offline", "Log in again once the server is reachable.");
                        return;
                    }
                    Task<Void> task = new Task<>() {
                        @Override
                        protected Void call() throws Exception {
                            if (owner) session.groups().delete(id);
                            else session.groups().leave(id);
                            return null;
                        }
                    };
                    task.setOnSucceeded(e -> refreshFromDatabase());
                    task.setOnFailed(e -> showErrorModal(owner ? "Could not delete the group" : "Could not leave the group",
                            describe(task.getException())));
                    startBackground(task, "leave-group");
                });
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
        if (activeConversation != null && activeConversation.hasSettings()) {
            showGroupSettingsModal(activeConversation);
        } else {
            showModal("settings_group.view.fxml");
        }
    }

    public void showModal(String fxmlFileName) {
        try {
            String path = "/com/vgu/client/ui/views/settings/" + fxmlFileName;
            var resourceUrl = getClass().getResource(path);
            if (resourceUrl == null) {
                System.err.println("The FXML file can't be found: " + path);
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
                renderContactList(session.conversations().list(searchField != null ? searchField.getText() : ""));
            });
            fadeOut.play();
        }
    }

    private void showGroupSettingsModal(ConversationItem group) {
        try {
            String path = "/com/vgu/client/ui/views/settings/settings_group.view.fxml";
            var resourceUrl = getClass().getResource(path);
            if (resourceUrl == null) return;

            FXMLLoader loader = new FXMLLoader(resourceUrl);
            currentOverlay = loader.load();

            Object controller = loader.getController();
            if (controller instanceof Handling baseController) {
                baseController.setOnCloseCallback(this::closeOverlay);
            }
            if (controller instanceof Settings_group groupController) {
                groupController.setTargetGroup(group);
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

    private void showConfirmModal(String message, String subtext, Runnable onConfirm) {
        hideAllFloatingMenus();
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/vgu/client/ui/views/settings/warning.view.fxml"));
            currentOverlay = loader.load();
            if (loader.getController() instanceof Warning warning) {
                warning.setupAsConfirm(message, subtext, onConfirm);
                warning.setOnCloseCallback(this::closeOverlay);
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