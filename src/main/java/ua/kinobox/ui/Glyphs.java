package ua.kinobox.ui;

import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineJoin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Іконки кнопок геймпада, намальовані фігурами JavaFX, і підказки з ними.
 * Шаблон: "{X} Пауза    {O} Вийти" — мітки {X} {O} {S} {T} {L1} {R1} {L2} {R2} {OPT}.
 */
public final class Glyphs {

    public enum Pad { CROSS, CIRCLE, SQUARE, TRIANGLE, L1, R1, L2, R2, OPTIONS }

    private static final double SIZE = 24;
    private static final Color FACE = Color.web("#2b2b31");
    private static final Color RING = Color.web("#5a5a62");
    private static final Pattern TOKEN = Pattern.compile("\\{(X|O|S|T|L1|R1|L2|R2|OPT)}");

    private Glyphs() {
    }

    /** Одна іконка кнопки. */
    public static Node of(Pad pad) {
        return switch (pad) {
            case CROSS -> face(cross(Color.web("#7fb2ff")));
            case CIRCLE -> face(ring(Color.web("#ff7070")));
            case SQUARE -> face(square(Color.web("#f29ad0")));
            case TRIANGLE -> face(triangle(Color.web("#4fd6a5")));
            case L1 -> shoulder("L1");
            case R1 -> shoulder("R1");
            case L2 -> shoulder("L2");
            case R2 -> shoulder("R2");
            case OPTIONS -> shoulder("OPTIONS");
        };
    }

    /** Рядок підказки: текст з іконками замість міток. Кожен шматок тексту отримує стиль textStyle. */
    public static HBox hint(String template, String textStyle) {
        var box = new HBox(6);
        box.setAlignment(Pos.CENTER_LEFT);
        Matcher m = TOKEN.matcher(template);
        int last = 0;
        while (m.find()) {
            addText(box, template.substring(last, m.start()), textStyle);
            box.getChildren().add(of(padOf(m.group(1))));
            last = m.end();
        }
        addText(box, template.substring(last), textStyle);
        return box;
    }

    // ---------- Фігури ----------

    /** Кругла кнопка: темне коло з тонкою обводкою, символ по центру. */
    private static StackPane face(Node symbol) {
        var background = new Circle(SIZE / 2, FACE);
        background.setStroke(RING);
        background.setStrokeWidth(1.2);
        var pane = new StackPane(background, symbol);
        pane.setMinSize(SIZE, SIZE);
        pane.setMaxSize(SIZE, SIZE);
        return pane;
    }

    private static Node cross(Color color) {
        double h = SIZE * 0.2;
        var a = new Line(-h, -h, h, h);
        var b = new Line(-h, h, h, -h);
        for (Line line : new Line[] {a, b}) {
            line.setStroke(color);
            line.setStrokeWidth(2.2);
        }
        return new Group(a, b);
    }

    private static Node ring(Color color) {
        var circle = new Circle(SIZE * 0.22, Color.TRANSPARENT);
        circle.setStroke(color);
        circle.setStrokeWidth(2);
        return circle;
    }

    private static Node square(Color color) {
        double s = SIZE * 0.38;
        var rect = new Rectangle(s, s, Color.TRANSPARENT);
        rect.setStroke(color);
        rect.setStrokeWidth(2);
        return rect;
    }

    private static Node triangle(Color color) {
        double h = SIZE * 0.24;
        var tri = new Polygon(0, -h, h * 1.1, h * 0.75, -h * 1.1, h * 0.75);
        tri.setFill(Color.TRANSPARENT);
        tri.setStroke(color);
        tri.setStrokeWidth(2);
        tri.setStrokeLineJoin(StrokeLineJoin.ROUND);
        return tri;
    }

    /** Плечові кнопки й Options — плашка з підписом (вигляд — у CSS, клас pad-shoulder). */
    private static Node shoulder(String text) {
        var label = new Label(text);
        label.getStyleClass().add("pad-shoulder");
        return label;
    }

    // ---------- Розбір шаблону ----------

    private static Pad padOf(String token) {
        return switch (token) {
            case "X" -> Pad.CROSS;
            case "O" -> Pad.CIRCLE;
            case "S" -> Pad.SQUARE;
            case "T" -> Pad.TRIANGLE;
            case "OPT" -> Pad.OPTIONS;
            default -> Pad.valueOf(token); // L1, R1, L2, R2 — назви збігаються з enum
        };
    }

    private static void addText(HBox box, String text, String style) {
        if (!text.isEmpty()) {
            var label = new Label(text);
            label.getStyleClass().add(style);
            box.getChildren().add(label);
        }
    }
}