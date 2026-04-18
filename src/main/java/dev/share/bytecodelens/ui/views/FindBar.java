package dev.share.bytecodelens.ui.views;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.kordamp.ikonli.javafx.FontIcon;

public final class FindBar extends HBox {

    private final TextField field = new TextField();
    private final Label counter = new Label("");
    private final ToggleButton regexBtn = new ToggleButton();
    private final ToggleButton caseBtn = new ToggleButton("Aa");
    private final Button prevBtn = new Button();
    private final Button nextBtn = new Button();
    private final Button closeBtn = new Button();

    private final CodeView target;
    private int currentIndex = 0;

    public FindBar(CodeView target) {
        this.target = target;
        getStyleClass().add("find-bar");
        setPadding(new Insets(4, 8, 4, 8));
        setSpacing(6);
        setAlignment(Pos.CENTER_LEFT);
        setVisible(false);
        setManaged(false);

        FontIcon searchIcon = new FontIcon("mdi2m-magnify");
        searchIcon.setIconSize(14);
        searchIcon.getStyleClass().add("find-bar-icon");

        field.setPromptText("Find in file...");
        field.setPrefWidth(280);
        field.getStyleClass().add("find-bar-field");

        counter.getStyleClass().add("find-bar-counter");

        FontIcon rx = new FontIcon("mdi2r-regex");
        rx.setIconSize(14);
        regexBtn.setGraphic(rx);
        regexBtn.setTooltip(new Tooltip("Regex mode"));
        regexBtn.getStyleClass().add("find-bar-option");

        caseBtn.setTooltip(new Tooltip("Match case"));
        caseBtn.getStyleClass().add("find-bar-option");

        FontIcon prevIcon = new FontIcon("mdi2c-chevron-up");
        prevIcon.setIconSize(14);
        prevBtn.setGraphic(prevIcon);
        prevBtn.setTooltip(new Tooltip("Previous match  (Shift+Enter)"));
        prevBtn.getStyleClass().add("find-bar-nav");

        FontIcon nextIcon = new FontIcon("mdi2c-chevron-down");
        nextIcon.setIconSize(14);
        nextBtn.setGraphic(nextIcon);
        nextBtn.setTooltip(new Tooltip("Next match  (Enter)"));
        nextBtn.getStyleClass().add("find-bar-nav");

        FontIcon closeIcon = new FontIcon("mdi2c-close");
        closeIcon.setIconSize(14);
        closeBtn.setGraphic(closeIcon);
        closeBtn.setTooltip(new Tooltip("Close  (Esc)"));
        closeBtn.getStyleClass().add("find-bar-nav");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(searchIcon, field, counter, spacer,
                prevBtn, nextBtn, regexBtn, caseBtn, closeBtn);

        field.textProperty().addListener((obs, o, n) -> runSearch());
        regexBtn.selectedProperty().addListener((obs, o, n) -> runSearch());
        caseBtn.selectedProperty().addListener((obs, o, n) -> runSearch());

        prevBtn.setOnAction(e -> prev());
        nextBtn.setOnAction(e -> next());
        closeBtn.setOnAction(e -> hide());

        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hide();
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.F3) {
                if (e.isShiftDown()) prev();
                else next();
                e.consume();
            }
        });
    }

    public void show(String initial) {
        setVisible(true);
        setManaged(true);
        if (initial != null && !initial.isEmpty()) {
            field.setText(initial);
        }
        javafx.application.Platform.runLater(() -> {
            field.requestFocus();
            field.selectAll();
        });
    }

    public void hide() {
        setVisible(false);
        setManaged(false);
        target.clearHighlight();
        if (target.area().getScene() != null) {
            target.area().requestFocus();
        }
    }

    private void runSearch() {
        String q = field.getText();
        if (q == null || q.isEmpty()) {
            target.clearHighlight();
            counter.setText("");
            return;
        }
        HighlightRequest req = regexBtn.isSelected()
                ? HighlightRequest.regex(q, -1)
                : HighlightRequest.literal(q, -1);
        target.applyHighlight(req);
        currentIndex = 0;
        int n = target.matchCount();
        if (n > 0) {
            counter.setText("1 of " + n);
            target.goToMatch(0);
        } else {
            counter.setText("0 matches");
        }
    }

    public void nextMatch() { next(); }

    public void prevMatch() { prev(); }

    private void next() {
        int n = target.matchCount();
        if (n == 0) return;
        currentIndex = (currentIndex + 1) % n;
        target.goToMatch(currentIndex);
        counter.setText((currentIndex + 1) + " of " + n);
    }

    private void prev() {
        int n = target.matchCount();
        if (n == 0) return;
        currentIndex = (currentIndex - 1 + n) % n;
        target.goToMatch(currentIndex);
        counter.setText((currentIndex + 1) + " of " + n);
    }
}
