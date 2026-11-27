package it.skynow.app.dashboard;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.*;
import android.widget.ScrollView;
import it.skynow.app.R;
import it.skynow.app.astronomy.EclipseCatalog;
import it.skynow.app.astronomy.EclipseIcon;
import it.skynow.app.astronomy.TransitCatalog;
import it.skynow.app.maps.RadarActivity;
import it.skynow.app.ui.Formats;
import it.skynow.app.ui.SkyViews;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;

/** Eclipse and transit accordions of the Astronomy tab, including the section opened from a notification. */
final class EventsSection {
    private final Activity activity;
    private final SkyViews views;
    private final AstronomyTab.Host host;
    private final AstronomyWidgets widgets;
    /** The tab's live "visible now" badges, refreshed by {@link #refreshVisibility}. */
    private final List<AstronomyTab.VisibilityBadge> visibilityBadges;
    private final Runnable refreshVisibility;

    // The forecast the section was last attached with; data is null when only events are shown.
    private LinearLayout page;
    private ScrollView scroll;
    private JSONObject data;
    private double latitude;
    private double longitude;
    private String place;

    private String pendingAstronomyEventSection;
    private ZoneId astronomyNotificationZone;
    private boolean solarEclipsesExpanded;
    private boolean lunarEclipsesExpanded;
    private boolean transitsExpanded;

    EventsSection(Activity activity, SkyViews views, AstronomyTab.Host host, AstronomyWidgets widgets,
                  List<AstronomyTab.VisibilityBadge> visibilityBadges, Runnable refreshVisibility) {
        this.activity = activity;
        this.views = views;
        this.host = host;
        this.widgets = widgets;
        this.visibilityBadges = visibilityBadges;
        this.refreshVisibility = refreshVisibility;
    }

    private Formats formats() {
        return host.formats();
    }

    /** Adds the solar eclipse, lunar eclipse and transit cards to the end of {@code page}. */
    void attach(LinearLayout page, ScrollView scroll, JSONObject data, double latitude, double longitude, String place) {
        this.page = page;
        this.scroll = scroll;
        this.data = data;
        this.latitude = latitude;
        this.longitude = longitude;
        this.place = place;
        eclipsesAndTransits();
    }

    void setPendingEventSection(String section) {
        pendingAstronomyEventSection = section;
    }

    boolean hasPendingEventSection() {
        return pendingAstronomyEventSection != null;
    }

    void setNotificationZone(ZoneId zone) {
        astronomyNotificationZone = zone;
    }

    ZoneId notificationZone() {
        return astronomyNotificationZone;
    }

