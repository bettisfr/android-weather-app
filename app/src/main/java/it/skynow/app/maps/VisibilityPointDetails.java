package it.skynow.app.maps;

import android.content.Context;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.json.JSONArray;

import it.skynow.app.R;
import it.skynow.app.astronomy.EclipseCatalog;
import it.skynow.app.astronomy.TransitCatalog;

final class VisibilityPointDetails {
    private VisibilityPointDetails() {}

    static JSONArray lookup(Context context, String kind, LocalDate date, double latitude, double longitude,
                            ZoneId zone, boolean clock24) throws Exception {
        Locale locale = Locale.forLanguageTag(context.getString(R.string.app_language_tag));
        DateTimeFormatter clock = DateTimeFormatter.ofPattern(clock24 ? "HH:mm" : "h:mm a", locale).withZone(zone);
        JSONArray lines = new JSONArray();
        if ("transit".equals(kind)) {
            TransitCatalog.Event event = TransitCatalog.atDate(date, latitude, longitude);
            if (event == null) return lines.put(context.getString(R.string.visibility_point_hidden));
            lines.put(context.getString(R.string.estimated_visible_range,
                    range(context, event.visibleStart, event.visibleEnd, zone, clock)));
        } else {
            EclipseCatalog.Event event = EclipseCatalog.atDate(context, date, "lunar".equals(kind), latitude, longitude);
            if (event == null) return lines.put(context.getString(R.string.visibility_point_hidden));
            lines.put(EclipseCatalog.typeLabel(context, event.type));
            lines.put(context.getString(R.string.visible_range, range(context, event.start, event.end, zone, clock)));
            lines.put(context.getString(event.estimatedPeak ? R.string.best_visible : R.string.peak_time,
                    clock.format(event.maximum)));
            if (Double.isFinite(event.obscuration)) {
                int label = event.lunar ? (event.penumbral ? R.string.moon_penumbra : R.string.moon_shadow)
                        : R.string.sun_covered;
                String coverage = context.getString(label, String.format(locale, "%.0f%%", event.obscuration));
                lines.put(event.estimatedPeak ? context.getString(R.string.approximate_value, coverage) : coverage);
            }
            if (event.totalitySeconds > 0) {
                long seconds = event.totalitySeconds;
                lines.put(seconds >= 3600
                        ? context.getString(R.string.totality_hours, seconds / 3600, seconds / 60 % 60, seconds % 60)
                        : context.getString(R.string.totality_minutes, seconds / 60, seconds % 60));
            }
        }
        return lines.put(zone.getId().replace('_', ' '));
    }

    private static String range(Context context, Instant start, Instant end, ZoneId zone, DateTimeFormatter clock) {
        ZonedDateTime first = start.atZone(zone);
        ZonedDateTime last = end.atZone(zone);
        String range = clock.format(first) + " – " + clock.format(last);
        return first.toLocalDate().equals(last.toLocalDate()) ? range : range + context.getString(R.string.next_day_suffix);
    }
}
