package it.skynow.app.weather;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import it.skynow.app.R;
import org.junit.Test;

public class WarningTitlesTest {
    @Test public void recognizesFeedTitles() {
        assertArrayEquals(new int[]{R.string.warning_level_yellow, R.string.warning_hazard_wind},
                WarningTitles.parts("Yellow Wind Warning"));
        assertArrayEquals(new int[]{R.string.warning_level_orange, R.string.warning_hazard_thunderstorm},
                WarningTitles.parts("Orange Thunderstorm Warning"));
        assertArrayEquals(new int[]{R.string.warning_level_red, R.string.warning_hazard_flooding},
                WarningTitles.parts("Red Flooding Warning"));
    }

    @Test public void acceptsBothSnowIceSpellings() {
        int[] expected = {R.string.warning_level_yellow, R.string.warning_hazard_snow_ice};
        assertArrayEquals(expected, WarningTitles.parts("Yellow Snow-ice Warning"));
        assertArrayEquals(expected, WarningTitles.parts("Yellow Snow/Ice Warning"));
    }

    @Test public void multiWordHazards() {
        assertArrayEquals(new int[]{R.string.warning_level_yellow, R.string.warning_hazard_high_temperature},
                WarningTitles.parts("Yellow High Temperature Warning"));
        assertArrayEquals(new int[]{R.string.warning_level_orange, R.string.warning_hazard_coastal_event},
                WarningTitles.parts("Orange Coastal Event Warning"));
    }

    @Test public void unknownTitlesAreLeftAlone() {
        assertNull(WarningTitles.parts("Green Wind Warning"));
        assertNull(WarningTitles.parts("Yellow Avalanche Warning"));
        assertNull(WarningTitles.parts("Warning"));
    }
}