    private void eclipsesAndTransits() {
        double lat = latitude;
        double lon = longitude;
        ZoneId zone = astronomyEventZone();
        boolean openFromNotification = pendingAstronomyEventSection != null;
        boolean openTransits = "TRANSIT".equals(pendingAstronomyEventSection);
        boolean openLunar = "LUNAR".equals(pendingAstronomyEventSection);
        solarEclipsesExpanded |= "SOLAR".equals(pendingAstronomyEventSection);
        lunarEclipsesExpanded |= openLunar;
        transitsExpanded |= openTransits;
        LinearLayout solarEclipses = widgets.astronomyAccordion(R.string.solar_eclipses, solarEclipsesExpanded,
                expanded -> solarEclipsesExpanded = expanded,
                content -> {
                    content.setPadding(0, views.dp(10), 0, 0);
                    new EclipseList(content, false, lat, lon, zone).showNext();
                });
        LinearLayout lunarEclipses = widgets.astronomyAccordion(R.string.lunar_eclipses, lunarEclipsesExpanded,
                expanded -> lunarEclipsesExpanded = expanded,
                content -> {
                    content.setPadding(0, views.dp(10), 0, 0);
                    new EclipseList(content, true, lat, lon, zone).showNext();
                });
        views.addSpaced(page, solarEclipses, 12);
        views.addSpaced(page, lunarEclipses, 9);
        LinearLayout transitsCard = widgets.astronomyAccordion(R.string.planetary_transits, transitsExpanded,
                expanded -> transitsExpanded = expanded, content -> {
            content.setPadding(0, views.dp(10), 0, 0);
            List<TransitCatalog.Event> transits = TransitCatalog.upcoming(lat, lon, Instant.now());
            if (transits.isEmpty()) content.addView(views.label(activity.getString(R.string.no_transits), 14, views.muted));
            else appendTransits(content, transits, zone, 0);
        });
        views.addSpaced(page, transitsCard, 9);
        if (openFromNotification) {
            View section = openTransits ? transitsCard : openLunar ? lunarEclipses : solarEclipses;
            ScrollView target = scroll;
            LinearLayout eventPage = page;
            Runnable reveal = () -> {
                if (pendingAstronomyEventSection == null || !section.isAttachedToWindow()) return;
                android.graphics.Rect bounds = new android.graphics.Rect();
                section.getDrawingRect(bounds);
                eventPage.offsetDescendantRectToMyCoords(section, bounds);
                target.scrollTo(0, bounds.top);
            };
            section.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    target.post(reveal));
            target.post(reveal);
        }
    }

    private void appendTransits(LinearLayout list, List<TransitCatalog.Event> items, ZoneId zone, int start) {
        int end = Math.min(items.size(), start + AstronomyTab.ASTRONOMY_PAGE_SIZE);
        for (int i = start; i < end; i++) addTransit(list, items.get(i), zone);
        refreshVisibility.run();
        if (end >= items.size()) return;
        Button more = views.moreButton();
        more.setContentDescription(activity.getString(R.string.more_transits));
        more.setOnClickListener(v -> {
            list.removeView(more);
            appendTransits(list, items, zone, end);
        });
        list.addView(more);
    }

    private final class EclipseList {
        private final LinearLayout events;
        private final LinearLayout footer;
        private final List<EclipseCatalog.Event> items = new ArrayList<>();
        private final boolean lunar;
        private final double latitude;
        private final double longitude;
        private final ZoneId zone;
        private int year;
        private int shown;
        private int goal;

        EclipseList(LinearLayout parent, boolean lunar, double latitude, double longitude, ZoneId zone) {
            this.lunar = lunar;
            this.latitude = latitude;
            this.longitude = longitude;
            this.zone = zone;
            year = Instant.now().atZone(zone).getYear();
            events = views.column();
            footer = views.column();
            parent.addView(events);
            parent.addView(footer);
        }

        void showNext() {
            goal = shown + AstronomyTab.ASTRONOMY_PAGE_SIZE;
            fill(2);
        }

        private void fill(int requestsLeft) {
            footer.removeAllViews();
            int available = Math.min(goal, items.size());
            while (shown < available) addEclipse(events, items.get(shown++), zone);
            refreshVisibility.run();
            int catalogLastYear = lunar ? EclipseCatalog.LAST_OFFLINE_LUNAR_YEAR : EclipseCatalog.LAST_OFFLINE_SOLAR_YEAR;
            boolean hasMoreYears = year <= catalogLastYear;
            if (shown < goal && requestsLeft > 0 && hasMoreYears) {
                fetch(requestsLeft);
                return;
            }
            if (shown == 0) {
                int lastYear = Math.min(year - 1, catalogLastYear);
                footer.addView(views.label(activity.getString(R.string.no_eclipses_until, lastYear), 14, views.muted));
            }
            if (!hasMoreYears && shown >= items.size()) return;
            Button more = views.moreButton();
            more.setOnClickListener(v -> showNext());
            more.setContentDescription(lunar ? activity.getString(R.string.more_lunar_eclipses) : activity.getString(R.string.more_solar_eclipses));
            footer.addView(more);
        }

        private void fetch(int requestsLeft) {
            footer.addView(views.loadingStatus(activity.getString(R.string.loading_eclipses)));
            int startYear = year;
            CompletableFuture.supplyAsync(() -> {
                try {
                    return EclipseCatalog.upcoming(activity.getApplicationContext(), lunar, startYear, latitude, longitude, Instant.now());
                } catch (Exception error) {
                    android.util.Log.w("SkyHere", "Eclipse catalog unavailable: " + error, error);
                    return null;
                }
            }).thenAccept(results -> activity.runOnUiThread(() -> {
                if (activity.isDestroyed() || !footer.isAttachedToWindow()) return;
                if (results == null) {
                    footer.removeAllViews();
                    footer.addView(views.label(activity.getString(R.string.eclipse_data_unavailable), 14, views.muted));
                    footer.addView(views.button(activity.getString(R.string.retry), () -> fill(requestsLeft)));
                    return;
                }
                year += EclipseCatalog.yearsPerRequest(lunar);
                items.addAll(results);
                fill(requestsLeft - 1);
            }));
        }
    }

    private void addEclipse(LinearLayout parent, EclipseCatalog.Event event, ZoneId zone) {
        LinearLayout entry = views.column();
        entry.setPadding(views.dp(11), views.dp(10), views.dp(11), views.dp(10));
        entry.setBackground(views.background(activity.getColor(R.color.sky_subtle), 12));
        String eventUrl = event.detailsUrl(latitude, longitude);
        ArrayList<String> localDetails = new ArrayList<>();
        LinearLayout heading = views.row();
        EclipseIcon icon = new EclipseIcon(activity, event);
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(views.dp(36), views.dp(36));
        iconSize.rightMargin = views.dp(9);
        heading.addView(icon, iconSize);
        LinearLayout title = views.column();
        title.addView(views.label(EclipseCatalog.typeLabel(activity, event.type), 15, views.ink));
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView live = widgets.nowActionBadge(entry::performClick);
        live.setVisibility(View.GONE);
        LinearLayout.LayoutParams liveSize = new LinearLayout.LayoutParams(-2, -2);
        liveSize.setMarginStart(views.dp(6));
        heading.addView(live, liveSize);
        View arrow = views.chevron();
        heading.addView(arrow);
        visibilityBadges.add(new AstronomyTab.VisibilityBadge(event.start.getEpochSecond(), event.end.getEpochSecond(), live, arrow));
        entry.addView(heading);
        ZonedDateTime start = event.start.atZone(zone);
        ZonedDateTime end = event.end.atZone(zone);
        DateTimeFormatter date = formats().shortDate(true);
        TextView when = views.label(start.format(date), 13, views.muted);
        localDetails.add(EclipseCatalog.typeLabel(activity, event.type) + " · " + when.getText());
        when.setPadding(0, views.dp(3), 0, 0);
        title.addView(when);
        String range = start.format(formats().clockFormat()) + " – " + end.format(formats().clockFormat());
        if (!start.toLocalDate().equals(end.toLocalDate())) range += activity.getString(R.string.next_day_suffix);
        TextView visible = views.label(activity.getString(R.string.visible_range, range), 14, views.ink);
        localDetails.add(visible.getText().toString());
        visible.setPadding(0, views.dp(7), 0, 0);
        entry.addView(visible);
        Duration duration = Duration.between(event.start, event.end);
        long minutes = Math.max(1, Math.round(duration.getSeconds() / 60.0));
        String durationText = formats().durationText(minutes);
        String durationLabel = activity.getString(event.horizonLimited ? R.string.estimated_duration : R.string.duration_value, durationText);
        durationLabel += " · " + activity.getString(event.estimatedPeak ? R.string.best_visible : R.string.peak_time,
                event.maximum.atZone(zone).format(formats().clockFormat()));
        TextView details = views.label(durationLabel, 13, views.muted);
        localDetails.add(durationLabel);
        details.setPadding(0, views.dp(3), 0, 0);
        entry.addView(details);
        String centralDuration = EclipseCatalog.centralPhaseDuration(activity, event);
        if (centralDuration != null) {
            TextView centralLabel = views.label(centralDuration, 13, views.accent);
            localDetails.add(centralDuration);
            centralLabel.setPadding(0, views.dp(3), 0, 0);
            entry.addView(centralLabel);
        }
        if (Double.isFinite(event.obscuration)) {
            int disk = event.lunar ? (event.penumbral ? R.string.moon_penumbra : R.string.moon_shadow) : R.string.sun_covered;
            String coverageText = activity.getString(disk, event.formattedObscuration(formats().appLocale()));
            if (event.estimatedPeak) coverageText = activity.getString(R.string.approximate_value, coverageText);
            TextView coverage = views.label(coverageText, 13, views.accent);
            localDetails.add(coverageText);
            coverage.setPadding(0, views.dp(3), 0, 0);
            entry.addView(coverage);
        }
        views.cardAction(entry, () -> openEclipseMap(event, eventUrl, localDetails));
        views.addSpaced(parent, entry, 7);
    }

    private void openEclipseMap(EclipseCatalog.Event event, String url, ArrayList<String> localDetails) {
        try {
            activity.startActivity(visibilityMapIntent(event.catalogDate(), url, localDetails)
                    .putExtra("visibilityKind", event.lunar ? "lunar" : "solar")
                    .putExtra("visibilitySamples", event.lunarVisibilitySamples().toString()));
        } catch (org.json.JSONException error) {
            widgets.openAstronomyPage(url);
        }
    }

    private android.content.Intent visibilityMapIntent(LocalDate date, String url, ArrayList<String> localDetails) {
        return new android.content.Intent(activity, RadarActivity.class)
                .putExtra("layer", RadarActivity.Layer.ECLIPSE.name())
                .putExtra("place", place).putExtra("lat", latitude).putExtra("lon", longitude)
                .putExtra("timeZone", astronomyEventZone().getId())
                .putExtra("clock24", formats().clock24)
                .putExtra("eventDate", date.toString()).putExtra("detailsUrl", url)
                .putStringArrayListExtra("localDetails", localDetails);
    }

    private void addTransit(LinearLayout parent, TransitCatalog.Event event, ZoneId zone) {
        LinearLayout entry = views.column();
        entry.setPadding(views.dp(11), views.dp(10), views.dp(11), views.dp(10));
        entry.setBackground(views.background(activity.getColor(R.color.sky_subtle), 12));
        String eventUrl = event.detailsUrl(latitude, longitude);
        String transitName = activity.getString(R.string.transit_of,
                activity.getString(event.transit.planet.equals("Mercury") ? R.string.mercury : R.string.venus));
        TransitCatalog.DiskPosition position = event.transit.diskPosition(event.visibleStart, event.visibleEnd);

        LinearLayout header = views.row();
        View icon = new View(activity) {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                float x = getWidth() / 2f;
                float y = getHeight() / 2f;
                float radius = Math.min(x, y) * .73f;
                paint.setColor(0xffffeaca);
                canvas.drawCircle(x, y, radius * 1.25f, paint);
                paint.setColor(0xffe9a946);
                canvas.drawCircle(x, y, radius, paint);
                paint.setColor(0xff293a58);
                canvas.drawCircle(x + radius * (float) position.x, y + radius * (float) position.y,
                        radius * (event.transit.planet.equals("Mercury") ? .10f : .18f), paint);
            }
        };
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(views.dp(36), views.dp(36));
        iconSize.rightMargin = views.dp(9);
        header.addView(icon, iconSize);
        LinearLayout title = views.column();
        title.addView(views.label(transitName, 15, views.ink));
        ZonedDateTime date = event.visibleStart.atZone(zone);
        TextView when = views.label(date.format(formats().shortDate(true)), 13, views.muted);
        when.setPadding(0, views.dp(3), 0, 0);
        title.addView(when);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView live = widgets.nowActionBadge(entry::performClick);
        live.setVisibility(View.GONE);
        LinearLayout.LayoutParams liveSize = new LinearLayout.LayoutParams(-2, -2);
        liveSize.setMarginStart(views.dp(6));
        header.addView(live, liveSize);
        View arrow = views.chevron();
        header.addView(arrow);
        visibilityBadges.add(new AstronomyTab.VisibilityBadge(event.visibleStart.getEpochSecond(),
                event.visibleEnd.getEpochSecond(), live, arrow));
        entry.addView(header);

        ZonedDateTime start = event.visibleStart.atZone(zone);
        ZonedDateTime end = event.visibleEnd.atZone(zone);
        String range = start.format(formats().clockFormat()) + " – " + end.format(formats().clockFormat());
        if (!start.toLocalDate().equals(end.toLocalDate())) range += activity.getString(R.string.next_day_suffix);
        TextView visible = views.label(activity.getString(R.string.estimated_visible_range, range), 14, views.ink);
        visible.setPadding(0, views.dp(7), 0, 0);
        entry.addView(visible);
        long minutes = Math.max(1, Math.round(Duration.between(event.visibleStart, event.visibleEnd).getSeconds() / 60.0));
        String duration = formats().durationText(minutes);
        TextView details = views.label(activity.getString(R.string.estimated_duration, duration), 13, views.muted);
        details.setPadding(0, views.dp(3), 0, 0);
        entry.addView(details);
        ArrayList<String> localDetails = new ArrayList<>();
        localDetails.add(transitName + " · " + when.getText());
        localDetails.add(visible.getText().toString());
        localDetails.add(details.getText().toString());
        views.cardAction(entry, () -> {
            try {
                activity.startActivity(visibilityMapIntent(event.catalogDate(), eventUrl, localDetails)
                        .putExtra("visibilityKind", "transit").putExtra("transitName", transitName)
                        .putExtra("catalogUrl", event.catalogUrl())
                        .putExtra("visibilitySamples", event.visibilitySamples().toString()));
            } catch (org.json.JSONException error) {
                widgets.openAstronomyPage(eventUrl);
            }
        });
        views.addSpaced(parent, entry, 7);
    }

    private ZoneId astronomyEventZone() {
        if (data != null) return ZoneId.of(data.optString("timezone", "UTC"));
        return astronomyNotificationZone == null ? ZoneId.systemDefault() : astronomyNotificationZone;
    }
}
