package ua.kinobox.nav;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import org.junit.jupiter.api.Test;
import ua.kinobox.input.Command;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpatialNavigatorTest {

    /**
     * Дві «полиці» по 4 картки, другу зсунуто на пів картки вправо.
     */
    private final List<Bounds> grid = List.of(
            box(0, 0), box(110, 0), box(220, 0), box(330, 0),         // 0..3
            box(55, 100), box(165, 100), box(275, 100), box(385, 100)  // 4..7
    );

    private static Bounds box(double x, double y) {
        return new BoundingBox(x, y, 100, 60);
    }

    @Test
    void rightPicksNeighbourInSameRow() {
        assertEquals(2, SpatialNavigator.pick(box(110, 0), grid, Command.RIGHT));
    }

    @Test
    void leftAtRowStartFindsNothing() {
        assertEquals(-1, SpatialNavigator.pick(box(0, 0), grid, Command.LEFT));
    }

    @Test
    void downPicksMostAlignedCardInNextRow() {
        // джерело x 210..310 перекривається з 5 (165..265) і 6 (275..375); центр 260 ближчий до 5
        assertEquals(5, SpatialNavigator.pick(box(210, 0), grid, Command.DOWN));
    }

    @Test
    void downPrefersNearRowOverFarAlignedRow() {
        // сусідній ряд зсунутий, але перекривається; далекий ряд вирівняний ідеально
        var cards = List.of(box(60, 100), box(0, 400));
        assertEquals(0, SpatialNavigator.pick(box(0, 0), cards, Command.DOWN));
    }

    @Test
    void upFromFirstRowFindsNothing() {
        assertEquals(-1, SpatialNavigator.pick(box(110, 0), grid, Command.UP));
    }

    @Test
    void scrollOffsetKeepsMarginAndClamps() {
        // контент 1000, в'юпорт 400, елемент 500..600 — прокрутити так, щоб правий край + 50 був видимим
        double v = ScrollUtil.offset(0, 1000, 400, 500, 600, 50, false);
        assertEquals((650 - 400) / 600.0, v, 1e-9);
        // елемент на початку — назад до 0
        assertEquals(0, ScrollUtil.offset(0.5, 1000, 400, 10, 110, 50, false), 1e-9);
        // snapToStart вирівнює верх елемента з відступом
        assertEquals((500 - 50) / 600.0, ScrollUtil.offset(0, 1000, 400, 500, 600, 50, true), 1e-9);
    }
}
