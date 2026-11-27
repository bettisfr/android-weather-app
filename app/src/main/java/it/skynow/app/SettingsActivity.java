package it.skynow.app;

import it.skynow.app.dashboard.EarthquakeTab;
import it.skynow.app.ui.Formats;
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
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import it.skynow.app.update.GitHubUpdates;
import it.skynow.app.astronomy.AuroraForecast;
import it.skynow.app.astronomy.OrbitalApsides;
import it.skynow.app.maps.SatelliteImagery;
import it.skynow.app.notifications.IssAlerts;
import it.skynow.app.notifications.EarthquakeAlerts;
import it.skynow.app.seismic.EarthquakeFeed;

public final class SettingsActivity extends Activity {
    static final String[] TEMPERATURE_UNITS = {"°C", "°F", "K"};
    static int temperatureUnit(SharedPreferences preferences) {
        int unit = preferences.getInt("temperatureUnit", preferences.getBoolean("fahrenheit", false) ? 1 : 0);
        return Math.max(0, Math.min(unit, TEMPERATURE_UNITS.length - 1));
    }

    static final String PREFERENCES_FILE = "preferencesFile";
    private SharedPreferences preferences;
    private ScrollView overview;
    private int overviewOffset;
    private boolean inSubpage;
    private TextView exactAlarmStatus;
    private static final int ISS_PERMISSION = 1;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(SkyTheme.withPreference(base));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(getIntent().getStringExtra(PREFERENCES_FILE), MODE_PRIVATE);
        setResult(RESULT_OK);
        SkyTheme.applySystemBars(this);

