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
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.EditText;
import android.widget.LinearLayout;
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
    private GridView results;
    private ProgressBar progress;
    private TextView emptyTitle, emptyDetail;
    private LinearLayout empty;
    private final List<Catalog.Title> titles = new ArrayList<>();
    private final Posters posters = new Posters();
    private Ratings ratings;
    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return titles.size(); }
        @Override public Object getItem(int position) { return titles.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View reusable, ViewGroup parent) {
            return posterCard(titles.get(position));
        }
    };
    private volatile int generation;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("catalog", MODE_PRIVATE);
        ratings = new Ratings(this);
        FrameLayout root = new FrameLayout(this);
        ImageView wallpaper = new ImageView(this);
        wallpaper.setImageResource(R.drawable.wallpaper);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xC9081425, 0xE5091220, 0xFA080D17}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(40), dp(18), dp(40), dp(12));
        column.setClipChildren(false);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("EVMA ♥  /  Discover", 22);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(button("⚙  Settings", this::settings));
        column.addView(head);
        LinearLayout entry = new LinearLayout(this);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setPadding(dp(16), dp(3), dp(5), dp(3));
        entry.setBackground(background(0xB51B293D));
        LinearLayout.LayoutParams entryLayout = new LinearLayout.LayoutParams(-1, dp(60));
        entryLayout.setMargins(0, dp(10), 0, 0);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_search);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        entry.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(26)));
        query = new EditText(this);
        query.setSingleLine(true);
        query.setTextColor(Color.WHITE);
        query.setHintTextColor(0x99FFFFFF);
        query.setHint("Search movies & shows");
        query.setTextSize(19);
        query.setPadding(dp(8), dp(8), dp(12), dp(8));
        query.setBackground(controlBackground());
        query.setSelectAllOnFocus(true);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setInputType(InputType.TYPE_CLASS_TEXT);
        query.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) { search(); return true; }
            return false;
        });
        entry.addView(query, new LinearLayout.LayoutParams(0, dp(48), 1));
        entry.addView(button("Search", this::search));
        column.addView(entry, entryLayout);
        LinearLayout summary = new LinearLayout(this);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams spinner = new LinearLayout.LayoutParams(dp(20), dp(20));
        spinner.setMargins(0, 0, dp(10), 0);
        summary.addView(progress, spinner);
        status = text("United States · Subscription & free", 13);
        status.setTextColor(0xFFB8C8DB);
        summary.addView(status, new LinearLayout.LayoutParams(-1, -2));
        column.addView(summary, new LinearLayout.LayoutParams(-1, dp(36)));
        FrameLayout content = new FrameLayout(this);
        results = new GridView(this);
        results.setNumColumns(GridView.AUTO_FIT);
        results.setColumnWidth(dp(140));
        results.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        results.setHorizontalSpacing(dp(14));
        results.setVerticalSpacing(dp(16));
        results.setPadding(dp(5), dp(5), dp(5), dp(12));
        results.setClipToPadding(false);
        results.setSelector(android.R.color.transparent);
        results.setDrawSelectorOnTop(false);
        results.setAdapter(adapter);
        results.setOnItemClickListener((parent, view, position, id) -> choose(titles.get(position)));
        content.addView(results, new FrameLayout.LayoutParams(-1, -1));
        empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(30), dp(12), dp(30), dp(12));
        TextView mark = text("✧", 38);
        mark.setTextColor(0xFFB8D7FF);
        empty.addView(mark);
        emptyTitle = text("Something good is waiting", 24);
        emptyTitle.setGravity(Gravity.CENTER);
        empty.addView(emptyTitle);
        emptyDetail = text("Search a movie or show to find where you can watch it.", 15);
        emptyDetail.setGravity(Gravity.CENTER);
        emptyDetail.setTextColor(0xFFB8C8DB);
        emptyDetail.setPadding(0, dp(10), 0, 0);
        empty.addView(emptyDetail);
        content.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        column.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView source = text("Availability: Movie of the Night · US subscription & free · RT critics scores via OMDb · Scores may be missing", 11);
        source.setTextColor(0x99FFFFFF);
        column.addView(source);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        query.requestFocus();
        if (prefs.getString("key", "").isEmpty()) {
            showEmpty("Let's set up movie night", "Open Settings → Search setup to add your catalog key.");
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
        clearResults();
        progress.setVisibility(View.VISIBLE);
        showEmpty("Finding your next watch", "Checking your selected US subscription and free services…");
        status.setText("Searching US subscription and free catalogs…");
        io.execute(() -> {
            if (ticket != generation) return;
            try {
                List<Catalog.Title> found = Catalog.search(term, key, selection);
                ui.post(() -> {
                    if (ticket != generation || isDestroyed()) return;
                    progress.setVisibility(View.GONE);
                    titles.addAll(found);
                    adapter.notifyDataSetChanged();
                    status.setText(found.isEmpty() ? "No matching titles"
                            : found.size() + " titles · Select a poster to see where to watch");
                    if (found.isEmpty()) showEmpty("No titles found", "Try a different title or check the spelling.");
                    else {
                        empty.setVisibility(View.GONE);
                        results.setSelection(0);
                        results.requestFocus();
                    }
                });
            } catch (IOException e) {
                String message = e.getMessage();
                // Network failures must never be reported as title unavailability.
                ui.post(() -> {
                    if (ticket == generation && !isDestroyed()) {
                        progress.setVisibility(View.GONE);
                        status.setText("Availability check failed");
                        showEmpty("Couldn't reach the catalog", message == null
                                ? "Check your connection and try again." : message);
                    }
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
                        clearResults();
                        showEmpty("You're ready for movie night", "Search a title to find where to watch.");
                        status.setText("Key saved. Search for a movie or TV show.");
                    }
                }).setNeutralButton("Remove key", (dialog, which) -> {
                    ++generation; prefs.edit().remove("key").apply(); clearResults();
                    showEmpty("Search needs a catalog key", "Open Settings → Search setup to add one.");
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
                    clearResults(); status.setText("Services updated. Run a new search.");
                    showEmpty("Your services, your movie night", "Search again to check your updated selection.");
                }).setNegativeButton("Cancel", null).show();
    }

    private void message(String title, String detail) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(detail).setPositiveButton("OK", null).show();
    }

    private void settings() {
        new AlertDialog.Builder(this).setTitle("Search settings")
                .setItems(new String[] {"Your streaming services", "Search setup", "Rotten Tomatoes setup"}, (dialog, which) -> {
                    if (which == 0) services(); else if (which == 1) setup(); else ratingsSetup();
                }).setNegativeButton("Close", null).show();
    }

    private void ratingsSetup() {
        EditText key = new EditText(this);
        key.setSingleLine(true);
        key.setHint("OMDb API key");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        key.setPadding(dp(24), dp(12), dp(24), dp(12));
        new AlertDialog.Builder(this).setTitle("Rotten Tomatoes scores")
                .setMessage("Get a separate key at omdbapi.com/apikey.aspx and activate it by email. "
                        + "OMDb receives the IMDb IDs of visible titles to check their Rotten Tomatoes critics scores. "
                        + "Coverage is incomplete, especially for TV series; these are not audience or season-specific scores. "
                        + "Your Movie of the Night key is separate. Leave blank to keep your saved OMDb key.")
                .setView(key)
                .setPositiveButton("Save", (dialog, which) -> {
                    String value = key.getText().toString().trim();
                    if (value.isEmpty()) return;
                    if (value.length() > 256 || !value.matches("[a-zA-Z0-9]+")) {
                        status.setText("Copy the OMDb key exactly, using letters and numbers only."); return;
                    }
                    prefs.edit().putString("ratingsKey", value).apply();
                    ratings.reset();
                    adapter.notifyDataSetChanged();
                    status.setText("Ratings key saved. Scores will load for visible posters.");
                }).setNeutralButton("Remove key", (dialog, which) -> {
                    prefs.edit().remove("ratingsKey").apply();
                    ratings.reset(); adapter.notifyDataSetChanged();
                    status.setText("Ratings disabled. Movie search is still available.");
                }).setNegativeButton("Cancel", null).show();
    }

    private void clearResults() {
        progress.setVisibility(View.GONE);
        posters.cancelPending();
        ratings.reset();
        titles.clear();
        adapter.notifyDataSetChanged();
    }

    private void showEmpty(String heading, String detail) {
        emptyTitle.setText(heading);
        emptyDetail.setText(detail);
        empty.setVisibility(View.VISIBLE);
    }

    private View posterCard(Catalog.Title item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(4), dp(4), dp(4), dp(8));
        card.setBackground(controlBackground());
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        StringBuilder available = new StringBuilder();
        for (Catalog.Offer offer : item.offers) {
            if (available.length() > 0) available.append(", ");
            available.append(offer.label());
        }
        card.setContentDescription(item.name + ", " + item.detail + ", "
                + (available.length() == 0 ? "Not listed on your selected services" : available));
        card.setTag(card.getContentDescription());
        FrameLayout art = new FrameLayout(this);
        art.setBackground(background(0xFF1C2A3C));
        art.setClipToOutline(true);
        TextView fallback = text(item.name, 19);
        fallback.setGravity(Gravity.CENTER);
        fallback.setMaxLines(5);
        fallback.setEllipsize(TextUtils.TruncateAt.END);
        fallback.setPadding(dp(12), dp(12), dp(12), dp(12));
        art.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(image, new FrameLayout.LayoutParams(-1, -1));
        TextView score = text("RT …", 11);
        score.setPadding(dp(8), dp(4), dp(8), dp(4));
        score.setBackground(background(0xED0B1421));
        FrameLayout.LayoutParams scoreLayout = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        scoreLayout.setMargins(dp(7), 0, dp(7), dp(7));
        art.addView(score, scoreLayout);
        card.addView(art, new LinearLayout.LayoutParams(-1, dp(192)));
        ratings.load(item.imdbId, score);
        posters.load(item.poster, image);
        TextView name = text(item.name, 15);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(dp(5), dp(8), dp(5), 0);
        card.addView(name, new LinearLayout.LayoutParams(-1, dp(31)));
        TextView detail = text(item.detail, 12);
        detail.setTextColor(0xFFB8C8DB);
        detail.setPadding(dp(5), 0, dp(5), 0);
        detail.setSingleLine(true);
        card.addView(detail, new LinearLayout.LayoutParams(-1, dp(20)));
        LinearLayout badges = new LinearLayout(this);
        badges.setGravity(Gravity.CENTER_VERTICAL);
        badges.setPadding(dp(5), dp(5), dp(5), 0);
        if (item.offers.isEmpty()) {
            TextView unavailable = text("Not on your services", 10);
            unavailable.setTextColor(0xFFD9BC9A);
            badges.addView(unavailable);
        } else {
            Catalog.Offer first = item.offers.get(0);
            TextView badge = text(first.service, 10);
            badge.setSingleLine(true);
            badge.setEllipsize(TextUtils.TruncateAt.END);
            badge.setPadding(dp(7), dp(3), dp(7), dp(3));
            badge.setBackground(background(serviceColor(first.key)));
            badges.addView(badge, new LinearLayout.LayoutParams(0, -2, 1));
            if (item.offers.size() > 1) {
                TextView count = text(" +" + (item.offers.size() - 1), 11);
                count.setTextColor(0xFFD1E5FF);
                badges.addView(count);
            }
        }
        card.addView(badges, new LinearLayout.LayoutParams(-1, dp(29)));
        return card;
    }

    private int serviceColor(String key) {
        switch (key) {
            case "netflix": return 0xFF922735;
            case "primevideo": return 0xFF185C83;
            case "disney": return 0xFF344F9F;
            case "hulu": return 0xFF186B4B;
            case "hbomax": return 0xFF4E3990;
            case "tubi": return 0xFF675122;
            default: return 0xFF334B66;
        }
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(Color.WHITE);
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        return view;
    }

    private Button button(String value, Runnable action) {
        Button view = new Button(this);
        view.setText(value); view.setTextColor(Color.WHITE); view.setTextSize(14);
        view.setAllCaps(false);
        view.setMinHeight(dp(44));
        view.setMinimumHeight(dp(44));
        view.setSingleLine(true);
        view.setIncludeFontPadding(true);
        view.setBackground(controlBackground()); view.setPadding(dp(18), dp(8), dp(18), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4)); view.setLayoutParams(lp);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private StateListDrawable controlBackground() {
        StateListDrawable state = new StateListDrawable();
        GradientDrawable focused = background(0xE8355479);
        focused.setStroke(dp(2), 0xFFD6E9FF);
        GradientDrawable selected = background(0xE8355479);
        selected.setStroke(dp(2), 0xFFD6E9FF);
        state.addState(new int[] {android.R.attr.state_focused}, focused);
        state.addState(new int[] {android.R.attr.state_selected}, selected);
        state.addState(new int[] {android.R.attr.state_pressed}, background(0xFF466B98));
        state.addState(new int[0], background(0xA51B293D));
        return state;
    }

    private GradientDrawable background(int color) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(9)); return shape;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        ++generation; io.shutdownNow(); posters.close(); if (ratings != null) ratings.close(); super.onDestroy();
    }
}
