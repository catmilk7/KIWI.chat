package com.vgu.client;

import atlantafx.base.theme.PrimerLight;
import javafx.application.*;
import java.util.*;
import javafx.fxml.*;
import javafx.scene.*;
import javafx.scene.text.*;
import javafx.scene.image.*;
import javafx.stage.*;
import java.net.*;

public class Main extends Application {
    private static final int SINGLE_INSTANCE_PORT = 47821;
    private static ServerSocket serverSocket;

    private static boolean lockInstance() {
        try {
            serverSocket = new ServerSocket(SINGLE_INSTANCE_PORT, 1, InetAddress.getLocalHost());
            return true; // Successfully bound -> We are the first instance
        } catch (Exception e) {
            return false; // Port is already bound -> Another instance is running
        }
    }

    @Override
    public void init(){
        loadFont();
    }

    @Override
    public void start(Stage primaryStage) throws Exception {

        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());

        Parent root = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("/com/vgu/client/ui/views/auth/login.view.fxml")));
        Scene scene = new Scene(root, 1200, 800);

        primaryStage.getIcons().addAll(
                new Image(Objects.requireNonNull(getClass().getResourceAsStream("/com/vgu/client/ui/assets/icon/icon-32.png"))),
                new Image(Objects.requireNonNull(getClass().getResourceAsStream("/com/vgu/client/ui/assets/icon/icon-64.png"))),
                new Image(Objects.requireNonNull(getClass().getResourceAsStream("/com/vgu/client/ui/assets/icon/icon-128.png")))
        );

        primaryStage.setTitle("KIWI.chat | ALPHA");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private void loadFont() {
        String[] variants = {
                "Bold.ttf", "BoldItalic.ttf", "ExtraBold.ttf", "ExtraBoldItalic.ttf",
                "ExtraLight.ttf", "ExtraLightItalic.ttf", "Italic.ttf", "Light.ttf",
                "LightItalic.ttf", "Medium.ttf", "MediumItalic.ttf", "Regular.ttf",
                "SemiBold.ttf", "SemiBoldItalic.ttf", "Thin.ttf", "ThinItalic.ttf"
        };

        String basePath = "/com/vgu/client/ui/assets/font/JetBrainsMono-";

        for (String variant : variants) {
            String fullPath = basePath + variant;
            Font font = Font.loadFont(getClass().getResourceAsStream(fullPath), 12);

            if (font == null) {
                System.err.println("Failed to load font: " + fullPath);
            }
        }
    }

    public static void main(String[] args) {
        if (!lockInstance()) {
            System.err.println("Another instance of KIWI.chat is already running. Exiting...");
            System.exit(0);
        }

        launch(args);
    }
}
