package it.skynow.app.weather;

import android.util.Xml;
import it.skynow.app.net.HttpText;
import java.text.Normalizer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.xmlpull.v1.XmlPullParser;

public final class MeteoAlarm {
    private static final String FEED = "https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-italy";
    private static final String ATOM = "http://www.w3.org/2005/Atom";
    private static final String CAP = "urn:oasis:names:tc:emergency:cap:1.2";

    public static List<Warning> warnings() throws Exception {
        return HttpText.stream(FEED, 8000, body -> {
            XmlPullParser xml = Xml.newPullParser();
            xml.setInput(body, null);
            return parse(xml);
        });
    }

    /** Reads the current public alerts from the CAP entries of a MeteoAlarm Atom feed, earliest first. */
    static List<Warning> parse(XmlPullParser xml) throws Exception {
        xml.nextTag();
        xml.require(XmlPullParser.START_TAG, ATOM, "feed");
        List<Warning> warnings = new ArrayList<>();
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.getEventType() == XmlPullParser.START_TAG && xml.getDepth() == 2
                    && ATOM.equals(xml.getNamespace()) && "entry".equals(xml.getName())) {
                Warning warning = readWarning(xml);
                if (warning != null) warnings.add(warning);
            }
        }
        warnings.sort(Comparator.comparing(warning -> warning.start));
        return warnings;
    }

    private static Warning readWarning(XmlPullParser xml) throws Exception {
        String area = "", event = "", status = "", message = "", scope = "", code = "";
        Instant onset = null, effective = null, expires = null;
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.getEventType() == XmlPullParser.END_TAG && xml.getDepth() == 2) break;
            if (xml.getEventType() != XmlPullParser.START_TAG) continue;
            if (xml.getDepth() == 3 && CAP.equals(xml.getNamespace())) {
                switch (xml.getName()) {
                    case "areaDesc": area = xml.nextText(); break;
                    case "event": event = xml.nextText(); break;
                    case "status": status = xml.nextText(); break;
                    case "message_type": message = xml.nextText(); break;
                    case "scope": scope = xml.nextText(); break;
                    case "onset": onset = instant(xml.nextText()); break;
                    case "effective": effective = instant(xml.nextText()); break;
                    case "expires": expires = instant(xml.nextText()); break;
                }
            } else if (xml.getDepth() == 4 && "value".equals(xml.getName())) {
                String value = xml.nextText();
                if (value.matches("IT[0-9]{3}")) code = value;
            }
        }
        Instant start = onset != null ? onset : effective;
        if (!"Actual".equals(status) || !"Public".equals(scope)
                || !("Alert".equals(message) || "Update".equals(message))
                || start == null || expires == null || !expires.isAfter(start) || area.isEmpty() || event.isEmpty()) return null;
        return new Warning(area, event, code, start, expires);
    }

    private static Instant instant(String text) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    public static String regionKey(String region) {
        String key = Normalizer.normalize(region.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("[^a-z]", "");
        switch (key) {
            case "aostavalley": return "valledaosta";
            case "emiliaeromagna": return "emiliaromagna";
            case "piedmont": return "piemonte";
            case "sardinia": return "sardegna";
            case "sicily": return "sicilia";
            case "tuscany": return "toscana";
            case "apulia": return "puglia";
            case "lombardy": return "lombardia";
            case "trentinoaltoadige": case "trentinosudtirol": case "trentinosouthtyrol": return "trentinoaltoadige";
            default: return key;
        }
    }

    public static final class Warning {
        public final String area, event, code;
        public final Instant start, expires;

        Warning(String area, String event, String code, Instant start, Instant expires) {
            this.area = area;
            this.event = event;
            this.code = code;
            this.start = start;
            this.expires = expires;
        }

        public String url() {
            return "https://meteoalarm.org/" + (code.isEmpty() ? "?region=IT" : "?geocode=EMMA_ID:" + code);
        }
    }
}