        showOverview();
    }

    private void showOverview() {
        inSubpage = false;
        exactAlarmStatus = null;
        LinearLayout screen = stack();
        screen.setBackgroundColor(getColor(R.color.sky_background));
        screen.addView(header(R.string.settings));
        ScrollView scroller = new ScrollView(this);
        overview = scroller;
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
        choice(display, R.string.app_language, new String[]{
                getString(R.string.theme_system), getString(R.string.language_italian), getString(R.string.language_english)
        }, () -> SkyLanguage.preference(this), index -> {
            SkyLanguage.savePreference(this, index);
            recreate();
        });
        choice(display, R.string.time_format, new String[]{getString(R.string.clock_12), getString(R.string.clock_24)},
                () -> preferences.getBoolean("clock24", true) ? 1 : 0,
                index -> preferences.edit().putBoolean("clock24", index == 1).apply());

        LinearLayout units = section(content, R.string.settings_measurement_units);
        choice(units, R.string.temperature, new String[]{getString(R.string.celsius), getString(R.string.fahrenheit), getString(R.string.kelvin)},
                () -> temperatureUnit(preferences),
                index -> preferences.edit().putInt("temperatureUnit", index).apply());
        choice(units, R.string.wind_speed, Formats.WIND_UNITS,
                () -> preferences.getInt("windUnit", 0), index -> preferences.edit().putInt("windUnit", index).apply());
        choice(units, R.string.pressure, Formats.PRESSURE_UNITS,
                () -> preferences.getInt("pressureUnit", 0), index -> preferences.edit().putInt("pressureUnit", index).apply());

        LinearLayout earthquakes = section(content, R.string.tab_earthquakes);
        subpage(earthquakes, R.string.earthquake_radius, getString(R.string.earthquake_radius_value,
                EarthquakeFeed.alertRadius(preferences)), this::addEarthquakeRadius);
        choice(earthquakes, R.string.earthquake_minimum_magnitude, EarthquakeTab.magnitudeOptions(this),
                () -> EarthquakeTab.magnitudeIndex(preferences.getInt("earthquakeMinimumMagnitude", 0)),
                index -> preferences.edit()
                        .putInt("earthquakeMinimumMagnitude", EarthquakeTab.magnitudeThreshold(index)).apply());
        LinearLayout notifications = section(content, R.string.settings_notifications);
        String[] options = {getString(R.string.iss_alert_off), getString(R.string.iss_alert_at_start),
                getString(R.string.iss_alert_lead, 5), getString(R.string.iss_alert_lead, 10),
                getString(R.string.iss_alert_lead, 15)};
        subpage(notifications, R.string.iss_alerts, options[IssAlerts.option(this)], panel -> {
            addExactAlarmSetting(panel);
            addChoices(panel, options, () -> IssAlerts.option(this), index -> {
                IssAlerts.setOption(this, index);
                if (index != 0) allowAstronomyNotifications();
            });
        });
        String[] eventOptions = {options[0], getString(R.string.astronomy_alert_at_start), getString(R.string.iss_alert_lead, 15),
                getString(R.string.astronomy_alert_hour_before), getString(R.string.astronomy_alert_day_before)};
        for (IssAlerts.Kind kind : IssAlerts.Kind.values()) {
            subpage(notifications, kind.title, eventOptions[IssAlerts.option(this, kind)], panel -> {
                addExactAlarmSetting(panel);
                addChoices(panel, eventOptions, () -> IssAlerts.option(this, kind), index -> {
                    IssAlerts.setOption(this, kind, index);
                    if (index != 0) allowAstronomyNotifications();
                });
            });
        }
        choice(notifications, R.string.earthquake_alerts,
                new String[]{getString(R.string.earthquake_alert_off), getString(R.string.earthquake_alert_on)},
                () -> EarthquakeAlerts.enabled(this) ? 1 : 0, index -> {
                    EarthquakeAlerts.setEnabled(this, index != 0);
                    if (index != 0) allowAstronomyNotifications();
                });
        LinearLayout about = section(content, R.string.app_name);
        subpage(about, R.string.settings_about, null, this::showAbout);
        subpage(about, R.string.credits, null, this::addCredits);
        setContentView(screen);
        scroller.post(() -> scroller.scrollTo(0, overviewOffset));
    }

    private void back() {
        if (inSubpage) showOverview();
        else finish();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        back();
    }

    private void addExactAlarmSetting(LinearLayout panel) {
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        LinearLayout exact = settingRow("");
        exactAlarmStatus = (TextView) exact.getChildAt(0);
        updateExactAlarmStatus();
        exact.setOnClickListener(view -> startActivity(new Intent(
                android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:" + getPackageName()))));
        addRow(panel, exact);
    }

    @Override protected void onResume() {
        super.onResume();
        updateExactAlarmStatus();
    }

    private void updateExactAlarmStatus() {
        if (exactAlarmStatus == null) return;
        exactAlarmStatus.setText(IssAlerts.canScheduleExactly(this)
                ? R.string.astronomy_alert_exact_allowed : R.string.astronomy_alert_exact_enable);
    }

    private void allowAstronomyNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, ISS_PERMISSION);
        }
    }

    private LinearLayout header(int heading) {
        LinearLayout header = row();
        header.setBackgroundColor(getColor(R.color.sky_card));
        TextView back = text("‹", 28, R.color.sky_text);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(getString(R.string.settings_back));
        back.setOnClickListener(view -> back());
        ripple(back);
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout titles = stack();
        titles.setPaddingRelative(dp(4), dp(12), dp(16), dp(12));
        TextView title = text(getString(heading), 24, R.color.sky_text);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titles.addView(title);
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
        subpage(section, title, values[selected.getAsInt()], panel -> addChoices(panel, values, selected, save));
    }

    private void subpage(LinearLayout section, int title, String value, Consumer<LinearLayout> populate) {
        LinearLayout row = settingRow(getString(title));
        if (value != null) row.addView(text(value, 14, R.color.sky_muted));
        TextView arrow = text("›", 22, R.color.sky_accent);
        arrow.setPaddingRelative(dp(8), 0, 0, 0);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow);
        row.setOnClickListener(view -> {
            overviewOffset = overview.getScrollY();
            inSubpage = true;
            LinearLayout screen = stack();
            screen.setBackgroundColor(getColor(R.color.sky_background));
            screen.addView(header(title));
            ScrollView scroller = new ScrollView(this);
            screen.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout panel = stack();
            panel.setPadding(dp(16), dp(8), dp(16), dp(20));
            scroller.addView(panel);
            populate.accept(panel);
            setContentView(screen);
        });
        addRow(section, row);
    }

    private void addChoices(LinearLayout panel, String[] values, IntSupplier selected, IntConsumer save) {
        RadioGroup options = new RadioGroup(this);
        options.setOrientation(RadioGroup.VERTICAL);
        for (String value : values) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            option.setText(value);
            option.setTextColor(getColor(R.color.sky_text));
            option.setMinHeight(dp(48));
            options.addView(option);
        }
        options.check(options.getChildAt(selected.getAsInt()).getId());
        options.setOnCheckedChangeListener((group, checkedId) -> {
            int index = group.indexOfChild(group.findViewById(checkedId));
            if (index >= 0 && index != selected.getAsInt()) save.accept(index);
        });
        panel.addView(options);
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
        slider.setMax(EarthquakeFeed.MAX_RADIUS / EarthquakeFeed.RADIUS_STEP_KM);
        int radius = EarthquakeFeed.alertRadius(preferences);
        slider.setProgress(radius / EarthquakeFeed.RADIUS_STEP_KM);
        slider.setKeyProgressIncrement(1);
        value.setText(getString(R.string.earthquake_radius_value, radius));
        slider.setPaddingRelative(dp(12), 0, dp(12), 0);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int progress, boolean fromUser) {
                int kilometers = progress * EarthquakeFeed.RADIUS_STEP_KM;
                value.setText(getString(R.string.earthquake_radius_value, kilometers));
                if (fromUser) preferences.edit().putInt("earthquakeRadius", kilometers).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar view) {}
            @Override public void onStopTrackingTouch(SeekBar view) {}
        });
        section.addView(slider, new LinearLayout.LayoutParams(-1, dp(42)));
    }

    private void showAbout(LinearLayout panel) {
        LinearLayout card = stack();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPaddingRelative(dp(16), dp(24), dp(16), dp(12));
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(getColor(R.color.sky_card));
        surface.setCornerRadius(dp(18));
        surface.setStroke(dp(1), getColor(R.color.sky_card_outline));
        card.setBackground(surface);
        panel.addView(card, new LinearLayout.LayoutParams(-1, -2));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_weather);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(logo, new LinearLayout.LayoutParams(dp(76), dp(76)));
        TextView name = text(getString(R.string.app_name), 26, R.color.sky_text);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(10), 0, 0);
        card.addView(name);

        Locale locale = Locale.forLanguageTag(getString(R.string.app_language_tag));
        String date = LocalDate.parse(getString(R.string.sky_version_date))
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale));
        TextView version = text(getString(R.string.app_version, getString(R.string.sky_version_name), date),
                15, R.color.sky_muted);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, dp(4), 0, 0);
        card.addView(version);
        TextView author = text(getString(R.string.app_author, "Francesco Betti Sorbelli"), 15, R.color.sky_text);
        author.setGravity(Gravity.CENTER);
        author.setPadding(0, dp(12), 0, dp(16));
        card.addView(author);
        if (getResources().getBoolean(R.bool.check_for_release_updates)) {
            LinearLayout updates = settingRow(getString(R.string.update_check));
            updates.setOnClickListener(view -> GitHubUpdates.checkManually(this));
            addRow(card, updates);
        }
        addCredit(card, R.string.app_github, "https://github.com/bettisfr/skyhere");
        addCredit(card, R.string.app_feedback, "https://github.com/bettisfr/skyhere/issues/new");
    }

    private void addCredits(LinearLayout links) {
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
