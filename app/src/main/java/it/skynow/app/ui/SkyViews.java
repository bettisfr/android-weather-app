package it.skynow.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.RotateDrawable;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import it.skynow.app.R;
import it.skynow.app.weather.WeatherCodes;
import it.skynow.app.weather.views.WeatherIconSpan;

/** The app's view kit: theme colours and factories for the cards, labels and buttons built in code. */
public final class SkyViews {
    private final Context context;
    public final int ink;
    public final int muted;
    public final int accent;
    public final int surfaceColor;

    /** {@code context} must already carry the app's theme and language. */
    public SkyViews(Context context) {
        this.context = context;
        ink = context.getColor(R.color.sky_text);
        muted = context.getColor(R.color.sky_muted);
        accent = context.getColor(R.color.sky_accent);
        surfaceColor = context.getColor(R.color.sky_background);
    }

    // Metrics

    public int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public boolean largeText() {
        Configuration configuration = context.getResources().getConfiguration();
        return configuration.fontScale >= 1.25f || configuration.screenWidthDp < 330;
    }

    public boolean stackedCards() {
        Configuration configuration = context.getResources().getConfiguration();
        return configuration.fontScale >= 1.55f || configuration.screenWidthDp < 330;
    }

    public int textWidth(TextView view, String text) {
        return (int) Math.ceil(view.getPaint().measureText(text));
    }

    // Containers

    public LinearLayout column() {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    public LinearLayout row() {
        LinearLayout view = new LinearLayout(context);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    public LinearLayout card() {
        LinearLayout view = column();
        view.setBackground(cardBackground(16));
        view.setPadding(dp(13), dp(13), dp(13), dp(13));
        return view;
    }

    public GradientDrawable cardBackground(int radius) {
        GradientDrawable drawable = background(context.getColor(R.color.sky_card), radius);
        drawable.setStroke(dp(1), context.getColor(R.color.sky_card_outline));
        return drawable;
    }

    public GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    public void addSpaced(LinearLayout parent, View child, int gap) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = dp(gap);
        parent.addView(child, size);
    }

    /** Adds a horizontally scrolling row to {@code parent} and returns the row to fill. */
    public LinearLayout carousel(LinearLayout parent) {
        HorizontalScrollView view = new HorizontalScrollView(context);
        view.setHorizontalScrollBarEnabled(false);
        LinearLayout content = row();
        view.addView(content);
        parent.addView(view);
        return content;
    }

    public LinearLayout loadingStatus(String message) {
        LinearLayout status = row();
        status.setPadding(dp(4), dp(12), dp(4), dp(8));
        ProgressBar spinner = new ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(accent));
        spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        status.addView(spinner, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView text = label(message, 16, muted);
        text.setPadding(dp(10), 0, 0, 0);
        text.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.addView(text);
        return status;
    }

    // Text

    public TextView label(String text, int size, int color) {
        TextView view = new TextView(context);
        view.setTextSize(Math.max(10f, size * 0.95f));
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        view.setTextColor(color);
        setLabelText(view, text);
        return view;
    }

    /** Sets {@code text}, drawing the weather icon glyphs from {@link WeatherCodes#symbol} as images. */
    public void setLabelText(TextView view, String text) {
        SpannableString decorated = null;
        for (int i = 0; i < text.length(); i++) {
            int resource = WeatherCodes.glyphDrawable(text.charAt(i));
            if (resource == 0) continue;
            if (decorated == null) decorated = new SpannableString(text);
            Drawable icon = context.getDrawable(resource);
            int pixels = Math.round(view.getTextSize() * 1.05f);
            icon.setBounds(0, 0, pixels, pixels);
            decorated.setSpan(new WeatherIconSpan(icon), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        view.setText(decorated == null ? text : decorated);
    }

    /** A section heading such as "Seismic activity"; the caller adds it to the page. */
    public TextView sectionTitle(String text) {
        TextView title = label(text, 19, ink);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(0, dp(23), 0, dp(11));
        return title;
    }

    /** The "›" hint at the end of a row that opens a detail view. */
    public TextView chevron() {
        TextView chevron = label("›", 20, accent);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return chevron;
    }

    /** Header of a full-screen detail page: a back control and a title. */
    public LinearLayout detailHeader(String title, int backDescription, Runnable goBack) {
        LinearLayout header = row();
        TextView back = label("‹", 28, ink);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(context.getString(backDescription));
        back.setOnClickListener(v -> goBack.run());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView titleView = label(title, 19, ink);
        titleView.setPaddingRelative(0, dp(7), dp(12), dp(7));
        header.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
        return header;
    }

    // Badges

    public TextView badge(String text) {
        TextView badge = label(text, 13, ink);
        badge.setPaddingRelative(dp(7), dp(3), dp(7), dp(3));
        return badge;
    }

    /** A green badge for something observable right now. */
    public TextView observingBadge(String text) {
        TextView badge = badge(text);
        badge.setTextColor(context.getColor(R.color.sky_observing));
        badge.setBackground(background(SignalColors.tint(SignalColors.GREEN), 8));
        return badge;
    }

    public TextView nowBadge() {
        return observingBadge(context.getString(R.string.sky_track_now));
    }

    // Buttons

    /** Makes a whole card clickable, with a ripple drawn over its content. */
    public void cardAction(View card, Runnable action) {
        card.setForeground(new RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.sky_ripple)),
                null, background(context.getColor(R.color.sky_card), 12)));
        card.setOnClickListener(v -> action.run());
    }

