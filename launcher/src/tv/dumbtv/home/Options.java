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
                + "\nWallpaper: the bundled image is included. Put wallpaper.jpg in that folder to use your own."
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
        int i = Arrays.asList(c.values)…2800 tokens truncated…, and catalog
  removals may differ from the results. Live searches cannot be validated without
  a working API key. API results are not proof of the user's subscription entitlement.

## Build and recovery

Use JDK 17, Android API 28 and build-tools as described in `../launcher/README.md`.
Run `bash search/build.sh` from the repository root; no Gradle is needed.
The companion APK is `search/build/evma-search.apk`. Keep its signing key for updates.
Install it with ADB separately; building never installs or changes HOME behavior.

The search manifest declares no HOME intent filter or accessibility service.
Back returns to EVMA. Amazon remains the preview's HOME target. Rolling back the
launcher APK does not require removing the search app or changing streaming apps.
Uninstalling the search app deletes its own API key and service settings.

## Fairfield weather and automatic key setup

The companion also provides Fairfield, Ohio weather to the home screen through
a bound Messenger service. Requests are accepted only from the installed
`tv.dumbtv.home` package when it has the same signing certificate. Build the
launcher first; both APKs must use the same private signing key.

Weather uses the [Open-Meteo API](https://open-meteo.com/en/docs), fixed Fairfield
city-center coordinates, Fahrenheit, and America/New_York time. It needs no API
key or location permission and does not consume movie-search requests. Successful
responses are cached for 20 minutes. Failures are rate-limited, and an older
reading is labeled cached rather than presented as freshly fetched weather.
The launcher binds only while visible; no always-running or foreground service
is added. Weather data attribution is displayed on the card.

Manual setup is the active Windows workflow. APK updates use `install -r` to
preserve the key and selected services; do not uninstall or clear app data.
The preview updater does not attempt automatic key transfer. The following
optional developer import endpoint remains available but is not used by updates.

The optional developer endpoint accepts ADB standard input through a content provider restricted to ADB
shell (UID 2000) and the system `DUMP` permission. The provider validates at most
4096 bytes and saves the key in private preferences. This avoids external-storage
access, which Fire OS can deny. Only a status value can be queried; the provider
never returns the key. Any existing PC staging file remains in the ignored
`report/private/` folder; the current update scripts do not read it.
Keys are never included in source, APKs, intent extras, command
arguments, logs, status output, or temporary files on the TV.
