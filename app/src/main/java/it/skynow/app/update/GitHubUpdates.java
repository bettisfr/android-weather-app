package it.skynow.app.update;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import java.io.FileNotFoundException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import it.skynow.app.R;
import it.skynow.app.net.HttpText;

public final class GitHubUpdates {
    private static final String REPOSITORY = "https://github.com/bettisfr/skyhere";
    private static final String LATEST = "https://api.github.com/repos/bettisfr/skyhere/releases/latest";
    private static final String APK = "SkyHere.apk";
    private static final String CHANNEL = "app_updates";
    private static final Pattern VERSION = Pattern.compile("[0-9]{1,9}(\\.[0-9]{1,9}){1,2}");
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private GitHubUpdates() {}

    public static void checkAutomatically(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(CHANNEL, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = prefs.getLong("last_attempt", 0);
        if (now >= last && now - last < TimeUnit.DAYS.toMillis(1)) return;
        prefs.edit().putLong("last_attempt", now).apply();
        IO.execute(() -> {
            try {
                String tag = availableUpdate(app);
                if (tag != null && !tag.equals(prefs.getString("notified", "")) && notifyUpdate(app, tag)) {
                    prefs.edit().putString("notified", tag).apply();
                }
            } catch (Exception error) {
                android.util.Log.w("SkyHere", "GitHub release check failed", error);
            }
        });
    }

    public static void checkManually(Activity activity) {
        Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show();
        IO.execute(() -> {
            try {
                String tag = availableUpdate(activity);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (tag == null) {
                        Toast.makeText(activity, R.string.update_up_to_date, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    new AlertDialog.Builder(activity)
                            .setTitle(R.string.update_available)
                            .setMessage(activity.getString(R.string.update_version, tag))
                            .setPositiveButton(R.string.update_download, (dialog, which) -> {
                                try {
                                    activity.startActivity(download(tag));
                                } catch (ActivityNotFoundException error) {
                                    Toast.makeText(activity, R.string.no_browser, Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null).show();
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private static String availableUpdate(Context context) throws Exception {
        JSONObject release;
        try {
            release = new JSONObject(HttpText.read(LATEST));
        } catch (FileNotFoundException noRelease) {
            return null;
        }
        String tag = release.optString("tag_name");
        String installed = context.getString(R.string.sky_version_name).split("\\+", 2)[0];
        if (!tag.startsWith("v") || !VERSION.matcher(tag.substring(1)).matches()
                || !VERSION.matcher(installed).matches() || !newer(tag.substring(1), installed)) return null;
        JSONArray assets = release.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (APK.equals(asset.optString("name")) && "uploaded".equals(asset.optString("state"))) return tag;
        }
        return null;
    }

    private static boolean newer(String candidate, String installed) {
        String[] release = candidate.split("\\.");
        String[] local = installed.split("\\.");
        for (int i = 0; i < Math.max(release.length, local.length); i++) {
            int remotePart = i < release.length ? Integer.parseInt(release[i]) : 0;
            int localPart = i < local.length ? Integer.parseInt(local[i]) : 0;
            if (remotePart != localPart) return remotePart > localPart;
        }
        return false;
    }

    private static Intent download(String tag) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY + "/releases/download/" + tag + "/" + APK));
    }

    private static boolean notifyUpdate(Context context, String tag) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) return false;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, context.getString(R.string.update_channel),
                NotificationManager.IMPORTANCE_DEFAULT));
        if (manager.getNotificationChannel(CHANNEL).getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        PendingIntent open = PendingIntent.getActivity(context, 0, download(tag), PendingIntent.FLAG_IMMUTABLE);
        manager.notify(CHANNEL, 1, new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_weather_foreground)
                .setContentTitle(context.getString(R.string.update_available))
                .setContentText(context.getString(R.string.update_version, tag))
                .setContentIntent(open).setAutoCancel(true).build());
        return true;
    }
}