    public Button iconActionButton(int title, int icon, Runnable open) {
        Button button = actionButton(context.getString(title), context.getColor(R.color.sky_action), 24);
        int foreground = context.getColor(R.color.sky_on_action);
        button.setTextColor(foreground);
        button.setCompoundDrawableTintList(ColorStateList.valueOf(foreground));
        button.setCompoundDrawablePadding(dp(7));
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        button.setOnClickListener(view -> open.run());
        return button;
    }

    public Button mapButton(int title, Runnable open) {
        return iconActionButton(title, R.drawable.ic_radar, open);
    }

    public TextView button(String text, Runnable click) {
        TextView view = label(text, 15, accent);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        view.setMinHeight(dp(44));
        view.setOnClickListener(v -> click.run());
        return view;
    }

    public TextView header(String name, Runnable click) {
        TextView header = button(name, click);
        header.setTextSize(20);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.setSingleLine();
        header.setEllipsize(TextUtils.TruncateAt.END);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackground(background(context.getColor(R.color.sky_header), 13));
        header.setPadding(dp(14), dp(9), dp(14), dp(9));
        return header;
    }

    public Button actionButton(String text, int fill, int radius) {
        Button view = new Button(context);
        view.setAllCaps(false);
        view.setText(text);
        view.setTextSize(14);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setTextColor(accent);
        view.setLetterSpacing(0);
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setMinHeight(dp(44));
        view.setPaddingRelative(dp(12), dp(4), dp(12), dp(4));
        view.setStateListAnimator(null);
        view.setBackground(new RippleDrawable(
                ColorStateList.valueOf(context.getColor(R.color.sky_ripple)), background(fill, radius), null));
        return view;
    }

    public Button moreButton() {
        return actionButton(context.getString(R.string.more), surfaceColor, 12);
    }

    public Button accordionButton(String text) {
        Button toggle = actionButton(text, surfaceColor, 12);
        toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toggle.setCompoundDrawablePadding(dp(12));
        updateAccordion(toggle, false);
        return toggle;
    }

    public void updateAccordion(Button toggle, boolean expanded) {
        Drawable arrow = context.getDrawable(R.drawable.ic_expand);
        if (expanded) {
            RotateDrawable rotated = new RotateDrawable();
            rotated.setDrawable(arrow);
            rotated.setFromDegrees(180);
            rotated.setToDegrees(180);
            rotated.setLevel(10000);
            arrow = rotated;
        }
        toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, arrow, null);
        if (Build.VERSION.SDK_INT >= 30) {
            toggle.setStateDescription(context.getString(expanded ? R.string.expanded : R.string.collapsed));
        }
    }
}
