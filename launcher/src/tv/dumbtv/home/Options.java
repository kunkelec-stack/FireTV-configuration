package tv.dumbtv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Every setting is a row: left/right or OK cycles it, and the change is saved at once. */
public final class Options extends Activity {
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final List<Runnable> refreshers = new ArrayList<>();
    private Config cfg;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = Config.get(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(0xFF0C0C0E);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(56), dp(40), dp(56), dp(40));
        scroll.addView(list);

        TextView title = Ui.text(this, 30, Ui.TEXT, Ui.LIGHT);
        title.setText("Settings");
        title.setPadding(dp(16), 0, 0, dp(20));
        list.addView(title);

        for (Config.Choice c : Config.CHOICES) list.addView(choice(c));
        list.addView(gap());
        list.addView(action("Wallpaper manager", () -> {
            try {
                startActivity(new Intent().setClassName("tv.evma.search", "tv.evma.search.Wallpapers"));
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(this, "Install or update the EVMA Search companion to manage wallpapers.", Toast.LENGTH_LONG).show();
            }
        }));
        list.addView(action("Apps on home screen", () -> pick(Catalog.APPS)));
        list.addView(action("App order", () -> orderApps(0)));
        list.addView(action("Inputs in menu", () -> pick(Catalog.INPUTS)));
        list.addView(action("Open Fire TV settings", this::openTvSettings));
        list.addView(action("Reset order, names and hidden tiles", this::reset));
        list.addView(action("Open Amazon's home screen", () -> {
            Guard.amazon(this);
            finish();
        }));

        TextView about = Ui.text(this, 13, Ui.FAINT, Ui.MEDIUM);
        about.setLineSpacing(0, 1.4f);
        about.setPadding(dp(16), dp(28), 0, 0);
        about.setText("Settings file: " + cfg.dir + "/" + Config.FILE
                + "\nWallpaper manager shares photos, Fill/Fit, brightness, dimming and rotation with Search. Once applied, it overrides the legacy background controls above."
                + "\nWeather: Open-Meteo (open-meteo.com), CC BY 4.0, via the optional search companion."
                + "\nNo internet permission. No tracking, no ads, no accounts.");
        list.addView(about);

