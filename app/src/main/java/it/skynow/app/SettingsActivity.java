package it.skynow.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import it.skynow.app.astronomy.AuroraForecast;
import it.skynow.app.astronomy.OrbitalApsides;
import it.skynow.app.maps.SatelliteImagery;
import it.skynow.app.seismic.EarthquakeFeed;

public final class SettingsActivity extends Activity {
    static final String[] TEMPERATURE_UNITS = {"°C", "°F", "K"};

    static int temperatureUnit(SharedPreferences preferences) {
        int unit = preferences.getInt("temperatureUnit", preferences.getBoolean("fahrenheit", false) ? 1 : 0);
        return Math.max(0, Math.min(unit, TEMPERATURE_UNITS.length - 1));
    }

    static final String PREFERENCES_FILE = "preferencesFile";
    private SharedPreferences preferences;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(SkyTheme.withPreference(base));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(getIntent().getStringExtra(PREFERENCES_FILE), MODE_PRIVATE);
        setResult(RESULT_OK);
        SkyTheme.applySystemBars(this);

        LinearLayout screen = stack();
        screen.setBackgroundColor(getColor(R.color.sky_background));
        screen.addView(header());
        ScrollView scroller = new ScrollView(this);
        screen.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = stack();
        content.setPadding(dp(16), dp(4), dp(16), dp(20));
        scroller.addView(content);

