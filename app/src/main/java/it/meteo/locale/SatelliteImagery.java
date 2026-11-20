package it.meteo.locale;

import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SatelliteImagery {
    static final String SOURCE = "https://www.ospo.noaa.gov/products/imagery/gmgsi/";
    private static final String BASE = "https://www.ospo.noaa.gov/Visualization01/cData/Atmosphere/Imagery/GMGSI/";
    private static final Pattern FRAME = Pattern.compile("GLOBCOMPLIR_v[0-9]+r[0-9]+_blend_s([0-9]{12})[0-9]+_e[0-9]+_c[0-9]+");
    private static final DateTimeFormatter FRAME_TIME = DateTimeFormatter.ofPattern("uuuuMMddHHmm");

    static final String EUMETSAT = "https://view.eumetsat.int/";
    private static final String WMS = EUMETSAT + "geoserver/wms";

    static JSONObject regionalFrames(double latitude, double longitude) throws Exception {
        boolean indianOcean = longitude > 35;
        double center = indianOcean ? 41.5 : 0;
        double distance = Math.acos(Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(longitude - center)));
        if (distance > Math.toRadians(65)) return null;
        String layer = indianOcean ? "msg_iodc:ir108" : "mtg_fd:ir105_hrfi";
        String capabilities = OpenMeteo.readBody(WMS + "?service=WMS&request=GetCapabilities&version=1.3.0");
        XmlPullParser xml = Xml.newPullParser();
        xml.setInput(new StringReader(capabilities));
        String name = "";
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.getEventType() != XmlPullParser.START_TAG) continue;
            if ("Name".equals(xml.getName())) {
                name = xml.nextText();
            } else if (layer.equals(name) && "Dimension".equals(xml.getName())
                    && "time".equals(xml.getAttributeValue(null, "name"))) {
                Instant latest = Instant.parse(xml.getAttributeValue(null, "default"));
                String[] range = xml.nextText().split("/");
                if (range.length != 3 || latest.isBefore(Instant.now().minus(Duration.ofHours(3)))) return null;
                Duration step = Duration.parse(range[2]);
                if (step.isZero() || step.isNegative()) return null;
                Instant first = Instant.parse(range[0]);
                JSONArray frames = new JSONArray();
                for (int i = 0; i < 8; i++) {
                    Instant time = latest.minus(step.multipliedBy(i));
                    if (time.isBefore(first)) break;
                    frames.put(new JSONObject().put("time", time.toEpochMilli()));
                }
                return new JSONObject().put("layer", layer).put("frames", frames);
            }
        }
        return null;
    }

    static JSONArray recentFrames() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        TreeMap<Instant, String> frames = new TreeMap<>();
        for (int day = 0; day <= 1 && frames.size() < 8; day++) {
            String date = today.minusDays(day).format(DateTimeFormatter.BASIC_ISO_DATE);
            String index;
            try {
                index = OpenMeteo.readBody(BASE + "assets/" + date + "_GMGSI_filelist.js");
            } catch (IOException unavailable) {
                continue;
            }
            Matcher matches = FRAME.matcher(index);
            while (matches.find()) {
                Instant time = LocalDateTime.parse(matches.group(1), FRAME_TIME).toInstant(ZoneOffset.UTC);
                frames.put(time, BASE + "GMGSI_LW_GIF_C/" + matches.group() + ".gif");
            }
        }
        int remaining = Math.min(8, frames.size());
        JSONArray result = new JSONArray();
        while (remaining-- > 0) {
            java.util.Map.Entry<Instant, String> frame = frames.pollLastEntry();
            result.put(new JSONObject().put("time", frame.getKey().toEpochMilli()).put("url", frame.getValue()));
        }
        return result;
    }

    private SatelliteImagery() {}
}
