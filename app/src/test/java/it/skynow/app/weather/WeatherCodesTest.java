package it.skynow.app.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import it.skynow.app.R;
import org.junit.Test;

public class WeatherCodesTest {
    /** Every WMO code that Open-Meteo documents. */
    private static final int[] CODES = {0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67,
            71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99};

    @Test public void everyCodeHasADescription() {
        for (int code : CODES) {
            assertNotEquals("Code " + code, R.string.conditions_unavailable, WeatherCodes.descriptionRes(code));
        }
        assertEquals(R.string.conditions_unavailable, WeatherCodes.descriptionRes(42));
    }

    @Test public void everyCustomGlyphHasADrawable() {
        for (int code : CODES) {
            for (boolean night : new boolean[] {false, true}) {
                String symbol = WeatherCodes.symbol(code, night);
                char glyph = symbol.charAt(0);
                if (glyph >= '' && glyph <= '') {
                    assertNotEquals("Code " + code, 0, WeatherCodes.glyphDrawable(glyph));
                }
            }
        }
    }

    @Test public void ordinaryCharactersHaveNoDrawable() {
        assertEquals(0, WeatherCodes.glyphDrawable('A'));
        assertEquals(0, WeatherCodes.glyphDrawable(''));
    }

    @Test public void dayAndNightVariants() {
        assertEquals("☀️", WeatherCodes.symbol(0, false));
        assertEquals("🌙", WeatherCodes.symbol(0, true));
        assertNotEquals(WeatherCodes.symbol(1, false), WeatherCodes.symbol(1, true));
        assertEquals(WeatherCodes.symbol(61, false), WeatherCodes.symbol(61, true));
    }
}
