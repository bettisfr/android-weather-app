package it.skynow.app.weather;

import static org.junit.Assert.assertEquals;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.junit.Test;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;

public class MeteoAlarmTest {
    private static List<MeteoAlarm.Warning> fixture() throws Exception {
        try (InputStream feed = MeteoAlarmTest.class.getResourceAsStream("/meteoalarm-italy.xml")) {
            // android.util.Xml.newPullParser() returns a namespace-aware KXmlParser.
            XmlPullParser parser = new KXmlParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            parser.setInput(feed, null);
            return MeteoAlarm.parse(parser);
        }
    }

    @Test public void keepsOnlyActualPublicAlertsAndUpdates() throws Exception {
        List<MeteoAlarm.Warning> warnings = fixture();
        // Cancelled, test and already-expired entries are dropped.
        assertEquals(2, warnings.size());
    }

    @Test public void sortsByStartAndPrefersOnsetOverEffective() throws Exception {
        List<MeteoAlarm.Warning> warnings = fixture();
        MeteoAlarm.Warning umbria = warnings.get(0);
        assertEquals("Umbria", umbria.area);
        assertEquals("Yellow Wind Warning", umbria.event);
        // No onset: the warning starts when it becomes effective.
        assertEquals(Instant.parse("2026-11-28T00:00:00Z"), umbria.start);
        MeteoAlarm.Warning sicily = warnings.get(1);
        assertEquals("Sicilia", sicily.area);
        assertEquals(Instant.parse("2026-11-28T06:00:00Z"), sicily.start);
        assertEquals(Instant.parse("2026-11-29T11:59:00Z"), sicily.expires);
    }

    @Test public void linksToTheRegionGeocode() throws Exception {
        List<MeteoAlarm.Warning> warnings = fixture();
        assertEquals("https://meteoalarm.org/?geocode=EMMA_ID:IT010", warnings.get(0).url());
        assertEquals("IT018", warnings.get(1).code);
    }

    @Test public void regionKeysMatchEnglishAndItalianNames() {
        assertEquals(MeteoAlarm.regionKey("Sicilia"), MeteoAlarm.regionKey("Sicily"));
        assertEquals(MeteoAlarm.regionKey("Toscana"), MeteoAlarm.regionKey("Tuscany"));
        assertEquals(MeteoAlarm.regionKey("Valle d'Aosta"), MeteoAlarm.regionKey("Aosta Valley"));
        assertEquals(MeteoAlarm.regionKey("Emilia-Romagna"), MeteoAlarm.regionKey("Emilia e Romagna"));
        assertEquals(MeteoAlarm.regionKey("Trentino-Alto Adige"), MeteoAlarm.regionKey("Trentino-Südtirol"));
        assertEquals(MeteoAlarm.regionKey("Trentino-Alto Adige"), MeteoAlarm.regionKey("Trentino South Tyrol"));
        assertEquals("umbria", MeteoAlarm.regionKey("Umbria"));
    }
}