        setContentView(scroll);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        cfg.refresh();
        refresh();
    }

    private void refresh() {
        for (Runnable r : refreshers) r.run();
    }

    private View choice(Config.Choice c) {
        LinearLayout row = row();
        TextView name = label(c.title);
        TextView value = Ui.text(this, 16, Ui.MUTED, Ui.MEDIUM);
        value.setGravity(Gravity.END);
        row.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(value, new LinearLayout.LayoutParams(WRAP, WRAP));
        row.setOnClickListener(v -> step(c, 1));
        row.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) return step(c, -1);
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) return step(c, 1);
            return false;
        });
        Runnable show = () -> {
            boolean focused = row.isFocused();
            String text = c.name(cfg.get(c));
            if (c == Config.ACCENT) {
                SpannableString s = new SpannableString("●  " + text);
                s.setSpan(new ForegroundColorSpan(cfg.accent()), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                value.setText(focused ? "‹  " : "");
                value.append(s);
                if (focused) value.append("  ›");
            } else {
                value.setText(focused ? "‹  " + text + "  ›" : text);
            }
            value.setTextColor(focused ? Ui.TEXT : Ui.MUTED);
            boolean off = c == Config.DIM && !"image".equals(cfg.get(Config.BACKGROUND));
            row.setAlpha(off ? 0.4f : 1f);
        };
        row.setOnFocusChangeListener((v, has) -> show.run());
        refreshers.add(show);
        refreshers.add(() -> style(row));
        return row;
    }

    private boolean step(Config.Choice c, int by) {
        int i = Arrays.asList(c.values).indexOf(cfg.get(c));
        int n = c.values.length;
        cfg.set(c.key, c.values[((i + by) % n + n) % n]);
        refresh();
        return true;
    }

    private View action(String text, Runnable run) {
        LinearLayout row = row();
        row.addView(label(text), new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView chevron = Ui.text(this, 16, Ui.MUTED, Ui.MEDIUM);
        chevron.setText("›");
        row.addView(chevron);
        row.setOnClickListener(v -> run.run());
        refreshers.add(() -> style(row));
        return row;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(600), WRAP);
        lp.bottomMargin = dp(4);
        row.setLayoutParams(lp);
        return row;
    }

    private void style(View row) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused},
                Ui.round((cfg.accent() & 0x00FFFFFF) | 0x33000000, dp(10)));
        bg.addState(new int[0], Ui.round(0x00000000, dp(10)));
        row.setBackground(bg);
    }

    private TextView label(String text) {
        TextView v = Ui.text(this, 16, Ui.TEXT, Ui.MEDIUM);
        v.setText(text);
        return v;
    }

    private View gap() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(18)));
        return v;
    }

    private void pick(String kind) {
        List<Item> items = Catalog.APPS.equals(kind)
                ? Catalog.apps(this)
                : Catalog.inputs(this, (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE));
        CharSequence[] names = new CharSequence[items.size()];
        boolean[] on = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) {
            names[i] = Catalog.label(cfg, items.get(i));
            on[i] = Catalog.shown(cfg, kind, items.get(i));
        }
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(Catalog.APPS.equals(kind) ? "Apps on home screen" : "Inputs in menu")
                .setMultiChoiceItems(names, on, (d, which, checked) ->
                        Catalog.setShown(cfg, kind, items.get(which), checked))
                .setPositiveButton("Done", null)
                .show();
    }

    private void openTvSettings() {
        Intent leanback = new Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.LEANBACK_SETTINGS");
        try {
            Intent target = leanback.resolveActivity(getPackageManager()) != null
                    ? leanback : new Intent(Settings.ACTION_SETTINGS);
            startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Could not open TV settings", Toast.LENGTH_SHORT).show();
        }
    }

    private void orderApps(int focus) {
        List<Item> items = Catalog.visible(cfg, Catalog.APPS, Catalog.apps(this));
        if (items.isEmpty()) {
            new AlertDialog.Builder(this, Ui.DIALOG).setTitle("App order")
                    .setMessage("Show some apps using Apps on home screen first.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        CharSequence[] names = new CharSequence[items.size()];
        for (int i = 0; i < items.size(); i++) names[i] = (i + 1) + ". " + Catalog.label(cfg, items.get(i));
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("App order · Choose an app to move")
                .setSingleChoiceItems(names, Math.min(focus, items.size() - 1), (dialog, which) -> {
                    dialog.dismiss();
                    new AlertDialog.Builder(this, Ui.DIALOG)
                            .setTitle(Catalog.label(cfg, items.get(which)))
                            .setItems(new String[] {"Move earlier", "Move later", "Move to first", "Move to last"},
                                    (moveDialog, action) -> {
                                        int target = action == 0 ? Math.max(0, which - 1)
                                                : action == 1 ? Math.min(items.size() - 1, which + 1)
                                                : action == 2 ? 0 : items.size() - 1;
                                        List<String> ids = new ArrayList<>();
                                        for (Item item : Order.move(items, which, target)) ids.add(item.id);
                                        Catalog.saveOrder(cfg, Catalog.APPS, ids);
                                        orderApps(target);
                                    })
                            .setNegativeButton("Back", (moveDialog, action) -> orderApps(which))
                            .show();
                }).setNegativeButton("Done", null).show();
    }

    private void reset() {
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Reset the home screen layout?")
                .setMessage("Order, names and hidden tiles go back to default. Colors and sizes stay.")
                .setPositiveButton("Reset", (d, w) -> cfg.removePrefix("name.", "apps.", "inputs."))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
