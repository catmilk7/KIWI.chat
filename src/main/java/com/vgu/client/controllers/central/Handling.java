package com.vgu.client.controllers.central;

import javafx.animation.FadeTransition;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import java.io.IOException;

public class Handling {
    @FXML
    protected StackPane overlayRoot;

    @FXML
    protected VBox modalCard;

    protected Runnable onCloseCallback;

    public void setOnCloseCallback(Runnable callback) {
        this.onCloseCallback = callback;
    }

    @FXML
    public void initialize() {
        if (modalCard != null) {
            modalCard.addEventFilter(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
        }
        onModalInitialized();
    }

    protected void onModalInitialized() {}

    @FXML
    protected void handleOverlayClick(MouseEvent event) {
        if (event.getTarget() == overlayRoot) {
            handleClose();
        }
    }

    @FXML
    protected void handleClose() {
        if (onCloseCallback != null) {
            onCloseCallback.run();
        }
    }

    protected void showError(String title, String detail) {
        if (overlayRoot == null || !(overlayRoot.getParent() instanceof Pane parentPane)) return;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/vgu/client/ui/views/settings/warning.view.fxml"));
            Parent warningOverlay = loader.load();

            Warning controller = loader.getController();
            controller.setupAsError(title, detail);
            controller.setOnCloseCallback(() -> {
                FadeTransition fadeOut = new FadeTransition(Duration.millis(150), warningOverlay);
                fadeOut.setFromValue(1.0);
                fadeOut.setToValue(0.0);
                fadeOut.setOnFinished(e -> parentPane.getChildren().remove(warningOverlay));
                fadeOut.play();
            });

            AnchorPane.setTopAnchor(warningOverlay, 0.0);
            AnchorPane.setBottomAnchor(warningOverlay, 0.0);
            AnchorPane.setLeftAnchor(warningOverlay, 0.0);
            AnchorPane.setRightAnchor(warningOverlay, 0.0);

            parentPane.getChildren().add(warningOverlay);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
