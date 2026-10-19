package tv.evma.search;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A separate, network-enabled search screen; the home launcher stays offline. */
public final class Search extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private EditText query;
    private TextView status;
    private LinearLayout results;
    private volatile int generation;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("catalog", MODE_PRIVATE);
        getExternalFilesDir(null); // Creates the single-use ADB provisioning directory.
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(48), dp(24), dp(48), dp(20));
        column.setBackgroundColor(0xFF0B1220);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Find your next watch", 26);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(button("Services", this::services));
        head.addView(button("Search setup", this::setup));
        column.addView(head);
        LinearLayout entry = new LinearLayout(this);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        query = new EditText(this);
        query.setSingleLine(true);
        query.setTextColor(Color.WHITE);
        query.setHintTextColor(0x99FFFFFF);
        query.setHint("Search movies & shows");
        query.setTextSize(19);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setInputType(InputType.TYPE_CLASS_TEXT);
        query.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) { search(); return true; }
            return false;
        });
        entry.addView(query, new LinearLayout.LayoutParams(0, dp(58), 1));
        entry.addView(button("Search", this::search));
        column.addView(entry);
        status = text("United States · Subscription and free only", 14);
        status.setPadding(0, dp(12), 0, dp(10));
        column.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(false);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(results);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView source = text("Availability: Movie of the Night · US catalog coverage varies by service and season.", 12);
        source.setTextColor(0x99FFFFFF);
        column.addView(source);
        setContentView(column);
        query.requestFocus();
        if (prefs.getString("key", "").isEmpty()) {
            status.setText("Open Search setup to add your catalog key. Your streaming passwords are not needed.");
        }
    }

    private Set<String> selected() {
        Set<String> defaults = new HashSet<>();
        for (String name : Catalog.SERVICES) defaults.add(Catalog.serviceKey(name));
        return new HashSet<>(prefs.getStringSet("services", defaults));
    }

    private void search() {
        String term = query.getText().toString().trim();
        String key = prefs.getString("key", "");
        if (key.isEmpty()) { setup(); return; }
        if (term.length() < 2 || term.length() > 180) {
            status.setText("Enter a title between 2 and 180 characters."); return;
        }
        Set<String> selection = selected();
        if (selection.isEmpty()) { status.setText("Choose at least one service first."); services(); return; }
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(), 0);
        int ticket = ++generation;
        results.removeAllViews();
        status.setText("Searching US subscription and free catalogs…");
        io.execute(() -> {
            if (ticket != generation) return;
            try {
                List<Catalog.Title> found = Catalog.search(term, key, selection);
                ui.post(() -> {
                    if (ticket != generation || isDestroyed()) return;
                    status.setText(found.isEmpty() ? "No matching titles found. Try another name."
                            : "Select a title to see where to watch.");
                    for (Catalog.Title item : found) {
                        String availability;
                        if (item.offers.isEmpty()) availability = "Not listed on your selected services";
                        else {
                            List<String> names = new ArrayList<>();
                            for (Catalog.Offer offer : item.offers) names.add(offer.service);
                            availability = String.join(" · ", names);
                        }
                        Button row = button(item.name + "\n" + item.detail + "\n" + availability, () -> choose(item));
                        row.setAllCaps(false);
                        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                        row.setPadding(dp(16), dp(12), dp(16), dp(12));
                        results.addView(row, new LinearLayout.LayoutParams(-1, -2));
                    }
                    if (results.getChildCount() > 0) results.getChildAt(0).requestFocus();
                });
            } catch (IOException e) {
                String message = e.getMessage();
                // Network failures must never be reported as title unavailability.
                ui.post(() -> {
                    if (ticket == generation && !isDestroyed()) status.setText(
                            "Could not check availability. " + (message == null ? "Check your connection and try again." : message));
                });
            }
        });
    }

    private void choose(Catalog.Title item) {
        if (item.offers.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Not listed on your services")
                    .setMessage("No US subscription or free option was found for “" + item.name
                            + "” on your selected services. Rentals, purchases and extra channel subscriptions are excluded. "
                            + "Catalog coverage can be incomplete; you can also check the service directly.")
                    .setPositiveButton("OK", null).show();
        } else if (item.offers.size() == 1) {
            open(item, item.offers.get(0));
        } else {
            CharSequence[] names = new CharSequence[item.offers.size()];
            for (int i = 0; i < names.length; i++) names[i] = item.offers.get(i).label();
            new AlertDialog.Builder(this).setTitle("Available on multiple services")
                    .setItems(names, (dialog, index) -> open(item, item.offers.get(index)))
                    .setNegativeButton("Cancel", null).show();
        }
    }

    private void open(Catalog.Title item, Catalog.Offer offer) {
        PackageManager pm = getPackageManager();
        Set<String> matches = new HashSet<>();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                if (offer.key.equals(Catalog.serviceKey(ri.loadLabel(pm).toString()))) matches.add(ri.activityInfo.packageName);
            }
        }
        if (matches.isEmpty()) {
            message("App not found", "“" + offer.service + "” was listed by the catalog, but its app could not be identified on this TV. Open the service from your Apps row.");
            return;
        }
        if (matches.size() > 1) {
            message("Choose the app manually", "More than one installed app matches “" + offer.service + "”. Open the service from your Apps row.");
            return;
        }
        String pkg = matches.iterator().next();
        Uri uri = Uri.parse(offer.link);
        if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null) {
            Intent link = new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(pkg);
            try {
                if (link.resolveActivity(pm) != null) { startActivity(link); return; }
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // Offer an honest fallback; never claim the title page opened successfully.
            }
        }
        new AlertDialog.Builder(this).setTitle("Title link not supported")
                .setMessage("This version of “" + offer.service + "” could not open the title link. You can open the app and search for “" + item.name + "”.")
                .setPositiveButton("Open app", (dialog, which) -> {
                    Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
                    if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
                    try {
                        if (launch == null) throw new ActivityNotFoundException();
                        startActivity(launch);
                    } catch (ActivityNotFoundException | SecurityException e) { message("Could not open app", "Open the service from the EVMA Apps row."); }
                }).setNegativeButton("Cancel", null).show();
    }

    private void setup() {
        EditText key = new EditText(this);
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setHint("Movie of the Night API key");
        key.setPadding(dp(24), dp(12), dp(24), dp(12));
        key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        new AlertDialog.Builder(this).setTitle("Search setup")
                .setMessage("Get your personal API key from developers.movieofthenight.com. It stays in this search app's private storage. "
                        + "Search titles are sent to that catalog service. Leave the field empty to keep your current key.")
                .setView(key)
                .setPositiveButton("Save", (dialog, which) -> {
                    String value = key.getText().toString().trim();
                    if (!value.isEmpty()) {
                        if (value.length() > 4096 || !value.matches("[\\x21-\\x7E]+")) {
                            status.setText("The key contains invalid characters. Copy the key exactly from your developer account.");
                            return;
                        }
                        ++generation;
                        prefs.edit().putString("key", value).apply();
                        results.removeAllViews();
                        status.setText("Key saved. Search for a movie or TV show.");
                    }
                }).setNeutralButton("Remove key", (dialog, which) -> {
                    ++generation; prefs.edit().remove("key").apply(); results.removeAllViews();
                    status.setText("Catalog key removed. Add one in Search setup to enable searches.");
                }).setNegativeButton("Cancel", null).show();
    }

    private void services() {
        Set<String> choice = selected();
        boolean[] checked = new boolean[Catalog.SERVICES.length];
        for (int i = 0; i < checked.length; i++) checked[i] = choice.contains(Catalog.serviceKey(Catalog.SERVICES[i]));
        new AlertDialog.Builder(this).setTitle("Your streaming services")
                .setMultiChoiceItems(Catalog.SERVICES, checked, (dialog, which, on) -> {
                    String id = Catalog.serviceKey(Catalog.SERVICES[which]);
                    if (on) choice.add(id); else choice.remove(id);
                }).setPositiveButton("Save", (dialog, which) -> {
                    ++generation; prefs.edit().putStringSet("services", choice).apply();
                    results.removeAllViews(); status.setText("Services updated. Run a new search.");
                }).setNegativeButton("Cancel", null).show();
    }

    private void message(String title, String detail) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(detail).setPositiveButton("OK", null).show();
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(Color.WHITE);
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        return view;
    }

    private Button button(String value, Runnable action) {
        Button view = new Button(this);
        view.setText(value); view.setTextColor(Color.WHITE); view.setTextSize(15);
        StateListDrawable state = new StateListDrawable();
        state.addState(new int[] {android.R.attr.state_focused}, background(0xFF285FB7));
        state.addState(new int[0], background(0xFF1C293C));
        view.setBackground(state); view.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4)); view.setLayoutParams(lp);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private GradientDrawable background(int color) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(9)); return shape;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        ++generation; io.shutdownNow(); super.onDestroy();
    }
}