        LinearLayout display = section(content, R.string.settings_display);
        choice(display, R.string.appearance_theme, new String[]{
                getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark)
        }, () -> SkyTheme.preference(this), index -> {
            SkyTheme.savePreference(this, index);
            recreate();
        });
        choice(display, R.string.time_format, new String[]{getString(R.string.clock_12), getString(R.string.clock_24)},
                () -> preferences.getBoolean("clock24", true) ? 1 : 0,
                index -> preferences.edit().putBoolean("clock24", index == 1).apply());

        LinearLayout units = section(content, R.string.settings_measurement_units);
        choice(units, R.string.temperature, new String[]{getString(R.string.celsius), getString(R.string.fahrenheit), getString(R.string.kelvin)},
                () -> temperatureUnit(preferences),
                index -> preferences.edit().putInt("temperatureUnit", index).apply());
        choice(units, R.string.wind_speed, MainActivity.WIND_UNITS,
                () -> preferences.getInt("windUnit", 0), index -> preferences.edit().putInt("windUnit", index).apply());
        choice(units, R.string.pressure, MainActivity.PRESSURE_UNITS,
                () -> preferences.getInt("pressureUnit", 0), index -> preferences.edit().putInt("pressureUnit", index).apply());

        LinearLayout earthquakes = section(content, R.string.tab_earthquakes);
        addEarthquakeRadius(earthquakes);
        choice(earthquakes, R.string.earthquake_minimum_magnitude, MainActivity.earthquakeMagnitudeOptions(this),
                () -> MainActivity.earthquakeMagnitudeIndex(preferences.getInt("earthquakeMinimumMagnitude", 0)),
                index -> preferences.edit()
                        .putInt("earthquakeMinimumMagnitude", MainActivity.EARTHQUAKE_MAGNITUDE_THRESHOLDS[index]).apply());
        addCredits(content, scroller);
        setContentView(screen);
    }

    private LinearLayout header() {
        LinearLayout header = row();
        header.setBackgroundColor(getColor(R.color.sky_card));
        TextView back = text("‹", 28, R.color.sky_text);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(getString(R.string.settings_back));
        back.setOnClickListener(view -> finish());
        ripple(back);
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout titles = stack();
        titles.setPaddingRelative(dp(4), dp(12), dp(16), dp(12));
        TextView title = text(getString(R.string.settings), 24, R.color.sky_text);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titles.addView(title);
        Locale locale = Locale.forLanguageTag(getString(R.string.app_language_tag));
        String date = LocalDate.parse(getString(R.string.sky_version_date))
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale));
        TextView version = text(getString(R.string.app_version, getString(R.string.app_name),
                getString(R.string.sky_version_name), date), 12, R.color.sky_muted);
        version.setPadding(0, dp(3), 0, 0);
        titles.addView(version);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        return header;
    }

    private LinearLayout section(LinearLayout content, int title) {
        TextView heading = text(getString(title), 14, R.color.sky_accent);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setPaddingRelative(dp(3), dp(15), 0, dp(6));
        content.addView(heading);
        LinearLayout section = stack();
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(14));
        fill.setColor(getColor(R.color.sky_card));
        fill.setStroke(dp(1), getColor(R.color.sky_card_outline));
        section.setBackground(fill);
        section.setClipToOutline(true);
        content.addView(section, new LinearLayout.LayoutParams(-1, -2));
        return section;
    }

    private void choice(LinearLayout section, int title, String[] values, IntSupplier selected, IntConsumer save) {
        LinearLayout row = settingRow(getString(title));
        TextView value = text(values[selected.getAsInt()], 14, R.color.sky_muted);
        row.addView(value);
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_expand);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams arrowSize = new LinearLayout.LayoutParams(dp(20), dp(20));
        arrowSize.setMarginStart(dp(8));
        row.addView(arrow, arrowSize);
        row.setOnClickListener(view -> {
            PopupMenu popup = new PopupMenu(this, value, Gravity.END);
            for (int index = 0; index < values.length; index++) popup.getMenu().add(0, index, index, values[index]);
            popup.getMenu().setGroupCheckable(0, true, true);
            popup.getMenu().getItem(selected.getAsInt()).setChecked(true);
            popup.setOnMenuItemClickListener(item -> {
                int index = item.getItemId();
                if (index != selected.getAsInt()) {
                    save.accept(index);
                    value.setText(values[index]);
                }
                return true;
            });
            popup.show();
        });
        addRow(section, row);
    }

    private void addEarthquakeRadius(LinearLayout section) {
        LinearLayout row = settingRow(getString(R.string.earthquake_radius));
        TextView label = (TextView) row.getChildAt(0);
        TextView value = text("", 14, R.color.sky_muted);
        row.addView(value);
        section.addView(row, new LinearLayout.LayoutParams(-1, -2));
        SeekBar slider = new SeekBar(this);
        slider.setId(View.generateViewId());
        label.setLabelFor(slider.getId());
        slider.setMin(1);
        slider.setMax(EarthquakeFeed.MAX_RADIUS);
        int radius = preferences.getInt("earthquakeRadius", EarthquakeFeed.DEFAULT_RADIUS);
        slider.setProgress(radius);
        slider.setKeyProgressIncrement(1);
        value.setText(getString(R.string.earthquake_radius_value, radius));
        slider.setPaddingRelative(dp(12), 0, dp(12), 0);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int progress, boolean fromUser) {
                value.setText(getString(R.string.earthquake_radius_value, progress));
                if (fromUser) preferences.edit().putInt("earthquakeRadius", progress).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar view) {}
            @Override public void onStopTrackingTouch(SeekBar view) {}
        });
        section.addView(slider, new LinearLayout.LayoutParams(-1, dp(42)));
    }

    private void addCredits(LinearLayout content, ScrollView scroller) {
        LinearLayout section = section(content, R.string.settings_about);
        LinearLayout toggle = settingRow(getString(R.string.credits));
        TextView arrow = text("⌄", 20, R.color.sky_accent);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        toggle.addView(arrow);
        addRow(section, toggle);
        LinearLayout links = stack();
        links.setVisibility(View.GONE);
        section.addView(links);
        toggle.setOnClickListener(view -> {
            boolean open = links.getVisibility() == View.VISIBLE;
            links.setVisibility(open ? View.GONE : View.VISIBLE);
            arrow.setRotation(open ? 0 : 180);
            if (!open) scroller.post(() -> scroller.smoothScrollTo(0, section.getTop()));
        });
        addCredit(links, R.string.weather_data, "https://open-meteo.com/");
        addCredit(links, R.string.warning_credit, "https://meteoalarm.org/");
        addCredit(links, R.string.radar_credit, "https://mappe.protezionecivile.gov.it/it/mappe-e-dashboard-rischi/piattaforma-radar/");
        addCredit(links, R.string.aurora_credit, AuroraForecast.MAP);
        addCredit(links, R.string.air_quality_credit, "https://open-meteo.com/en/docs/air-quality-api");
        addCredit(links, R.string.air_quality_map_credit, "https://open-meteo.com/en/docs/air-quality-api");
        addCredit(links, R.string.air_quality_eea_credit, "https://airindex.eea.europa.eu/AQI/");
        addCredit(links, R.string.satellite_credit, SatelliteImagery.EUMETSAT);
        addCredit(links, R.string.satellite_mosaic_credit, SatelliteImagery.SOURCE);
        addCredit(links, R.string.earthquake_credit, EarthquakeFeed.SOURCE);
        addCredit(links, R.string.iss_credit, "https://www.amsat.org/keplerian-elements/");
        addCredit(links, R.string.eclipse_data, "https://opale.imcce.fr/terms/");
        addCredit(links, R.string.solar_eclipse_credit, "https://eclipse.gsfc.nasa.gov/SEcat5/beselm.html");
        addCredit(links, R.string.transit_credit, "https://eclipse.gsfc.nasa.gov/transit/transit.html");
        addCredit(links, R.string.seasons_credit, "https://aa.usno.navy.mil/data/Earth_Seasons");
        addCredit(links, R.string.orbital_apsides_credit, OrbitalApsides.SOURCE);
    }

    private void addCredit(LinearLayout section, int title, String url) {
        LinearLayout row = settingRow(getString(title));
        row.setOnClickListener(view -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException error) {
                Toast.makeText(this, getString(R.string.no_browser), Toast.LENGTH_SHORT).show();
            }
        });
        addRow(section, row);
    }

    private LinearLayout settingRow(String title) {
        LinearLayout row = row();
        row.setMinimumHeight(dp(48));
        row.setPaddingRelative(dp(12), dp(6), dp(12), dp(6));
        row.addView(text(title, 15, R.color.sky_text), new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private void addRow(LinearLayout section, LinearLayout row) {
        if (section.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(getColor(R.color.sky_card_outline));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, dp(1));
            size.setMarginStart(dp(12));
            size.setMarginEnd(dp(12));
            section.addView(divider, size);
        }
        ripple(row);
        section.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void ripple(View view) {
        view.setForeground(new RippleDrawable(ColorStateList.valueOf(getColor(R.color.sky_ripple)), null,
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE)));
    }

    private LinearLayout stack() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView text(String label, int size, int color) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(getColor(color));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
