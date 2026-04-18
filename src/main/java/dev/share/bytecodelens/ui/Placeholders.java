package dev.share.bytecodelens.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

public final class Placeholders {

    private Placeholders() {}

    public static Node build(String iconLiteral, String title, String hint) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(40);
        icon.getStyleClass().add("placeholder-icon");

        Label t = new Label(title);
        t.getStyleClass().add("placeholder-title");

        Label h = new Label(hint);
        h.getStyleClass().add("placeholder-hint");
        h.setWrapText(true);

        VBox box = new VBox(10, icon, t, h);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(30));
        box.setMaxWidth(400);
        return box;
    }
}
