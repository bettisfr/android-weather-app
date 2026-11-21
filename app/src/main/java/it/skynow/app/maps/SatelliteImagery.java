package it.skynow.app.maps;

import it.skynow.app.net.HttpText;

import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.io.StringReader;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SatelliteImagery {
    public static final String SOURCE = "https://www.ospo.noaa.gov/products/imagery/gmgsi/";
    private static final String BASE = "https://www.ospo.noaa.gov/Visualization01/cData/Atmosphere/Imagery/GMGSI/";
    private static final Pattern FRAME = Pattern.compile("GLOBCOMPLIR_v[0-9]+r[0-9]+_blend_s([0-9]{12})[0-9]+_e[0-9]+_c[0-9]+");
    private static final DateTimeFormatter FRAME_TIME = DateTimeFormatter.ofPattern("uuuuMMddHHmm");

    public static final String EUMETSAT = "https://view.eumetsat.int/";
    private static final String GEOCOLOUR_LAYER = "mtg_fd:rgb_geocolour";
    public static final String GEOCOLOUR_SOURCE = EUMETSAT + "productviewer/productDetails/" + GEOCOLOUR_LAYER;
    private static String cachedWorkspace;
    private static JSONObject cachedProducts;
    private static long cachedAt;

    public static synchronized JSONObject regionalFrames(double latitude, double longitude) throws Exception {
        boolean indianOcean = longitude > 35;
        double center = indianOcean ? 41.5 : 0;
        double distance = Math.acos(Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(longitude - center)));
        if (distance > Math.toRadians(65)) return null;
        String workspace = indianOcean ? "msg_iodc" : "mtg_fd";
        if (workspace.equals(cachedWorkspace) && android.os.SystemClock.elapsedRealtime() - cachedAt < 60_000) {
            return cachedProducts;
        }
        String infraredLayer = workspace + (indianOcean ? ":ir108" : ":ir105_hrfi");
        String capabilities = HttpText.read(EUMETSAT + "geoserver/" + workspace
                + "/wms?service=WMS&request=GetCapabilities&version=1.3.0");
        XmlPullParser xml = Xml.newPullParser();
        xml.setInput(new StringReader(capabilities));
        JSONObject products = new JSONObject();
        Instant cutoff = Instant.now().minus(Duration.ofHours(3));
        String name = "";
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.getEventType() != XmlPullParser.START_TAG) continue;
            if ("Name".equals(xml.getName())) {
                name = xml.nextText();
                if (!name.contains(":")) name = workspace + ":" + name;
            } else if ("Dimension".equals(xml.getName())
                    && "time".equals(xml.getAttributeValue(null, "name"))) {
                boolean geocolour = !indianOcean && GEOCOLOUR_LAYER.equals(name);
                if (!geocolour && !infraredLayer.equals(name)) continue;
                String defaultTime = xml.getAttributeValue(null, "default");
                JSONArray frames = regionalTimeline(xml.nextText(), defaultTime, cutoff);
                if (frames.length() > 0) {
                    products.put(geocolour ? "meteosat" : "infrared", new JSONObject().put("layer", name).put("frames", frames));
                }
                if (products.length() == (indianOcean ? 1 : 2)) break;
            }
        }
        if (products.length() == 0) return null;
        cachedWorkspace = workspace;
        cachedProducts = products;
        cachedAt = android.os.SystemClock.elapsedRealtime();
        return products;
    }

    private static JSONArray regionalTimeline(String extent, String defaultTime, Instant cutoff) throws JSONException {
        JSONArray frames = new JSONArray();
        String[] range = extent.trim().split("/");
        if (range.length != 3 || defaultTime == null) return frames;
        try {
            Instant latest = Instant.parse(defaultTime);
            if (latest.isBefore(cutoff)) return frames;
            Duration step = Duration.parse(range[2]);
            if (step.isZero() || step.isNegative()) return frames;
            Instant first = Instant.parse(range[0]);
            for (int i = 0; i < 8; i++) {
                Instant time = latest.minus(step.multipliedBy(i));
                if (time.isBefore(first)) break;
                frames.put(new JSONObject().put("time", time.toEpochMilli()));
            }
        } catch (DateTimeException invalidExtent) {
            android.util.Log.w("SkyHere", "Invalid satellite time dimension", invalidExtent);
        }
        return frames;
    }

    static JSONArray recentFrames() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        TreeMap<Instant, String> frames = new TreeMap<>();
        for (int day = 0; day <= 1 && frames.size() < 8; day++) {
            String date = today.minusDays(day).format(DateTimeFormatter.BASIC_ISO_DATE);
            String index;
            try {
                index = HttpText.read(BASE + "assets/" + date + "_GMGSI_filelist.js");
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
