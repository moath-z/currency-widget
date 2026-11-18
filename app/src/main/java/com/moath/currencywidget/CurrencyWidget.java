package com.moath.currencywidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.format.DateUtils;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Home-screen currency converter with its own keypad.
 * Everything happens inside the widget; the app never opens.
 *
 * To add a currency, add its 3-letter code to CURRENCIES.
 */
public class CurrencyWidget extends AppWidgetProvider {

    static final String[] CURRENCIES = {"SAR", "GBP", "EUR"};

    private static final String ACTION_KEY = "com.moath.currencywidget.KEY";
    private static final String EXTRA_KEY = "key";
    private static final String PREFS = "currency_widget";
    private static final long STALE_MS = 6 * 60 * 60 * 1000L; // refresh rates after 6 hours
    private static final int MAX_DIGITS = 12;

    // Keypad view id -> key name
    private static final int[] KEY_IDS = {
            R.id.k0, R.id.k1, R.id.k2, R.id.k3, R.id.k4, R.id.k5, R.id.k6, R.id.k7, R.id.k8, R.id.k9,
            R.id.k00, R.id.kdot, R.id.kdel, R.id.kclr, R.id.krefresh
    };
    private static final String[] KEY_NAMES = {
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
            "00", ".", "del", "clr", "refresh"
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) render(ctx, mgr, id);
        refreshIfNeeded(ctx, false);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        if (!ACTION_KEY.equals(intent.getAction())) return;

