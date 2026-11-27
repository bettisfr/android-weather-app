package it.skynow.app.dashboard;

import android.app.Activity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import it.skynow.app.R;
import it.skynow.app.ui.Formats;
import it.skynow.app.ui.SignalColors;
import it.skynow.app.ui.SkyViews;
import java.util.function.Supplier;

/** View helpers shared by the sections of the Astronomy tab. */
final class AstronomyWidgets {
    private final Activity activity;
    private final SkyViews views;
    private final Supplier<Formats> formats;

    AstronomyWidgets(Activity activity, SkyViews views, Supplier<Formats> formats) {
        this.activity = activity;
        this.views = views;
        this.formats = formats;
    }

    String compassDirection(double azimuth) {
        String[] directions = formats.get().italian()
                ? new String[]{"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO"}
                : new String[]{"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        return directions[(int) Math.round(azimuth / 22.5) % directions.length];
    }

    void addCelestialTitleIcon(LinearLayout heading, View icon) {
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(views.dp(23), views.dp(23));
        iconSize.setMarginEnd(views.dp(7));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(icon, iconSize);
    }

    View astronomyTitleIcon(int drawable) {
        android.widget.ImageView icon = new android.widget.ImageView(activity);
        icon.setImageResource(drawable);
        return icon;
    }

    LinearLayout astronomyTitle(View icon, String name) {
        LinearLayout heading = views.row();
        addCelestialTitleIcon(heading, icon);
        heading.addView(views.label(name, 16, views.ink), new LinearLayout.LayoutParams(0, -2, 1));
        return heading;
    }

    TextView nowActionBadge(Runnable open) {
        TextView badge = views.observingBadge(activity.getString(R.string.sky_track_now) + "  ›");
        badge.setContentDescription(activity.getString(R.string.astronomy_open_now_details));
        views.cardAction(badge, open);
        return badge;
    }

    void setMagnitudeBadge(TextView badge, double magnitude, int fill) {
        String value = formats.get().number(magnitude, "%+.1f");
        badge.setText(activity.getString(R.string.astronomy_magnitude, value));
        badge.setContentDescription(activity.getString(R.string.astronomy_magnitude_description, value));
        badge.setBackground(views.background(SignalColors.tint(fill), 8));
    }

    static int magnitudeColor(double magnitude) {
        if (magnitude <= -4) return SignalColors.RED;
        if (magnitude <= -3) return SignalColors.ORANGE;
        if (magnitude <= -2) return SignalColors.YELLOW;
        if (magnitude <= -1) return SignalColors.BLUE;
        return SignalColors.GRAY;
    }

    void openAstronomyPage(String url) {
        try {
            activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException error) {
            Toast.makeText(activity, activity.getString(R.string.no_browser), Toast.LENGTH_SHORT).show();
        }
    }

    LinearLayout astronomyAccordion(int title, boolean expanded,
            java.util.function.Consumer<Boolean> saveExpanded, java.util.function.Consumer<LinearLayout> populate) {
        LinearLayout card = views.card();
        LinearLayout content = views.column();
        content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        Button toggle = views.accordionButton(activity.getString(title));
        views.updateAccordion(toggle, expanded);
        toggle.setOnClickListener(v -> {
            boolean open = content.getVisibility() != View.VISIBLE;
            saveExpanded.accept(open);
            content.setVisibility(open ? View.VISIBLE : View.GONE);
            views.updateAccordion(toggle, open);
            if (open && content.getChildCount() == 0) populate.accept(content);
        });
        card.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        card.addView(content);
        if (expanded) populate.accept(content);
        return card;
    }

    String issBearing(double azimuth) {
        return compassDirection(azimuth) + " " + (Math.round(azimuth) % 360) + "°";
    }

    String skyCoordinatesText(double azimuth, double elevation) {
        return activity.getString(R.string.sky_coordinates, Math.floorMod(Math.round(azimuth), 360),
                compassDirection(azimuth), Math.round(elevation));
    }
}
