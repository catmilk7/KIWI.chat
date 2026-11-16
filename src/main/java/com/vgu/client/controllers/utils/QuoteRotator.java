package com.vgu.client.controllers.utils;

import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Label;
import javafx.util.Duration;

import java.util.List;
import java.util.Random;

/** Shows a random tagline in a label and swaps it for another one every few seconds. */
public final class QuoteRotator {

    private static final List<String> QUOTES = List.of(
            "Secure. Private. Yours.",
            "Sécurisé. Confidentiel. À vous.",
            "Sicher. Privat. Deins.",
            "Säkert. Privat. Ditt.",
            "Надежно. Конфиденциально. Ваше.",
            "Bezpieczne. Prywatne. Twoje.",
            "Aman. Pribadi. Milik Anda.",
            "Bảo mật. Riêng tư. Của bạn.");

    private static final Duration INTERVAL = Duration.seconds(5);
    private static final Duration FADE = Duration.millis(300);

    private final Label label;
    private final Random random = new Random();

    private QuoteRotator(Label label) {
        this.label = label;
    }

    /** Starts rotating quotes in {@code label}. Does nothing if the label is null. */
    public static void start(Label label) {
        if (label == null) {
            return;
        }
        QuoteRotator rotator = new QuoteRotator(label);
        label.setText(QUOTES.get(rotator.random.nextInt(QUOTES.size())));

        Timeline timeline = new Timeline(new KeyFrame(INTERVAL, e -> rotator.next()));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
    }

    private void next() {
        FadeTransition out = new FadeTransition(FADE, label);
        out.setFromValue(1.0);
        out.setToValue(0.0);
        out.setOnFinished(e -> {
            String next;
            do {
                next = QUOTES.get(random.nextInt(QUOTES.size()));
            } while (next.equals(label.getText()));
            label.setText(next);

            FadeTransition in = new FadeTransition(FADE, label);
            in.setFromValue(0.0);
            in.setToValue(1.0);
            in.play();
        });
        out.play();
    }
}
