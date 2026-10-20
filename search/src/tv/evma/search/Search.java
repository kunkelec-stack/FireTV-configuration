package tv.evma.search;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
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
import android.os.Parcelable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
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
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import android.widget.ListView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Random;
import java.util.Collections;
import java.util.function.IntConsumer;
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
    private Ratings audience;
    private Library library;
    private Button profileButton;
    private Button filterButton;
    private LinearLayout suggestions;
    private HorizontalScrollView suggestionsScroll;
    private Dialog detailsDialog;
    private WallpaperBackdrop backdrop;
    private int detailsGeneration;
    private boolean watchlistMode;
    private Parcelable returnGridState;
    private View returnFocus;
    private String returnIdentity = "";
    private int returnPosition, returnGeneration;
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
        audience = new Ratings(this, true);
        library = new Library(this);
        FrameLayout root = new FrameLayout(this);
        ImageView wallpaper = new ImageView(this);
        wallpaper.setImageResource(R.drawable.wallpaper);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xC9081425, 0xE5091220, 0xFA080D17}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        backdrop = new WallpaperBackdrop(this, wallpaper, shade, () -> {
            wallpaper.setImageResource(R.drawable.wallpaper);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {0xC9081425, 0xE5091220, 0xFA080D17}));
        });
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(40), dp(18), dp(40), dp(12));
        column.setClipChildren(false);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("EVMA ♥  /  Discover", 22);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(button("Watchlist", this::watchlist));
        head.addView(button("Recent", this::recent));
        head.addView(button("Pick for us", this::randomPick));
        profileButton = button(library.name() + " ▾", this::profiles);
        head.addView(profileButton);
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
        suggestionsScroll = new HorizontalScrollView(this);
        suggestionsScroll.setHorizontalScrollBarEnabled(false);
        suggestionsScroll.setClipToPadding(false);
        suggestions = new LinearLayout(this);
        suggestions.setGravity(Gravity.CENTER_VERTICAL);
        suggestionsScroll.addView(suggestions);
        column.addView(suggestionsScroll, new LinearLayout.LayoutParams(-1, dp(56)));
        suggestionsScroll.setVisibility(View.GONE);
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable value) { updateSuggestions(); }
        });
        LinearLayout summary = new LinearLayout(this);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams spinner = new LinearLayout.LayoutParams(dp(20), dp(20));
        spinner.setMargins(0, 0, dp(10), 0);
        summary.addView(progress, spinner);
        status = text("United States · Subscription & free", 13);
        status.setTextColor(0xFFB8C8DB);
        summary.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        filterButton = button("Filters", () -> filterOptions(false));
        filterButton.setVisibility(View.GONE);
        summary.addView(filterButton);
        column.addView(summary, new LinearLayout.LayoutParams(-1, dp(52)));
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
        results.setOnItemClickListener((parent, view, position, id) -> details(titles.get(position), watchlistMode));
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
        TextView source = text("Movie of the Night · US subscription & free · Rotten Tomatoes® scores: OMDb / MDBList · Coverage varies", 11);
        source.setTextColor(0x99FFFFFF);
        column.addView(source);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        query.requestFocus();
        updateSuggestions();
        if (prefs.getString("key", "").isEmpty()) {
            showEmpty("Let's set up movie night", "Open Settings → Search setup to add your catalog key.");
        }
    }

    private Set<String> selected() {
        return library.services();
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
        watchlistMode = false;
        filterButton.setVisibility(View.GONE);
        library.remember(term);
        clearResults();
        progress.setVisibility(View.VISIBLE);
        updateSuggestions();
        showEmpty("Finding your next watch", "Checking your selected US subscription and free services…");
        status.setText("Searching US subscription and free catalogs…");
        io.execute(() -> {
            if (ticket != generation) return;
            try {
                List<Catalog.Title> found = Catalog.search(term, key, selection);
                ui.post(() -> {
                    if (ticket != generation || isDestroyed()) return;
                    progress.setVisibility(View.GONE);
                    updateSuggestions();
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
                        updateSuggestions();
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
            menu().setTitle("Not listed on your services")
                    .setMessage("No US subscription or free option was found for “" + item.name
                            + "” on your selected services. Rentals, purchases and extra channel subscriptions are excluded. "
                            + "Catalog coverage can be incomplete; you can also check the service directly.")
                    .setPositiveButton("OK", null).show();
        } else if (item.offers.size() == 1) {
            open(item, item.offers.get(0));
        } else {
            CharSequence[] names = new CharSequence[item.offers.size()];
            for (int i = 0; i < names.length; i++) names[i] = item.offers.get(i).label();
            menu().setTitle("Available on multiple services")
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
        menu().setTitle("Title link not supported")
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
        menu().setTitle("Search setup")
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
        menu().setTitle("Your streaming services")
                .setMultiChoiceItems(Catalog.SERVICES, checked, (dialog, which, on) -> {
                    String id = Catalog.serviceKey(Catalog.SERVICES[which]);
                    if (on) choice.add(id); else choice.remove(id);
                }).setPositiveButton("Save", (dialog, which) -> {
                    if (!library.services(choice)) { message("Could not save services", "Try again."); return; }
                    ++generation;
                    clearResults(); status.setText("Services updated. Run a new search.");
                    showEmpty("Your services, your movie night", "Search again to check your updated selection.");
                }).setNegativeButton("Cancel", null).show();
    }

    private void message(String title, String detail) {
        menu().setTitle(title).setMessage(detail).setPositiveButton("OK", null).show();
    }

    private void profiles() {
        menu().setTitle("Who's watching?")
                .setSingleChoiceItems(Library.NAMES, library.active(), (dialog, index) -> {
                    if (!library.activate(index)) { message("Could not switch profile", "Please try again."); return; }
                    dialog.dismiss();
                    ++generation;
                    clearResults();
                    watchlistMode = false;
                    filterButton.setVisibility(View.GONE);
                    query.setText("");
                    profileButton.setText(library.name() + " ▾");
                    status.setText(library.name() + " · US subscription & free");
                    showEmpty("Welcome, " + library.name(), "Your watchlist, recent searches, and streaming filters are ready.");
                    query.requestFocus();
                }).setNegativeButton("Cancel", null).show();
    }

    private void recent() {
        List<String> history = library.recent();
        if (history.isEmpty()) { message("No recent searches", "Search for a title to start " + library.name() + "'s history."); return; }
        List<String> labels = new ArrayList<>(history);
        labels.add("Clear recent searches…");
        menu().setTitle(library.name() + " · Recent searches")
                .setItems(labels.toArray(new String[0]), (dialog, index) -> {
                    if (index < history.size()) { query.setText(history.get(index)); search(); }
                    else menu().setTitle("Clear this profile's search history?")
                            .setPositiveButton("Clear", (confirm, action) -> {
                                if (!library.clearRecent()) message("Could not clear history", "Please try again.");
                                updateSuggestions();
                            }).setNegativeButton("Cancel", null).show();
                }).setNegativeButton("Close", null).show();
    }

    private void watchlist() {
        try {
            library.saved(); // Read before replacing the current screen, preserving it on failure.
            ++generation;
            clearResults();
            watchlistMode = true;
            filterButton.setVisibility(View.VISIBLE);
            updateSuggestions();
            populateWatchlist();
            if (!titles.isEmpty()) { results.setSelection(0); results.requestFocus(); }
            else filterButton.requestFocus();
        } catch (IOException e) { message("Could not open watchlist", "The saved list could not be read. Your data has been kept."); }
    }

    private void populateWatchlist() throws IOException {
        int total = library.saved().size();
        WatchFilter filter = library.filter(false);
        List<Catalog.Title> saved = library.filtered(filter);
        titles.clear(); titles.addAll(saved);
        adapter.notifyDataSetChanged();
        status.setText(library.name() + " · " + saved.size() + "/" + total + " saved · " + filter.summary());
        if (total == 0) showEmpty("Your watchlist starts here", "Open a title and choose Save to watchlist.");
        else if (saved.isEmpty()) showEmpty("No titles match these filters", "Choose Filters to change type, genre, runtime, or watched status.");
        else empty.setVisibility(View.GONE);
    }

    private void randomPick() {
        filterOptions(true);
    }

    private void pickNow(WatchFilter filter) {
        try {
            filter.watched = 1;
            List<Catalog.Title> candidates = library.filtered(filter);
            if (candidates.isEmpty()) {
                message("No unwatched titles match", "Save more titles, mark a title unwatched, or broaden your Pick for us filters."); return;
            }
            String lastPick = library.lastPick();
            if (candidates.size() > 1) candidates.removeIf(item -> item.identity().equals(lastPick));
            Catalog.Title pick = candidates.get(new Random().nextInt(candidates.size()));
            library.lastPick(pick.identity());
            details(pick, true);
        } catch (IOException e) { message("Could not choose a title", "The watchlist could not be read. Your data has been kept."); }
    }

    private void updateSuggestions() {
        if (suggestions == null || progress == null) return;
        suggestions.removeAllViews();
        List<String> history = library.recent();
        boolean show = query.getText().toString().trim().isEmpty() && !watchlistMode
                && progress.getVisibility() != View.VISIBLE && !history.isEmpty();
        suggestionsScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        TextView label = text("Recent", 12);
        label.setPadding(0, 0, dp(8), 0);
        label.setTextColor(0xFFB8C8DB);
        suggestions.addView(label);
        for (String term : history.subList(0, Math.min(5, history.size()))) {
            Button suggestion = button(term, () -> { query.setText(term); search(); });
            suggestion.setMaxWidth(dp(230));
            suggestion.setEllipsize(TextUtils.TruncateAt.END);
            suggestions.addView(suggestion);
        }
    }

    private void choice(String title, String[] values, int selected, IntConsumer action) {
        menu().setTitle(title).setSingleChoiceItems(values, selected, (dialog, which) -> {
            dialog.dismiss(); action.accept(which);
        }).setNegativeButton("Cancel", null).show();
    }

    private void filterOptions(boolean picker) {
        WatchFilter filter = library.filter(picker);
        final List<String> genres = new ArrayList<>();
        try {
            for (Catalog.Title item : library.saved()) for (String name : item.genreNames)
                if (!genres.contains(name)) genres.add(name);
        } catch (IOException e) { message("Could not read watchlist", "Your data has been kept."); return; }
        Collections.sort(genres, String.CASE_INSENSITIVE_ORDER);
        genres.add(0, "All genres");
        String[] types = {"Movies & TV shows", "Movies", "TV shows"};
        String[] times = {"Any runtime", "Under 30 minutes", "Under 1 hour", "Under 2 hours", "Under 3 hours"};
        int[] caps = {0, 30, 60, 120, 180};
        String[] seen = {"All titles", "Unwatched", "Watched"};
        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(dp(16), dp(8), dp(16), dp(8));
        Button type = button("Type: " + types[filter.type], () -> {});
        type.setOnClickListener(v -> choice("Title type", types, filter.type, index -> {
            filter.type = index; type.setText("Type: " + types[index]);
        }));
        options.addView(type, new LinearLayout.LayoutParams(-1, -2));
        Button genre = button("Genre: " + (filter.genre.isEmpty() ? "All genres" : filter.genre), () -> {});
        genre.setOnClickListener(v -> choice("Genre", genres.toArray(new String[0]), Math.max(0, genres.indexOf(filter.genre)), index -> {
            filter.genre = index == 0 ? "" : genres.get(index);
            genre.setText("Genre: " + genres.get(index));
        }));
        options.addView(genre, new LinearLayout.LayoutParams(-1, -2));
        int currentCap = 0;
        for (int i = 0; i < caps.length; i++) if (caps[i] == filter.minutes) currentCap = i;
        Button runtime = button("Runtime: " + times[currentCap], () -> {});
        runtime.setOnClickListener(v -> {
            int selected = 0;
            for (int i = 0; i < caps.length; i++) if (caps[i] == filter.minutes) selected = i;
            choice("Runtime · known durations only", times, selected, index -> {
                filter.minutes = caps[index]; runtime.setText("Runtime: " + times[index]);
            });
        });
        options.addView(runtime, new LinearLayout.LayoutParams(-1, -2));
        if (!picker) {
            Button watched = button("Status: " + seen[filter.watched], () -> {});
            watched.setOnClickListener(v -> choice("Watched status", seen, filter.watched, index -> {
                filter.watched = index; watched.setText("Status: " + seen[index]);
            }));
            options.addView(watched, new LinearLayout.LayoutParams(-1, -2));
        }
        TextView hint = text(picker ? "Chooses only unwatched titles from this profile's watchlist."
                : "Runtime limits exclude titles whose duration is unknown.", 13);
        hint.setPadding(dp(10), dp(12), dp(10), dp(6));
        hint.setTextColor(0xFFB8C8DB);
        options.addView(hint);
        menu().setTitle(picker ? "Pick something for " + library.name() : "Watchlist filters")
                .setView(options)
                .setPositiveButton(picker ? "Pick a title" : "Apply", (dialog, which) -> {
                    if (!library.filter(picker, filter)) { message("Could not save filters", "Please try again."); return; }
                    if (picker) pickNow(filter); else watchlist();
                }).setNeutralButton("Reset", (dialog, which) -> {
                    WatchFilter defaults = new WatchFilter();
                    if (!library.filter(picker, defaults)) { message("Could not reset filters", "Please try again."); return; }
                    if (picker) filterOptions(true); else watchlist();
                }).setNegativeButton("Cancel", null).show();
    }

    private void details(Catalog.Title item, boolean refresh) {
        if (detailsDialog == null) rememberOrigin();
        if (detailsDialog != null) detailsDialog.dismiss();
        Dialog dialog = new Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        detailsDialog = dialog;
        int request = ++detailsGeneration, ticket = generation;
        Catalog.Title[] current = {item};
        boolean[] verified = {!refresh};
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(40), dp(22), dp(40), dp(22));
        page.setBackgroundColor(0xFF0B1421);
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(item.name, 26);
        name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
        heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(button("Close", dialog::dismiss));
        page.addView(heading);
        LinearLayout body = new LinearLayout(this);
        body.setPadding(0, dp(16), 0, dp(16));
        LinearLayout artwork = new LinearLayout(this);
        artwork.setOrientation(LinearLayout.VERTICAL);
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(background(0xFF1C2A3C));
        artwork.addView(poster, new LinearLayout.LayoutParams(dp(158), dp(237)));
        posters.load(item.poster, poster);
        TextView score = text("RT …", 15);
        score.setPadding(0, dp(10), 0, dp(3));
        artwork.addView(score);
        TextView audienceScore = text("Audience …", 15);
        audienceScore.setPadding(0, dp(5), 0, dp(3));
        artwork.addView(audienceScore);
        TextView attribution = text("Rotten Tomatoes®\nOMDb · MDBList", 11);
        attribution.setTextColor(0xFFB8C8DB);
        artwork.addView(attribution);
        body.addView(artwork, new LinearLayout.LayoutParams(dp(180), -2));
        ScrollView scroll = new ScrollView(this);
        scroll.setPadding(dp(16), dp(10), dp(16), dp(10));
        scroll.setBackground(controlBackground());
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView meta = text("", 16);
        meta.setTextColor(0xFFB8D7FF);
        copy.addView(meta);
        TextView synopsis = text("", 18);
        synopsis.setPadding(0, dp(16), 0, dp(16));
        synopsis.setLineSpacing(dp(3), 1);
        copy.addView(synopsis);
        TextView credits = text(item.credits.isEmpty() ? "Cast information was not provided by the catalog." : item.credits, 16);
        credits.setLineSpacing(dp(4), 1);
        credits.setPadding(0, 0, 0, dp(20));
        copy.addView(credits);
        TextView availability = text("", 15);
        availability.setTextColor(0xFFB8C8DB);
        copy.addView(availability);
        scroll.addView(copy);
        body.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1));
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = new LinearLayout(this);
        Button where = button("Where to watch", () -> {
            if (verified[0]) choose(current[0]);
            else if (prefs.getString("key", "").isEmpty()) { dialog.dismiss(); setup(); }
            else details(current[0], true);
        });
        Button save = button("Save to watchlist", () -> {});
        save.setOnClickListener(view -> {
            try {
                boolean on = !library.contains(current[0]);
                library.setSaved(current[0], on);
                save.setText(on ? "Remove from watchlist" : "Save to watchlist");
                if (watchlistMode) populateWatchlist();
            } catch (IOException e) { message("Could not update watchlist", e.getMessage()); }
        });
        try { if (library.contains(item)) save.setText("Remove from watchlist"); }
        catch (IOException e) { save.setEnabled(false); }
        Button watched = button(library.watched(item) ? "Mark unwatched" : "Mark watched", () -> {});
        watched.setOnClickListener(view -> {
            try {
                boolean seen = !library.watched(current[0]);
                library.watched(current[0], seen);
                watched.setText(seen ? "Mark unwatched" : "Mark watched");
                if (watchlistMode) populateWatchlist(); else adapter.notifyDataSetChanged();
            } catch (IOException e) { message("Could not update watched status", e.getMessage()); }
        });
        actions.addView(where); actions.addView(save);
        actions.addView(watched);
        actions.addView(button("Pick again", () -> pickNow(library.filter(true))));
        page.addView(actions);
        dialog.setContentView(page);
        dialog.setOnDismissListener(closed -> {
            if (detailsDialog == dialog) {
                detailsDialog = null; ++detailsGeneration;
                restoreOrigin();
            }
        });
        dialog.show();
        ratings.load(item.imdbId, score);
        audience.load(item, audienceScore);
        meta.setText(item.detail + (item.runtime > 0 ? " · " + item.runtime + " min" : "")
                + (item.genres.isEmpty() ? "" : "\n" + item.genres));
        synopsis.setText(item.overview.isEmpty() ? "No synopsis was provided by the catalog." : item.overview);
        availability.setText(refresh ? "Checking current US subscription and free availability…" : availability(item));
        where.requestFocus();
        if (!refresh) return;
        String key = prefs.getString("key", "");
        if (key.isEmpty()) {
            availability.setText("Add your catalog key in Search setup to check current availability.");
            where.setText("Set up search"); return;
        }
        Set<String> services = selected();
        where.setEnabled(false); where.setText("Checking…");
        save.requestFocus();
        io.execute(() -> {
            try {
                Catalog.Title fresh = Catalog.refresh(item, key, services);
                ui.post(() -> {
                    if (isDestroyed() || ticket != generation || request != detailsGeneration || !dialog.isShowing()) return;
                    current[0] = fresh; verified[0] = true;
                    name.setText(fresh.name);
                    meta.setText(fresh.detail + (fresh.runtime > 0 ? " · " + fresh.runtime + " min" : "")
                            + (fresh.genres.isEmpty() ? "" : "\n" + fresh.genres));
                    synopsis.setText(fresh.overview.isEmpty() ? "No synopsis was provided by the catalog." : fresh.overview);
                    credits.setText(fresh.credits.isEmpty() ? "Cast information was not provided by the catalog." : fresh.credits);
                    try { library.refreshSaved(fresh); }
                    catch (IOException ignored) { /* Fresh credits remain visible; the previous saved snapshot is retained. */ }
                    availability.setText(availability(fresh));
                    where.setEnabled(true); where.setText("Where to watch");
                });
            } catch (IOException e) {
                ui.post(() -> {
                    if (isDestroyed() || ticket != generation || request != detailsGeneration || !dialog.isShowing()) return;
                    availability.setText("Could not verify availability. " + e.getMessage());
                    where.setEnabled(true); where.setText("Retry availability");
                });
            }
        });
    }

    private String availability(Catalog.Title item) {
        List<String> labels = new ArrayList<>();
        for (Catalog.Offer offer : item.offers) labels.add(offer.label());
        return labels.isEmpty() ? "No listed US subscription or free offers on your selected services."
                : "Where to watch · US\n" + String.join("\n", labels);
    }

    private void rememberOrigin() {
        returnGeneration = generation;
        returnFocus = getCurrentFocus();
        returnGridState = results.onSaveInstanceState();
        returnPosition = results.getSelectedItemPosition();
        returnIdentity = returnPosition >= 0 && returnPosition < titles.size()
                ? titles.get(returnPosition).identity() : "";
    }

    private void restoreOrigin() {
        ui.post(() -> {
            if (isDestroyed() || detailsDialog != null || returnGeneration != generation) return;
            if (returnGridState != null && !titles.isEmpty()) results.onRestoreInstanceState(returnGridState);
            if (returnFocus != null && returnFocus != results && returnFocus.isShown()) {
                returnFocus.requestFocus(); return;
            }
            if (titles.isEmpty()) {
                if (watchlistMode) filterButton.requestFocus(); else query.requestFocus();
                return;
            }
            int target = Math.min(Math.max(returnPosition, 0), titles.size() - 1);
            for (int i = 0; i < titles.size(); i++) if (returnIdentity.equals(titles.get(i).identity())) target = i;
            if (target != returnPosition) results.setSelection(target);
            results.requestFocus();
        });
    }

    private void settings() {
        menu().setTitle("Search settings")
                .setItems(new String[] {"Your streaming services", "Search setup", "Rotten Tomatoes setup", "Audience score setup", "Wallpaper manager"}, (dialog, which) -> {
                    if (which == 0) services(); else if (which == 1) setup(); else if (which == 2) ratingsSetup();
                    else if (which == 3) audienceSetup(); else startActivity(new Intent(this, Wallpapers.class));
                }).setNegativeButton("Close", null).show();
    }

    private void audienceSetup() {
        EditText key = new EditText(this);
        key.setSingleLine(true);
        key.setHint("MDBList API key");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        key.setPadding(dp(24), dp(12), dp(24), dp(12));
        menu().setTitle("Audience scores · MDBList")
                .setMessage("Get your API key from Preferences at mdblist.com. This key is separate from your OMDb and movie-search keys. "
                        + "MDBList receives visible titles' IMDb IDs to look up Rotten Tomatoes audience percentages. "
                        + "Coverage varies; verified-versus-all-audience and award status are not supplied. "
                        + "The key is stored privately on this TV and shared by your local profiles. Leave blank to keep your current key.")
                .setView(key).setPositiveButton("Save", (dialog, which) -> {
                    String value = key.getText().toString().trim();
                    if (value.isEmpty()) return;
                    if (value.length() > 256 || !value.matches("[A-Za-z0-9_-]+")) {
                        status.setText("Copy the MDBList key exactly, without spaces or quotes."); return;
                    }
                    prefs.edit().putString("audienceKey", value).apply();
                    audience.reset(); adapter.notifyDataSetChanged();
                    status.setText("Audience key saved. Scores load for visible posters.");
                }).setNeutralButton("Remove key", (dialog, which) -> {
                    prefs.edit().remove("audienceKey").apply();
                    audience.reset(); adapter.notifyDataSetChanged();
                    status.setText("Audience scores disabled. Critics scores and movie search remain available.");
                }).setNegativeButton("Cancel", null).show();
    }

    private void ratingsSetup() {
        EditText key = new EditText(this);
        key.setSingleLine(true);
        key.setHint("OMDb API key");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        key.setPadding(dp(24), dp(12), dp(24), dp(12));
        menu().setTitle("Rotten Tomatoes scores")
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
        audience.reset();
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
                + (watchlistMode ? "Saved title. Open details to check current availability."
                : available.length() == 0 ? "Not listed on your selected services" : available));
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
        LinearLayout scoreStack = new LinearLayout(this);
        scoreStack.setOrientation(LinearLayout.VERTICAL);
        TextView score = text("Critics …", 11);
        score.setPadding(dp(8), dp(4), dp(8), dp(4));
        score.setBackground(background(0xED0B1421));
        FrameLayout.LayoutParams scoreLayout = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        scoreLayout.setMargins(dp(7), 0, dp(7), dp(7));
        scoreStack.addView(score);
        TextView audienceScore = text("Audience …", 11);
        audienceScore.setPadding(dp(8), dp(4), dp(8), dp(4));
        audienceScore.setBackground(background(0xED0B1421));
        LinearLayout.LayoutParams audienceLayout = new LinearLayout.LayoutParams(-2, -2);
        audienceLayout.topMargin = dp(3);
        scoreStack.addView(audienceScore, audienceLayout);
        art.addView(scoreStack, scoreLayout);
        card.addView(art, new LinearLayout.LayoutParams(-1, dp(192)));
        ratings.load(item.imdbId, score);
        audience.load(item, audienceScore);
        posters.load(item.poster, image);
        TextView name = text(item.name, 15);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(dp(5), dp(8), dp(5), 0);
        card.addView(name, new LinearLayout.LayoutParams(-1, dp(31)));
        TextView detail = text(item.detail + (library.watched(item) ? " · Watched" : ""), 12);
        detail.setTextColor(0xFFB8C8DB);
        detail.setPadding(dp(5), 0, dp(5), 0);
        detail.setSingleLine(true);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(detail, new LinearLayout.LayoutParams(-1, dp(20)));
        LinearLayout badges = new LinearLayout(this);
        badges.setGravity(Gravity.CENTER_VERTICAL);
        badges.setPadding(dp(5), dp(5), dp(5), 0);
        if (watchlistMode || item.offers.isEmpty()) {
            TextView unavailable = text(watchlistMode ? "Check availability" : "Not on your services", 10);
            unavailable.setTextColor(watchlistMode ? 0xFFB8D7FF : 0xFFD9BC9A);
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
        view.setText(value);
        styleButton(view);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4)); view.setLayoutParams(lp);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private void styleButton(Button view) {
        view.setTextColor(Color.WHITE); view.setTextSize(14);
        view.setAllCaps(false);
        view.setMinHeight(dp(44));
        view.setMinimumHeight(dp(44));
        view.setSingleLine(true);
        view.setIncludeFontPadding(true);
        view.setBackground(controlBackground()); view.setPadding(dp(18), dp(8), dp(18), dp(8));
    }

    private AlertDialog.Builder menu() {
        return new AlertDialog.Builder(this, R.style.SearchMenu) {
            @Override public AlertDialog show() {
                AlertDialog dialog = super.show();
                for (int id : new int[] {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
                    Button action = dialog.getButton(id);
                    if (action != null) styleButton(action);
                }
                ListView list = dialog.getListView();
                if (list != null) {
                    list.setSelector(controlBackground());
                    list.setPadding(dp(8), dp(6), dp(8), dp(6));
                    list.setClipToPadding(false);
                }
                return dialog;
            }
        };
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

    @Override protected void onResume() { super.onResume(); if (backdrop != null) backdrop.start(); }
    @Override protected void onPause() { if (backdrop != null) backdrop.stop(); super.onPause(); }
    @Override protected void onDestroy() {
        if (backdrop != null) backdrop.close();
        ++generation; if (detailsDialog != null) detailsDialog.dismiss();
        io.shutdownNow(); posters.close(); if (ratings != null) ratings.close(); if (audience != null) audience.close(); super.onDestroy();
    }
}