        int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        String key = intent.getStringExtra(EXTRA_KEY);
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || key == null) return;

        handleKey(ctx, id, key);
        render(ctx, AppWidgetManager.getInstance(ctx), id);
        refreshIfNeeded(ctx, "refresh".equals(key));
    }

    @Override
    public void onDeleted(Context ctx, int[] ids) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        for (int id : ids) {
            e.remove("amt_" + id);
            e.remove("src_" + id);
        }
        e.apply();
    }

    // ---------------------------------------------------------------- input

    private static void handleKey(Context ctx, int id, String key) {
        SharedPreferences p = prefs(ctx);
        String amt = p.getString("amt_" + id, "");
        int src = p.getInt("src_" + id, 0);

        if (key.startsWith("cur")) {
            src = Integer.parseInt(key.substring(3));
        } else if (key.equals("clr")) {
            amt = "";
        } else if (key.equals("del")) {
            if (!amt.isEmpty()) amt = amt.substring(0, amt.length() - 1);
        } else if (key.equals(".")) {
            if (!amt.contains(".")) amt = (amt.isEmpty() ? "0" : amt) + ".";
        } else if (key.equals("refresh")) {
            // handled by caller
        } else {
            for (char c : key.toCharArray()) amt = appendDigit(amt, c);
        }

        p.edit().putString("amt_" + id, amt).putInt("src_" + id, src).apply();
    }

    private static String appendDigit(String amt, char c) {
        int dot = amt.indexOf('.');
        if (dot >= 0 && amt.length() - dot > 2) return amt;          // max 2 decimals
        if (amt.replace(".", "").length() >= MAX_DIGITS) return amt; // max length
        if (amt.equals("0")) return String.valueOf(c);               // no leading zeros
        return amt + c;
    }

    // ---------------------------------------------------------------- drawing

    static void updateAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, CurrencyWidget.class));
        for (int id : ids) render(ctx, mgr, id);
    }

    private static void render(Context ctx, AppWidgetManager mgr, int id) {
        SharedPreferences p = prefs(ctx);
        String pkg = ctx.getPackageName();
        String amt = p.getString("amt_" + id, "");
        int src = p.getInt("src_" + id, 0);
        if (src < 0 || src >= CURRENCIES.length) src = 0;

        RemoteViews v = new RemoteViews(pkg, R.layout.widget);

        // Currency chips (tap to choose what you're typing in)
        v.removeAllViews(R.id.chips);
        for (int i = 0; i < CURRENCIES.length; i++) {
            RemoteViews chip = new RemoteViews(pkg, R.layout.chip);
            chip.setTextViewText(R.id.chip, CURRENCIES[i]);
            boolean on = i == src;
            chip.setInt(R.id.chip, "setBackgroundResource", on ? R.drawable.chip_on : R.drawable.chip_off);
            chip.setTextColor(R.id.chip, on ? 0xFF10141A : 0xFFB8C2CC);
            chip.setOnClickPendingIntent(R.id.chip, keyIntent(ctx, id, "cur" + i));
            v.addView(R.id.chips, chip);
        }

        // Typed amount
        v.setTextViewText(R.id.amount, formatInput(amt));
        v.setTextViewText(R.id.amount_code, CURRENCIES[src]);

        // Converted amounts
        v.removeAllViews(R.id.results);
        boolean haveRates = p.getLong("rates_time", 0) > 0;
        BigDecimal value = amt.isEmpty() || amt.equals(".") ? BigDecimal.ZERO : new BigDecimal(amt.endsWith(".") ? amt + "0" : amt);
        for (int i = 0; i < CURRENCIES.length; i++) {
            if (i == src) continue;
            RemoteViews row = new RemoteViews(pkg, R.layout.result_row);
            String text = "—";
            if (haveRates) {
                double from = rate(p, CURRENCIES[src]);
                double to = rate(p, CURRENCIES[i]);
                if (from > 0 && to > 0) text = money().format(value.doubleValue() / from * to);
            }
            row.setTextViewText(R.id.result_value, text);
            row.setTextViewText(R.id.result_code, CURRENCIES[i]);
            v.addView(R.id.results, row);
        }

        // Status line
        String status;
        if (System.currentTimeMillis() - p.getLong("busy_time", 0) < 30000) {
            status = "Updating rates…";
        } else if (!haveRates) {
            status = p.getBoolean("rates_error", false)
                    ? "Failed (" + p.getString("rates_error_msg", "?") + ") · tap ↻"
                    : "Tap ↻ to load rates";
        } else {
            long t = p.getLong("rates_time", 0);
            String when = DateUtils.isToday(t)
                    ? new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(t))
                    : new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(t));
            status = "Rates updated " + when + (p.getBoolean("rates_error", false) ? " · update failed (" + p.getString("rates_error_msg", "?") + ")" : "");
        }
        v.setTextViewText(R.id.status, status);

        // Keypad
        for (int k = 0; k < KEY_IDS.length; k++) {
            v.setOnClickPendingIntent(KEY_IDS[k], keyIntent(ctx, id, KEY_NAMES[k]));
        }

        mgr.updateAppWidget(id, v);
    }

    private static String formatInput(String amt) {
        if (amt.isEmpty()) return "0";
        int dot = amt.indexOf('.');
        String whole = dot >= 0 ? amt.substring(0, dot) : amt;
        String frac = dot >= 0 ? amt.substring(dot) : "";
        if (whole.isEmpty()) whole = "0";
        DecimalFormat f = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US));
        return f.format(new BigDecimal(whole)) + frac;
    }

    private static DecimalFormat money() {
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
    }

    private static PendingIntent keyIntent(Context ctx, int id, String key) {
        Intent i = new Intent(ctx, CurrencyWidget.class);
        i.setAction(ACTION_KEY);
        i.setData(Uri.parse("currencywidget://" + id + "/" + key)); // makes each intent unique
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        i.putExtra(EXTRA_KEY, key);
        return PendingIntent.getBroadcast(ctx, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---------------------------------------------------------------- rates

    private static double rate(SharedPreferences p, String code) {
        try {
            return Double.parseDouble(p.getString("rate_" + code, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void refreshIfNeeded(Context ctx, boolean force) {
        SharedPreferences p = prefs(ctx);
        long age = System.currentTimeMillis() - p.getLong("rates_time", 0);
        if (!force && age < STALE_MS) return;

        final Context app = ctx.getApplicationContext();
        prefs(app).edit().putLong("busy_time", System.currentTimeMillis()).commit();
        updateAll(app); // show "Updating rates…" so a tap on ↻ gives feedback

        final PendingResult pending = goAsync();
        new Thread(() -> {
            boolean ok = false;
            try {
                fetchRates(app);
                ok = true;
            } catch (Throwable ignored) {
            } finally {
                prefs(app).edit().putLong("busy_time", 0).putBoolean("rates_error", !ok).commit();
                updateAll(app);
                pending.finish();
            }
        }).start();
    }

    /** Tries each rate source in turn; throws with a short reason if all fail. */
    private static void fetchRates(Context ctx) throws Exception {
        StringBuilder errors = new StringBuilder();
        for (int s = 0; s < SOURCES.length; s++) {
            try {
                JSONObject json = new JSONObject(download(SOURCES[s]));
                // Source 0: {"rates":{"SAR":..}}   Sources 1-2: {"eur":{"sar":..}}
                JSONObject rates = json.has("rates") ? json.getJSONObject("rates") : json.getJSONObject("eur");
                boolean lower = !json.has("rates");

                SharedPreferences.Editor e = prefs(ctx).edit();
                for (String code : CURRENCIES) {
                    String k = lower ? code.toLowerCase(Locale.US) : code;
                    double r = code.equals("EUR") ? 1.0 : rates.getDouble(k);
                    e.putString("rate_" + code, Double.toString(r));
                }
                e.putLong("rates_time", System.currentTimeMillis());
                e.putString("rates_error_msg", "");
                e.commit();
                return;
            } catch (Exception ex) {
                errors.append(s + 1).append(":").append(shortReason(ex)).append(" ");
            }
        }
        prefs(ctx).edit().putString("rates_error_msg", errors.toString().trim()).commit();
        throw new Exception(errors.toString());
    }

    private static final String[] SOURCES = {
            "https://open.er-api.com/v6/latest/EUR",
            "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/eur.json",
            "https://latest.currency-api.pages.dev/v1/currencies/eur.json",
    };

    private static String download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) CurrencyWidget/1.1");
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private static String shortReason(Exception e) {
        String n = e.getClass().getSimpleName();
        if (n.contains("UnknownHost")) return "no internet/DNS";
        if (n.contains("Timeout")) return "timeout";
        if (n.contains("SSL")) return "SSL";
        if (n.contains("JSON")) return "bad data";
        String m = e.getMessage();
        return m != null && m.length() < 20 ? m : n;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
