package de.thkoeln.mensadeutz;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.thkoeln.mensadeutz.MensaModels.DayMenu;
import de.thkoeln.mensadeutz.MensaModels.DietFilter;
import de.thkoeln.mensadeutz.MensaModels.Dish;
import de.thkoeln.mensadeutz.MensaModels.PriceTier;
import de.thkoeln.mensadeutz.MensaModels.WeekMenuResponse;

import static de.thkoeln.mensadeutz.ThemePalette.dp;

public class MainActivity extends Activity {

    private static final String PREFS_NAME = "mensa_deutz_prefs";
    private static final String KEY_DARK_MODE = "dark_mode";
    private static final String KEY_PRICE_TIER = "price_tier";
    private static final String KEY_LOCATION = "location_name";

    private static final ZoneId BERLIN_ZONE = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter SHORT_DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMANY);
    private static final DateTimeFormatter FULL_DATE_FMT = DateTimeFormatter.ofPattern("EEEE, dd. MMMM yyyy", Locale.GERMANY);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY);

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private boolean isDark;
    private ThemePalette palette;
    private PriceTier selectedPriceTier = PriceTier.STUDENT;
    private DietFilter selectedDietFilter = DietFilter.ALL;
    private String selectedLocation = MensaApiClient.DEFAULT_LOCATION;

    private WeekMenuResponse currentResponse;
    private int selectedDayIndex = 0;
    private boolean isLoading = false;

    // Tray calculator state: Dish ID -> Dish
    private final Map<String, Dish> trayDishes = new LinkedHashMap<>();

    // UI References
    private FrameLayout rootContainer;
    private LinearLayout mainColumn;
    private LinearLayout headerBox;
    private TextView headerTitleText;
    private TextView headerStatusText;
    private TextView priceTierButton;
    private TextView themeToggleButton;
    private TextView refreshButton;

    private HorizontalScrollView dayScrollView;
    private LinearLayout dayStripContainer;
    private final List<View> dayTabViews = new ArrayList<>();

    private HorizontalScrollView filterScrollView;
    private LinearLayout filterStripContainer;

    private ScrollView contentScrollView;
    private LinearLayout contentContainer;

    private LinearLayout trayBarContainer;
    private TextView trayBarTitle;
    private TextView trayBarSubtitle;
    private TextView trayBarClearBtn;

    private GestureDetector swipeGestureDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean sysDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        isDark = prefs.getBoolean(KEY_DARK_MODE, sysDark);
        palette = new ThemePalette(isDark);

        String savedTier = prefs.getString(KEY_PRICE_TIER, PriceTier.STUDENT.name());
        try {
            selectedPriceTier = PriceTier.valueOf(savedTier);
        } catch (Exception e) {
            selectedPriceTier = PriceTier.STUDENT;
        }
        selectedLocation = prefs.getString(KEY_LOCATION, MensaApiClient.DEFAULT_LOCATION);

        setupSwipeDetector();
        buildRootUi();
        applySystemBars();

        // 1. Load cached menu immediately for 0ms instant display
        WeekMenuResponse cached = MensaApiClient.loadCachedMenu(this, selectedLocation);
        if (cached != null && !cached.days.isEmpty()) {
            currentResponse = cached;
            selectedDayIndex = findBestInitialDayIndex(cached);
            renderAll();
        }

        // 2. Refresh live data from KStW CloudMensa API
        triggerRefresh(cached == null);
    }

    private void setupSwipeDetector() {
        swipeGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            private static final int SWIPE_THRESHOLD = 110;
            private static final int SWIPE_VELOCITY_THRESHOLD = 120;

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null || currentResponse == null) return false;
                float diffX = e2.getX() - e1.getX();
                float diffY = e2.getY() - e1.getY();
                if (Math.abs(diffX) > Math.abs(diffY) * 1.6f
                        && Math.abs(diffX) > SWIPE_THRESHOLD
                        && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX < 0 && selectedDayIndex < currentResponse.days.size() - 1) {
                        selectDay(selectedDayIndex + 1, true);
                        return true;
                    } else if (diffX > 0 && selectedDayIndex > 0) {
                        selectDay(selectedDayIndex - 1, true);
                        return true;
                    }
                }
                return false;
            }
        });
    }

    private void applySystemBars() {
        Window window = getWindow();
        if (window == null) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(palette.bgHeader);
        window.setNavigationBarColor(palette.bgWindow);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = window.getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (!isDark) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    private void buildRootUi() {
        rootContainer = new FrameLayout(this);
        rootContainer.setBackgroundColor(palette.bgWindow);

        mainColumn = new LinearLayout(this);
        mainColumn.setOrientation(LinearLayout.VERTICAL);
        rootContainer.addView(mainColumn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- TOP HEADER ---
        headerBox = new LinearLayout(this);
        headerBox.setOrientation(LinearLayout.VERTICAL);
        headerBox.setBackgroundColor(palette.bgHeader);
        headerBox.setElevation(dp(this, 3));
        headerBox.setPadding(dp(this, 16), dp(this, 12), dp(this, 16), dp(this, 10));
        mainColumn.addView(headerBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Top Row: Brand & Actions
        LinearLayout topBarRow = new LinearLayout(this);
        topBarRow.setOrientation(LinearLayout.HORIZONTAL);
        topBarRow.setGravity(Gravity.CENTER_VERTICAL);
        headerBox.addView(topBarRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // App Badge Icon
        TextView appIconBadge = new TextView(this);
        appIconBadge.setText("🍽️");
        appIconBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        appIconBadge.setGravity(Gravity.CENTER);
        appIconBadge.setBackground(ThemePalette.roundedRect(palette.accentPrimarySoft, dp(this, 12), dp(this, 1), palette.accentPrimary));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(this, 42), dp(this, 42));
        iconLp.rightMargin = dp(this, 12);
        topBarRow.addView(appIconBadge, iconLp);

        // Title & Live Status Column
        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setOnClickListener(v -> showLocationPickerDialog());
        LinearLayout.LayoutParams titleColLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        topBarRow.addView(titleCol, titleColLp);

        headerTitleText = new TextView(this);
        headerTitleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        headerTitleText.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        headerTitleText.setTextColor(palette.textPrimary);
        headerTitleText.setSingleLine(true);
        headerTitleText.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(headerTitleText);

        headerStatusText = new TextView(this);
        headerStatusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        headerStatusText.setTextColor(palette.textSecondary);
        headerStatusText.setSingleLine(true);
        headerStatusText.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(headerStatusText);

        // Action Buttons: Price Tier, Theme Toggle, Refresh
        priceTierButton = new TextView(this);
        priceTierButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        priceTierButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        priceTierButton.setPadding(dp(this, 10), dp(this, 7), dp(this, 10), dp(this, 7));
        priceTierButton.setGravity(Gravity.CENTER);
        priceTierButton.setOnClickListener(v -> cyclePriceTier());
        LinearLayout.LayoutParams tierLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tierLp.leftMargin = dp(this, 6);
        topBarRow.addView(priceTierButton, tierLp);

        themeToggleButton = new TextView(this);
        themeToggleButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        themeToggleButton.setGravity(Gravity.CENTER);
        themeToggleButton.setOnClickListener(v -> toggleTheme());
        LinearLayout.LayoutParams themeLp = new LinearLayout.LayoutParams(dp(this, 36), dp(this, 36));
        themeLp.leftMargin = dp(this, 6);
        topBarRow.addView(themeToggleButton, themeLp);

        refreshButton = new TextView(this);
        refreshButton.setText("↻");
        refreshButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        refreshButton.setTypeface(Typeface.DEFAULT_BOLD);
        refreshButton.setGravity(Gravity.CENTER);
        refreshButton.setOnClickListener(v -> triggerRefresh(false));
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(dp(this, 36), dp(this, 36));
        refreshLp.leftMargin = dp(this, 6);
        topBarRow.addView(refreshButton, refreshLp);

        // --- DAY SELECTOR STRIP (Heute & Nächste Tage) ---
        dayScrollView = new HorizontalScrollView(this);
        dayScrollView.setHorizontalScrollBarEnabled(false);
        dayScrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams dayScrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dayScrollLp.topMargin = dp(this, 12);
        headerBox.addView(dayScrollView, dayScrollLp);

        dayStripContainer = new LinearLayout(this);
        dayStripContainer.setOrientation(LinearLayout.HORIZONTAL);
        dayStripContainer.setGravity(Gravity.CENTER_VERTICAL);
        dayScrollView.addView(dayStripContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- DIET FILTER BAR ---
        filterScrollView = new HorizontalScrollView(this);
        filterScrollView.setHorizontalScrollBarEnabled(false);
        filterScrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams filterScrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        filterScrollLp.topMargin = dp(this, 10);
        headerBox.addView(filterScrollView, filterScrollLp);

        filterStripContainer = new LinearLayout(this);
        filterStripContainer.setOrientation(LinearLayout.HORIZONTAL);
        filterStripContainer.setGravity(Gravity.CENTER_VERTICAL);
        filterScrollView.addView(filterStripContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- MAIN SCROLLABLE CONTENT ---
        contentScrollView = new ScrollView(this) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                if (swipeGestureDetector != null) {
                    swipeGestureDetector.onTouchEvent(ev);
                }
                return super.dispatchTouchEvent(ev);
            }
        };
        contentScrollView.setFillViewport(true);
        contentScrollView.setVerticalScrollBarEnabled(false);
        mainColumn.addView(contentScrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        contentContainer = new LinearLayout(this);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setPadding(dp(this, 14), dp(this, 14), dp(this, 14), dp(this, 96));
        contentScrollView.addView(contentContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- FLOATING TRAY CALCULATOR BAR ---
        trayBarContainer = new LinearLayout(this);
        trayBarContainer.setOrientation(LinearLayout.HORIZONTAL);
        trayBarContainer.setGravity(Gravity.CENTER_VERTICAL);
        trayBarContainer.setPadding(dp(this, 16), dp(this, 12), dp(this, 14), dp(this, 12));
        trayBarContainer.setElevation(dp(this, 8));
        trayBarContainer.setVisibility(View.GONE);
        trayBarContainer.setOnClickListener(v -> showTraySummaryDialog());

        LinearLayout trayTextCol = new LinearLayout(this);
        trayTextCol.setOrientation(LinearLayout.VERTICAL);
        trayBarContainer.addView(trayTextCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        trayBarTitle = new TextView(this);
        trayBarTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        trayBarTitle.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        trayBarTitle.setTextColor(Color.WHITE);
        trayTextCol.addView(trayBarTitle);

        trayBarSubtitle = new TextView(this);
        trayBarSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        trayBarSubtitle.setTextColor(0xFFE2E8F0);
        trayBarSubtitle.setSingleLine(true);
        trayBarSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        trayTextCol.addView(trayBarSubtitle);

        trayBarClearBtn = new TextView(this);
        trayBarClearBtn.setText("Leeren");
        trayBarClearBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        trayBarClearBtn.setTypeface(Typeface.DEFAULT_BOLD);
        trayBarClearBtn.setTextColor(Color.WHITE);
        trayBarClearBtn.setPadding(dp(this, 12), dp(this, 6), dp(this, 12), dp(this, 6));
        trayBarClearBtn.setBackground(ThemePalette.roundedRect(0x33FFFFFF, dp(this, 8), dp(this, 1), 0x55FFFFFF));
        trayBarClearBtn.setOnClickListener(v -> {
            trayDishes.clear();
            updateTrayBar();
            renderContentSection();
        });
        trayBarContainer.addView(trayBarClearBtn);

        FrameLayout.LayoutParams trayLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trayLp.gravity = Gravity.BOTTOM;
        trayLp.setMargins(dp(this, 14), 0, dp(this, 14), dp(this, 14));
        rootContainer.addView(trayBarContainer, trayLp);

        setContentView(rootContainer);
        updateThemeStyles();
    }

    private void updateThemeStyles() {
        palette = new ThemePalette(isDark);
        applySystemBars();

        rootContainer.setBackgroundColor(palette.bgWindow);
        headerBox.setBackgroundColor(palette.bgHeader);
        headerTitleText.setTextColor(palette.textPrimary);
        headerStatusText.setTextColor(palette.textSecondary);

        priceTierButton.setText("💶 " + selectedPriceTier.shortLabel);
        priceTierButton.setTextColor(palette.textPrimary);
        priceTierButton.setBackground(ThemePalette.roundedRect(
                palette.bgChipUnselected, dp(this, 10), dp(this, 1), palette.borderSubtle));

        themeToggleButton.setText(isDark ? "☀️" : "🌙");
        themeToggleButton.setBackground(ThemePalette.roundedRect(
                palette.bgChipUnselected, dp(this, 10), dp(this, 1), palette.borderSubtle));

        refreshButton.setTextColor(palette.textPrimary);
        refreshButton.setBackground(ThemePalette.roundedRect(
                palette.bgChipUnselected, dp(this, 10), dp(this, 1), palette.borderSubtle));

        trayBarContainer.setBackground(ThemePalette.roundedRect(
                isDark ? 0xFFEA580C : 0xFF0F172A, dp(this, 16), dp(this, 1), palette.accentPrimary));

        updateHeaderTitleAndStatus();
    }

    private void updateHeaderTitleAndStatus() {
        String title = selectedLocation.equals("Mensa Deutz") ? "Mensa IWZ Deutz ▾" : selectedLocation + " ▾";
        headerTitleText.setText(title);

        ZonedDateTime nowBerlin = ZonedDateTime.now(BERLIN_ZONE);
        DayOfWeek dow = nowBerlin.getDayOfWeek();
        LocalTime time = nowBerlin.toLocalTime();
        boolean isWeekday = dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
        boolean isOpenNow = isWeekday && !time.isBefore(LocalTime.of(11, 30)) && !time.isAfter(LocalTime.of(14, 45));

        String openStatus = isOpenNow ? "🟢 Geöffnet (bis 14:45)" : "🕒 Mo–Fr 11:30–14:45 Uhr";
        if (isLoading) {
            headerStatusText.setText("⏳ Aktualisiere Speiseplan...");
        } else if (currentResponse != null) {
            String timeStr = Instant.ofEpochMilli(currentResponse.fetchedAtMillis)
                    .atZone(BERLIN_ZONE).toLocalTime().format(TIME_FMT);
            headerStatusText.setText(openStatus + " · Stand " + timeStr);
        } else {
            headerStatusText.setText(openStatus);
        }
    }

    private void toggleTheme() {
        isDark = !isDark;
        prefs.edit().putBoolean(KEY_DARK_MODE, isDark).apply();
        updateThemeStyles();
        renderAll();
    }

    private void cyclePriceTier() {
        PriceTier[] tiers = PriceTier.values();
        selectedPriceTier = tiers[(selectedPriceTier.ordinal() + 1) % tiers.length];
        prefs.edit().putString(KEY_PRICE_TIER, selectedPriceTier.name()).apply();
        updateThemeStyles();
        renderContentSection();
        updateTrayBar();
        Toast.makeText(this, "Preise für: " + selectedPriceTier.label, Toast.LENGTH_SHORT).show();
    }

    private void showLocationPickerDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(
                this, isDark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
        builder.setTitle("Standort wählen");
        int checked = 0;
        for (int i = 0; i < MensaApiClient.AVAILABLE_LOCATIONS.length; i++) {
            if (MensaApiClient.AVAILABLE_LOCATIONS[i].equalsIgnoreCase(selectedLocation)) {
                checked = i;
                break;
            }
        }
        builder.setSingleChoiceItems(MensaApiClient.AVAILABLE_LOCATIONS, checked, (dialog, which) -> {
            dialog.dismiss();
            String chosen = MensaApiClient.AVAILABLE_LOCATIONS[which];
            if (!chosen.equals(selectedLocation)) {
                selectedLocation = chosen;
                prefs.edit().putString(KEY_LOCATION, selectedLocation).apply();
                trayDishes.clear();
                updateTrayBar();
                updateHeaderTitleAndStatus();
                WeekMenuResponse cached = MensaApiClient.loadCachedMenu(this, selectedLocation);
                if (cached != null) {
                    currentResponse = cached;
                    selectedDayIndex = findBestInitialDayIndex(cached);
                    renderAll();
                }
                triggerRefresh(cached == null);
            }
        });
        builder.setNegativeButton("Abbrechen", null);
        builder.show();
    }

    private void triggerRefresh(boolean showFullScreenLoader) {
        if (isLoading) return;
        isLoading = true;
        updateHeaderTitleAndStatus();
        refreshButton.animate().rotationBy(360f).setDuration(600).start();

        if (showFullScreenLoader && currentResponse == null) {
            renderLoadingState();
        }

        final String locToFetch = selectedLocation;
        networkExecutor.execute(() -> {
            try {
                WeekMenuResponse fresh = MensaApiClient.fetchWeekMenu(getApplicationContext(), locToFetch);
                mainHandler.post(() -> {
                    isLoading = false;
                    // Preserve selected date if already chosen
                    String previousIso = null;
                    if (currentResponse != null && selectedDayIndex >= 0 && selectedDayIndex < currentResponse.days.size()) {
                        previousIso = currentResponse.days.get(selectedDayIndex).dateIso;
                    }
                    currentResponse = fresh;
                    int nextIdx = -1;
                    if (previousIso != null) {
                        for (int i = 0; i < fresh.days.size(); i++) {
                            if (previousIso.equals(fresh.days.get(i).dateIso)) {
                                nextIdx = i;
                                break;
                            }
                        }
                    }
                    if (nextIdx < 0) {
                        nextIdx = findBestInitialDayIndex(fresh);
                    }
                    selectedDayIndex = nextIdx;
                    updateHeaderTitleAndStatus();
                    renderAll();
                    MensaWidgetProvider.updateAllWidgets(getApplicationContext());
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    isLoading = false;
                    updateHeaderTitleAndStatus();
                    if (currentResponse == null) {
                        renderErrorState(e.getMessage());
                    } else {
                        Toast.makeText(MainActivity.this,
                                "Offline-Modus: Gespeicherter Speiseplan wird angezeigt", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private int findBestInitialDayIndex(WeekMenuResponse resp) {
        if (resp == null || resp.days.isEmpty()) return 0;
        // Prefer the first day (starting from today) that actually has dishes
        for (int i = 0; i < resp.days.size(); i++) {
            if (!resp.days.get(i).isEmpty()) {
                return i;
            }
        }
        return 0;
    }

    private void selectDay(int index, boolean smoothScrollContentTop) {
        if (currentResponse == null || index < 0 || index >= currentResponse.days.size()) return;
        selectedDayIndex = index;
        renderDayStrip();
        renderFilterStrip();
        renderContentSection();
        if (smoothScrollContentTop && contentScrollView != null) {
            contentScrollView.smoothScrollTo(0, 0);
        }
        scrollDayTabIntoView(index);
    }

    private void scrollDayTabIntoView(int index) {
        if (index < 0 || index >= dayTabViews.size() || dayScrollView == null) return;
        final View tab = dayTabViews.get(index);
        dayScrollView.post(() -> {
            int scrollX = tab.getLeft() - (dayScrollView.getWidth() - tab.getWidth()) / 2;
            dayScrollView.smoothScrollTo(Math.max(0, scrollX), 0);
        });
    }

    private void renderAll() {
        renderDayStrip();
        renderFilterStrip();
        renderContentSection();
        updateTrayBar();
        scrollDayTabIntoView(selectedDayIndex);
    }

    private void renderDayStrip() {
        dayStripContainer.removeAllViews();
        dayTabViews.clear();
        if (currentResponse == null || currentResponse.days.isEmpty()) return;

        LocalDate today = LocalDate.now(BERLIN_ZONE);
        try {
            today = LocalDate.parse(currentResponse.serverTodayIso);
        } catch (Exception ignored) {
        }

        for (int i = 0; i < currentResponse.days.size(); i++) {
            final int idx = i;
            DayMenu dayMenu = currentResponse.days.get(i);
            boolean isSelected = (i == selectedDayIndex);

            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(this, 14), dp(this, 8), dp(this, 14), dp(this, 8));

            int bg = isSelected ? palette.bgChipSelected : palette.bgChipUnselected;
            int border = isSelected ? palette.borderHighlight : palette.borderSubtle;
            tab.setBackground(ThemePalette.roundedRect(bg, dp(this, 12), dp(this, 1), border));

            LocalDate date = LocalDate.parse(dayMenu.dateIso);
            String topLabel;
            if (date.equals(today)) {
                topLabel = "HEUTE";
            } else if (date.equals(today.plusDays(1))) {
                topLabel = "MORGEN";
            } else {
                topLabel = getGermanShortWeekday(date.getDayOfWeek());
            }

            TextView tvDay = new TextView(this);
            tvDay.setText(topLabel);
            tvDay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tvDay.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
            tvDay.setTextColor(isSelected ? Color.WHITE : (date.equals(today) ? palette.accentPrimary : palette.textMuted));
            tab.addView(tvDay);

            TextView tvDate = new TextView(this);
            tvDate.setText(date.format(SHORT_DATE_FMT));
            tvDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tvDate.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            tvDate.setTextColor(isSelected ? Color.WHITE : palette.textPrimary);
            tab.addView(tvDate);

            TextView tvCount = new TextView(this);
            int count = dayMenu.getTotalDishCount();
            tvCount.setText(count > 0 ? (dayMenu.mainDishes.size() + " Haupt · " + (dayMenu.sideDishes.size() + dayMenu.desserts.size()) + " Beil.") : "Geschlossen");
            tvCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            tvCount.setTextColor(isSelected ? 0xCCF8FAFC : palette.textMuted);
            tab.addView(tvCount);

            tab.setOnClickListener(v -> selectDay(idx, true));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(this, 8);
            dayStripContainer.addView(tab, lp);
            dayTabViews.add(tab);
        }
    }

    private void renderFilterStrip() {
        filterStripContainer.removeAllViews();
        if (currentResponse == null || currentResponse.days.isEmpty()) return;

        DayMenu day = currentResponse.days.get(selectedDayIndex);

        for (DietFilter filter : DietFilter.values()) {
            boolean isSelected = (filter == selectedDietFilter);
            int matchCount = countMatchingDishes(day, filter);

            TextView chip = new TextView(this);
            String prefix = "";
            if (filter == DietFilter.VEGAN) prefix = "🌱 ";
            else if (filter == DietFilter.VEGETARIAN) prefix = "🧀 ";
            else if (filter == DietFilter.MEAT_FISH) prefix = "🍗 ";

            chip.setText(prefix + filter.label + " (" + matchCount + ")");
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chip.setTypeface(Typeface.create("sans-serif-medium", isSelected ? Typeface.BOLD : Typeface.NORMAL));
            chip.setPadding(dp(this, 12), dp(this, 6), dp(this, 12), dp(this, 6));

            int bg = isSelected ? (isDark ? 0xFF334155 : 0xFF0F172A) : palette.bgChipUnselected;
            int fg = isSelected ? Color.WHITE : palette.textSecondary;
            int stroke = isSelected ? (isDark ? 0xFF64748B : 0xFF0F172A) : palette.borderSubtle;
            chip.setBackground(ThemePalette.roundedRect(bg, dp(this, 20), dp(this, 1), stroke));
            chip.setTextColor(fg);

            chip.setOnClickListener(v -> {
                selectedDietFilter = filter;
                renderFilterStrip();
                renderContentSection();
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (filter.ordinal() > 0) lp.leftMargin = dp(this, 8);
            filterStripContainer.addView(chip, lp);
        }
    }

    private int countMatchingDishes(DayMenu day, DietFilter filter) {
        int c = 0;
        for (Dish d : day.mainDishes) if (d.matchesFilter(filter)) c++;
        for (Dish d : day.sideDishes) if (d.matchesFilter(filter)) c++;
        for (Dish d : day.desserts) if (d.matchesFilter(filter)) c++;
        return c;
    }

    private void renderLoadingState() {
        contentContainer.removeAllViews();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(this, 24), dp(this, 72), dp(this, 24), dp(this, 72));

        ProgressBar pb = new ProgressBar(this);
        box.addView(pb, new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));

        TextView tv = new TextView(this);
        tv.setText("Lade aktuellen Speiseplan der " + selectedLocation + "...");
        tv.setTextColor(palette.textSecondary);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 16);
        box.addView(tv, lp);

        contentContainer.addView(box);
    }

    private void renderErrorState(String errorMsg) {
        contentContainer.removeAllViews();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(this, 24), dp(this, 48), dp(this, 24), dp(this, 48));
        box.setBackground(ThemePalette.roundedRect(palette.bgCard, dp(this, 16), dp(this, 1), palette.borderSubtle));

        TextView icon = new TextView(this);
        icon.setText("📡");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
        box.addView(icon);

        TextView title = new TextView(this);
        title.setText("Speiseplan konnte nicht geladen werden");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(palette.textPrimary);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = dp(this, 12);
        box.addView(title, tLp);

        TextView sub = new TextView(this);
        sub.setText("Bitte überprüfe deine Internetverbindung.\n(" + (errorMsg != null ? errorMsg : "Verbindungsfehler") + ")");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setTextColor(palette.textSecondary);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(this, 6);
        box.addView(sub, sLp);

        TextView retryBtn = new TextView(this);
        retryBtn.setText("Erneut versuchen");
        retryBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        retryBtn.setTypeface(Typeface.DEFAULT_BOLD);
        retryBtn.setTextColor(Color.WHITE);
        retryBtn.setPadding(dp(this, 20), dp(this, 10), dp(this, 20), dp(this, 10));
        retryBtn.setBackground(ThemePalette.roundedRect(palette.accentPrimary, dp(this, 12), 0, 0));
        retryBtn.setOnClickListener(v -> triggerRefresh(true));
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bLp.topMargin = dp(this, 18);
        box.addView(retryBtn, bLp);

        contentContainer.addView(box);
    }

    private void renderContentSection() {
        contentContainer.removeAllViews();
        if (currentResponse == null || currentResponse.days.isEmpty()) return;

        DayMenu day = currentResponse.days.get(selectedDayIndex);
        LocalDate parsedDate = LocalDate.parse(day.dateIso);

        // --- Date Banner Header with Prev / Next Day Arrows ---
        LinearLayout dateBanner = new LinearLayout(this);
        dateBanner.setOrientation(LinearLayout.HORIZONTAL);
        dateBanner.setGravity(Gravity.CENTER_VERTICAL);
        dateBanner.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
        dateBanner.setBackground(ThemePalette.roundedRect(palette.bgCard, dp(this, 14), dp(this, 1), palette.borderSubtle));

        TextView prevBtn = new TextView(this);
        prevBtn.setText("◀");
        prevBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        prevBtn.setGravity(Gravity.CENTER);
        prevBtn.setTextColor(selectedDayIndex > 0 ? palette.textPrimary : palette.textMuted);
        prevBtn.setAlpha(selectedDayIndex > 0 ? 1f : 0.35f);
        prevBtn.setBackground(ThemePalette.roundedRect(palette.bgChipUnselected, dp(this, 8), 0, 0));
        prevBtn.setOnClickListener(v -> {
            if (selectedDayIndex > 0) selectDay(selectedDayIndex - 1, true);
        });
        dateBanner.addView(prevBtn, new LinearLayout.LayoutParams(dp(this, 34), dp(this, 34)));

        LinearLayout dateCenterCol = new LinearLayout(this);
        dateCenterCol.setOrientation(LinearLayout.VERTICAL);
        dateCenterCol.setGravity(Gravity.CENTER);
        dateBanner.addView(dateCenterCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView fullDateTv = new TextView(this);
        fullDateTv.setText(capitalize(parsedDate.format(FULL_DATE_FMT)));
        fullDateTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        fullDateTv.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        fullDateTv.setTextColor(palette.textPrimary);
        fullDateTv.setGravity(Gravity.CENTER);
        dateCenterCol.addView(fullDateTv);

        TextView subHintTv = new TextView(this);
        subHintTv.setText("Preise für " + selectedPriceTier.label + " · Tippe auf ein Gericht für Allergene");
        subHintTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        subHintTv.setTextColor(palette.textMuted);
        subHintTv.setGravity(Gravity.CENTER);
        dateCenterCol.addView(subHintTv);

        TextView nextBtn = new TextView(this);
        nextBtn.setText("▶");
        nextBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        nextBtn.setGravity(Gravity.CENTER);
        boolean hasNext = selectedDayIndex < currentResponse.days.size() - 1;
        nextBtn.setTextColor(hasNext ? palette.textPrimary : palette.textMuted);
        nextBtn.setAlpha(hasNext ? 1f : 0.35f);
        nextBtn.setBackground(ThemePalette.roundedRect(palette.bgChipUnselected, dp(this, 8), 0, 0));
        nextBtn.setOnClickListener(v -> {
            if (selectedDayIndex < currentResponse.days.size() - 1) selectDay(selectedDayIndex + 1, true);
        });
        dateBanner.addView(nextBtn, new LinearLayout.LayoutParams(dp(this, 34), dp(this, 34)));

        contentContainer.addView(dateBanner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (day.isEmpty()) {
            renderEmptyDayCard(parsedDate);
            return;
        }

        // Filter dishes according to selectedDietFilter
        List<Dish> filteredMains = new ArrayList<>();
        for (Dish d : day.mainDishes) if (d.matchesFilter(selectedDietFilter)) filteredMains.add(d);

        List<Dish> filteredSides = new ArrayList<>();
        for (Dish d : day.sideDishes) if (d.matchesFilter(selectedDietFilter)) filteredSides.add(d);

        List<Dish> filteredDesserts = new ArrayList<>();
        for (Dish d : day.desserts) if (d.matchesFilter(selectedDietFilter)) filteredDesserts.add(d);

        // --- SECTION 1: HAUPTSPEISEN ---
        addSectionHeader("🍛 Hauptspeisen", filteredMains.size() + (filteredMains.size() == 1 ? " Gericht" : " Gerichte"),
                "Wähle dein Hauptgericht an den Ausgabetheken");

        if (filteredMains.isEmpty()) {
            addNoFilterMatchCard("Keine Hauptspeisen für den Filter \"" + selectedDietFilter.label + "\" an diesem Tag.");
        } else {
            for (Dish mainDish : filteredMains) {
                contentContainer.addView(buildMainDishCard(mainDish));
            }
        }

        // --- SECTION 2: BEILAGEN ---
        addSectionHeader("🍟 Mögliche Beilagen", filteredSides.size() + (filteredSides.size() == 1 ? " Beilage" : " Beilagen"),
                "Frei kombinierbare Beilagen & Salate des Tages");

        if (filteredSides.isEmpty()) {
            addNoFilterMatchCard("Keine Beilagen für diesen Filter verfügbar.");
        } else {
            contentContainer.addView(buildSideDishesGrid(filteredSides));
        }

        // --- SECTION 3: DESSERTS (if any) ---
        if (!filteredDesserts.isEmpty()) {
            addSectionHeader("🍮 Desserts & Süßes", filteredDesserts.size() + (filteredDesserts.size() == 1 ? " Dessert" : " Desserts"),
                    "Aus dem Beilagen- und Dessertpool");
            contentContainer.addView(buildSideDishesGrid(filteredDesserts));
        }
    }

    private void renderEmptyDayCard(LocalDate date) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(this, 24), dp(this, 40), dp(this, 24), dp(this, 40));
        card.setBackground(ThemePalette.roundedRect(palette.bgCard, dp(this, 16), dp(this, 1), palette.borderSubtle));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 16);

        TextView emoji = new TextView(this);
        emoji.setText("☕");
        emoji.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
        card.addView(emoji);

        TextView title = new TextView(this);
        title.setText("Keine Speisen für diesen Tag gemeldet");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(palette.textPrimary);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = dp(this, 10);
        card.addView(title, tLp);

        TextView sub = new TextView(this);
        sub.setText("Die Mensa Deutz hat Mo–Fr von 11:30 bis 14:45 Uhr geöffnet. Wähle oben einfach den nächsten offenen Tag aus.");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setTextColor(palette.textSecondary);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(this, 6);
        card.addView(sub, sLp);

        contentContainer.addView(card, lp);
    }

    private void addSectionHeader(String title, String countBadge, String subtitle) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 18);
        lp.bottomMargin = dp(this, 8);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        wrapper.addView(topRow);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17.5f);
        titleTv.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        titleTv.setTextColor(palette.textPrimary);
        topRow.addView(titleTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badgeTv = new TextView(this);
        badgeTv.setText(countBadge);
        badgeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        badgeTv.setTypeface(Typeface.DEFAULT_BOLD);
        badgeTv.setTextColor(palette.accentPrimary);
        badgeTv.setPadding(dp(this, 10), dp(this, 4), dp(this, 10), dp(this, 4));
        badgeTv.setBackground(ThemePalette.roundedRect(palette.accentPrimarySoft, dp(this, 12), dp(this, 1), palette.accentPrimary));
        topRow.addView(badgeTv);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subTv = new TextView(this);
            subTv.setText(subtitle);
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            subTv.setTextColor(palette.textMuted);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sLp.topMargin = dp(this, 2);
            wrapper.addView(subTv, sLp);
        }

        contentContainer.addView(wrapper, lp);
    }

    private void addNoFilterMatchCard(String message) {
        TextView tv = new TextView(this);
        tv.setText(message);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(palette.textSecondary);
        tv.setPadding(dp(this, 16), dp(this, 16), dp(this, 16), dp(this, 16));
        tv.setBackground(ThemePalette.roundedRect(palette.bgCard, dp(this, 12), dp(this, 1), palette.borderSubtle));
        contentContainer.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View buildMainDishCard(final Dish dish) {
        boolean inTray = trayDishes.containsKey(dish.id);
        int categoryColor = ThemePalette.getCategoryAccentColor(dish, isDark);

        LinearLayout cardOuter = new LinearLayout(this);
        cardOuter.setOrientation(LinearLayout.HORIZONTAL);
        cardOuter.setBackground(ThemePalette.roundedRect(
                palette.bgCard,
                dp(this, 16),
                dp(this, inTray ? 2 : 1),
                inTray ? palette.accentPrimary : palette.borderSubtle));
        cardOuter.setElevation(dp(this, 2));
        cardOuter.setOnClickListener(v -> showDishDetailDialog(dish));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(this, 10);
        cardOuter.setLayoutParams(cardLp);

        // Left Category Color Accent Strip
        View leftStrip = new View(this);
        GradientDrawable stripDrawable = new GradientDrawable();
        stripDrawable.setShape(GradientDrawable.RECTANGLE);
        stripDrawable.setColor(categoryColor);
        float r = dp(this, 16);
        stripDrawable.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        leftStrip.setBackground(stripDrawable);
        cardOuter.addView(leftStrip, new LinearLayout.LayoutParams(dp(this, 6), ViewGroup.LayoutParams.MATCH_PARENT));

        // Inner Body
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        cardOuter.addView(body, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Top Row: Category Label + Theke + SoldOut + Price Badge
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(topRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView catBadge = new TextView(this);
        catBadge.setText(dish.getCategoryDisplayLabel().toUpperCase(Locale.GERMANY));
        catBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        catBadge.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        catBadge.setTextColor(categoryColor);
        topRow.addView(catBadge);

        if (dish.counterLocation != null && !dish.counterLocation.isEmpty()) {
            TextView counterBadge = new TextView(this);
            counterBadge.setText(" · 📍 " + dish.counterLocation);
            counterBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            counterBadge.setTextColor(palette.textMuted);
            topRow.addView(counterBadge);
        }

        if (dish.isSoldOut) {
            TextView soldOutBadge = new TextView(this);
            soldOutBadge.setText("AUSVERKAUFT");
            soldOutBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            soldOutBadge.setTypeface(Typeface.DEFAULT_BOLD);
            soldOutBadge.setTextColor(0xFFEF4444);
            soldOutBadge.setPadding(dp(this, 6), dp(this, 2), dp(this, 6), dp(this, 2));
            soldOutBadge.setBackground(ThemePalette.roundedRect(isDark ? 0xFF450A0A : 0xFFFEE2E2, dp(this, 6), 0, 0));
            LinearLayout.LayoutParams soLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            soLp.leftMargin = dp(this, 6);
            topRow.addView(soldOutBadge, soLp);
        }

        View spacer = new View(this);
        topRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        // Prominent Price Tag
        TextView priceBadge = new TextView(this);
        priceBadge.setText(dish.getFormattedPrice(selectedPriceTier));
        priceBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        priceBadge.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        priceBadge.setTextColor(palette.textPrimary);
        priceBadge.setPadding(dp(this, 10), dp(this, 4), dp(this, 10), dp(this, 4));
        priceBadge.setBackground(ThemePalette.roundedRect(palette.bgCardSubtle, dp(this, 10), dp(this, 1), palette.borderSubtle));
        topRow.addView(priceBadge);

        // Middle Row: Title + Components on Left, Dish Thumbnail on Right
        LinearLayout midRow = new LinearLayout(this);
        midRow.setOrientation(LinearLayout.HORIZONTAL);
        midRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        midLp.topMargin = dp(this, 8);
        body.addView(midRow, midLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        midRow.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleTv = new TextView(this);
        titleTv.setText(dish.titleDe);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        titleTv.setTextColor(dish.isSoldOut ? palette.textMuted : palette.textPrimary);
        textCol.addView(titleTv);

        if (dish.subtitleDe != null && !dish.subtitleDe.isEmpty()) {
            TextView subTv = new TextView(this);
            subTv.setText("Dazu: " + dish.subtitleDe);
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            subTv.setTextColor(palette.textSecondary);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(this, 4);
            textCol.addView(subTv, subLp);
        }

        // Optional Dish Photo Thumbnail
        if (dish.imageUrl != null && !dish.imageUrl.isEmpty()) {
            final int imgSize = dp(this, 68);
            final int radius = dp(this, 12);
            ImageView imgView = new ImageView(this);
            imgView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            imgView.setBackground(ThemePalette.roundedRect(palette.bgCardSubtle, radius, dp(this, 1), palette.borderSubtle));
            imgView.setClipToOutline(true);
            imgView.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(imgSize, imgSize);
            imgLp.leftMargin = dp(this, 12);
            midRow.addView(imgView, imgLp);
            ImageLoader.load(this, dish.imageUrl, imgView, imgSize * 2, null);
        }

        // Bottom Row: Food Badges + All Prices Info + Add-to-Tray Button
        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams botLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        botLp.topMargin = dp(this, 10);
        body.addView(bottomRow, botLp);

        LinearLayout badgesContainer = new LinearLayout(this);
        badgesContainer.setOrientation(LinearLayout.HORIZONTAL);
        badgesContainer.setGravity(Gravity.CENTER_VERTICAL);
        bottomRow.addView(badgesContainer, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        for (String iconCode : dish.foodIcons) {
            int[] colors = ThemePalette.getBadgeColorsForIcon(iconCode, isDark);
            TextView badge = new TextView(this);
            badge.setText(ThemePalette.getBadgeLabelForIcon(iconCode));
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            badge.setTextColor(colors[1]);
            badge.setPadding(dp(this, 8), dp(this, 3), dp(this, 8), dp(this, 3));
            badge.setBackground(ThemePalette.roundedRect(colors[0], dp(this, 8), 0, 0));
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bLp.rightMargin = dp(this, 6);
            badgesContainer.addView(badge, bLp);
        }

        // Add to Tray Calculator Button
        TextView trayBtn = new TextView(this);
        trayBtn.setText(inTray ? "✓ Im Tablett" : "+ Tablett");
        trayBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        trayBtn.setTypeface(Typeface.DEFAULT_BOLD);
        trayBtn.setTextColor(inTray ? Color.WHITE : palette.accentPrimary);
        trayBtn.setPadding(dp(this, 10), dp(this, 5), dp(this, 10), dp(this, 5));
        trayBtn.setBackground(ThemePalette.roundedRect(
                inTray ? palette.accentPrimary : palette.accentPrimarySoft,
                dp(this, 10),
                dp(this, 1),
                palette.accentPrimary));
        trayBtn.setOnClickListener(v -> toggleDishInTray(dish));
        bottomRow.addView(trayBtn);

        return cardOuter;
    }

    private View buildSideDishesGrid(List<Dish> dishes) {
        LinearLayout gridContainer = new LinearLayout(this);
        gridContainer.setOrientation(LinearLayout.VERTICAL);

        for (int i = 0; i < dishes.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(this, 8);
            gridContainer.addView(row, rowLp);

            Dish leftDish = dishes.get(i);
            View leftCard = buildCompactSideCard(leftDish);
            LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            leftLp.rightMargin = dp(this, 4);
            row.addView(leftCard, leftLp);

            if (i + 1 < dishes.size()) {
                Dish rightDish = dishes.get(i + 1);
                View rightCard = buildCompactSideCard(rightDish);
                LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                rightLp.leftMargin = dp(this, 4);
                row.addView(rightCard, rightLp);
            } else {
                View emptySlot = new View(this);
                LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, 1, 1f);
                rightLp.leftMargin = dp(this, 4);
                row.addView(emptySlot, rightLp);
            }
        }
        return gridContainer;
    }

    private View buildCompactSideCard(final Dish dish) {
        boolean inTray = trayDishes.containsKey(dish.id);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        card.setBackground(ThemePalette.roundedRect(
                palette.bgCard,
                dp(this, 14),
                dp(this, inTray ? 2 : 1),
                inTray ? palette.accentPrimary : palette.borderSubtle));
        card.setElevation(dp(this, 1));
        card.setOnClickListener(v -> showDishDetailDialog(dish));

        // Top Row: Emoji/Diet Badge + Price
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(topRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String dietIcon = dish.isVegan() ? "🌱 Vegan" : (dish.isVegetarian() ? "🧀 Veggie" : "🍽️ Beilage");
        int[] badgeColors = ThemePalette.getBadgeColorsForIcon(dish.isVegan() ? "VGN" : (dish.isVegetarian() ? "VGT" : ""), isDark);

        TextView dietBadge = new TextView(this);
        dietBadge.setText(dietIcon);
        dietBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        dietBadge.setTypeface(Typeface.DEFAULT_BOLD);
        dietBadge.setTextColor(badgeColors[1]);
        dietBadge.setPadding(dp(this, 6), dp(this, 2), dp(this, 6), dp(this, 2));
        dietBadge.setBackground(ThemePalette.roundedRect(badgeColors[0], dp(this, 6), 0, 0));
        topRow.addView(dietBadge);

        View spacer = new View(this);
        topRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView priceTv = new TextView(this);
        priceTv.setText(dish.getFormattedPrice(selectedPriceTier));
        priceTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        priceTv.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        priceTv.setTextColor(palette.textPrimary);
        topRow.addView(priceTv);

        // Middle: Beilage Title
        TextView titleTv = new TextView(this);
        titleTv.setText(getSideDishEmoji(dish) + " " + dish.titleDe);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        titleTv.setTextColor(palette.textPrimary);
        titleTv.setMaxLines(2);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        titleLp.topMargin = dp(this, 8);
        titleLp.bottomMargin = dp(this, 8);
        card.addView(titleTv, titleLp);

        // Bottom Row: Add to Tray Pill
        TextView addBtn = new TextView(this);
        addBtn.setText(inTray ? "✓ Im Tablett" : "+ Zum Tablett");
        addBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        addBtn.setTypeface(Typeface.DEFAULT_BOLD);
        addBtn.setGravity(Gravity.CENTER);
        addBtn.setTextColor(inTray ? Color.WHITE : palette.textSecondary);
        addBtn.setPadding(dp(this, 8), dp(this, 4), dp(this, 8), dp(this, 4));
        addBtn.setBackground(ThemePalette.roundedRect(
                inTray ? palette.accentPrimary : palette.bgChipUnselected,
                dp(this, 8),
                0,
                0));
        addBtn.setOnClickListener(v -> toggleDishInTray(dish));
        card.addView(addBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return card;
    }

    private void toggleDishInTray(Dish dish) {
        if (trayDishes.containsKey(dish.id)) {
            trayDishes.remove(dish.id);
        } else {
            trayDishes.put(dish.id, dish);
        }
        updateTrayBar();
        renderContentSection();
    }

    private void updateTrayBar() {
        if (trayDishes.isEmpty()) {
            trayBarContainer.setVisibility(View.GONE);
            return;
        }
        trayBarContainer.setVisibility(View.VISIBLE);
        double sum = 0.0;
        List<String> names = new ArrayList<>();
        for (Dish d : trayDishes.values()) {
            sum += d.getPrice(selectedPriceTier);
            names.add(d.titleDe);
        }
        String formattedTotal = String.format(Locale.GERMANY, "%.2f €", sum);
        trayBarTitle.setText("🍽️ Dein Tablett (" + trayDishes.size() + ") · " + formattedTotal);
        trayBarSubtitle.setText(TextUtils.join(" + ", names));
    }

    private void showTraySummaryDialog() {
        if (trayDishes.isEmpty()) return;
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(this, 20), dp(this, 20), dp(this, 20), dp(this, 20));
        card.setBackground(ThemePalette.roundedRect(palette.bgCard, dp(this, 20), dp(this, 1), palette.borderSubtle));

        TextView title = new TextView(this);
        title.setText("🍽️ Dein Mensa-Tablett");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        title.setTextColor(palette.textPrimary);
        card.addView(title);

        double sumStud = 0, sumEmp = 0, sumGuest = 0;
        for (Dish d : trayDishes.values()) {
            sumStud += d.priceStudent;
            sumEmp += d.getPrice(PriceTier.EMPLOYEE);
            sumGuest += d.getPrice(PriceTier.GUEST);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.topMargin = dp(this, 10);
            card.addView(row, rLp);

            TextView nameTv = new TextView(this);
            nameTv.setText("• " + d.titleDe);
            nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            nameTv.setTextColor(palette.textPrimary);
            row.addView(nameTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView pTv = new TextView(this);
            pTv.setText(d.getFormattedPrice(selectedPriceTier));
            pTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            pTv.setTypeface(Typeface.DEFAULT_BOLD);
            pTv.setTextColor(palette.accentPrimary);
            row.addView(pTv);
        }

        View divider = new View(this);
        divider.setBackgroundColor(palette.borderSubtle);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 1));
        dLp.topMargin = dp(this, 14);
        dLp.bottomMargin = dp(this, 12);
        card.addView(divider, dLp);

        TextView totalTv = new TextView(this);
        totalTv.setText(String.format(Locale.GERMANY,
                "Gesamt (%s): %.2f €\nStudierende: %.2f € · Bedienstete: %.2f € · Gäste: %.2f €",
                selectedPriceTier.label,
                selectedPriceTier == PriceTier.EMPLOYEE ? sumEmp : (selectedPriceTier == PriceTier.GUEST ? sumGuest : sumStud),
                sumStud, sumEmp, sumGuest));
        totalTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        totalTv.setTextColor(palette.textSecondary);
        card.addView(totalTv);

        TextView closeBtn = new TextView(this);
        closeBtn.setText("Schließen");
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        closeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding(dp(this, 16), dp(this, 10), dp(this, 16), dp(this, 10));
        closeBtn.setBackground(ThemePalette.roundedRect(palette.accentPrimary, dp(this, 12), 0, 0));
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = dp(this, 16);
        card.addView(closeBtn, cLp);

        dialog.setContentView(card);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.90f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void showDishDetailDialog(final Dish dish) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(this, 20), dp(this, 20), dp(this, 20), dp(this, 20));
        sheet.setBackground(ThemePalette.roundedRect(palette.bgCard, dp(this, 22), dp(this, 1), palette.borderSubtle));
        scroll.addView(sheet);

        // Optional Large Image
        if (dish.imageUrl != null && !dish.imageUrl.isEmpty()) {
            final int radius = dp(this, 14);
            ImageView heroImg = new ImageView(this);
            heroImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            heroImg.setBackground(ThemePalette.roundedRect(palette.bgCardSubtle, radius, 0, 0));
            heroImg.setClipToOutline(true);
            heroImg.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 180));
            imgLp.bottomMargin = dp(this, 14);
            sheet.addView(heroImg, imgLp);
            ImageLoader.load(this, dish.imageUrl, heroImg, dp(this, 360), null);
        }

        // Category + Counter
        TextView catTv = new TextView(this);
        String catLine = dish.getCategoryDisplayLabel().toUpperCase(Locale.GERMANY);
        if (dish.counterLocation != null && !dish.counterLocation.isEmpty()) {
            catLine += " · 📍 Mensa Deutz " + dish.counterLocation;
        }
        catTv.setText(catLine);
        catTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        catTv.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        catTv.setTextColor(ThemePalette.getCategoryAccentColor(dish, isDark));
        sheet.addView(catTv);

        // Dish Title DE
        TextView titleTv = new TextView(this);
        titleTv.setText(dish.titleDe);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        titleTv.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        titleTv.setTextColor(palette.textPrimary);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = dp(this, 4);
        sheet.addView(titleTv, tLp);

        if (dish.subtitleDe != null && !dish.subtitleDe.isEmpty()) {
            TextView compTv = new TextView(this);
            compTv.setText("Dazu: " + dish.subtitleDe);
            compTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            compTv.setTextColor(palette.textSecondary);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.topMargin = dp(this, 4);
            sheet.addView(compTv, cLp);
        }

        if (dish.titleEn != null && !dish.titleEn.isEmpty()) {
            TextView enTv = new TextView(this);
            String enText = "🇬🇧 " + dish.titleEn;
            if (dish.subtitleEn != null && !dish.subtitleEn.isEmpty()) {
                enText += " (" + dish.subtitleEn + ")";
            }
            enTv.setText(enText);
            enTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            enTv.setTextColor(palette.textMuted);
            LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            eLp.topMargin = dp(this, 6);
            sheet.addView(enTv, eLp);
        }

        // Price Table Box (Studierende / Bedienstete / Gäste)
        LinearLayout priceBox = new LinearLayout(this);
        priceBox.setOrientation(LinearLayout.HORIZONTAL);
        priceBox.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        priceBox.setBackground(ThemePalette.roundedRect(palette.bgCardSubtle, dp(this, 12), dp(this, 1), palette.borderSubtle));
        LinearLayout.LayoutParams pBoxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pBoxLp.topMargin = dp(this, 14);
        sheet.addView(priceBox, pBoxLp);

        addPriceColumn(priceBox, "Studierende", dish.getFormattedPrice(PriceTier.STUDENT), selectedPriceTier == PriceTier.STUDENT);
        addPriceColumn(priceBox, "Bedienstete", dish.getFormattedPrice(PriceTier.EMPLOYEE), selectedPriceTier == PriceTier.EMPLOYEE);
        addPriceColumn(priceBox, "Gäste", dish.getFormattedPrice(PriceTier.GUEST), selectedPriceTier == PriceTier.GUEST);

        // Allergens & Additives Section
        TextView allergHeader = new TextView(this);
        allergHeader.setText("Allergene & Zusatzstoffe");
        allergHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        allergHeader.setTypeface(Typeface.DEFAULT_BOLD);
        allergHeader.setTextColor(palette.textPrimary);
        LinearLayout.LayoutParams ahLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ahLp.topMargin = dp(this, 14);
        sheet.addView(allergHeader, ahLp);

        TextView allergBody = new TextView(this);
        if (dish.allergensList.isEmpty()) {
            allergBody.setText("✓ Keine deklarationspflichtigen Allergene oder Zusatzstoffe angegeben.");
            allergBody.setTextColor(palette.accentGreen);
        } else {
            allergBody.setText("• " + TextUtils.join("\n• ", dish.allergensList));
            allergBody.setTextColor(palette.textSecondary);
        }
        allergBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        abLp.topMargin = dp(this, 6);
        sheet.addView(allergBody, abLp);

        // Close Button
        TextView closeBtn = new TextView(this);
        closeBtn.setText("Fertig");
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        closeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding(dp(this, 16), dp(this, 11), dp(this, 16), dp(this, 11));
        closeBtn.setBackground(ThemePalette.roundedRect(palette.accentPrimary, dp(this, 12), 0, 0));
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = dp(this, 18);
        sheet.addView(closeBtn, cLp);

        dialog.setContentView(scroll);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void addPriceColumn(LinearLayout parent, String label, String value, boolean highlight) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        parent.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        lbl.setTextColor(highlight ? palette.accentPrimary : palette.textMuted);
        lbl.setTypeface(highlight ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        col.addView(lbl);

        TextView val = new TextView(this);
        val.setText(value);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        val.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        val.setTextColor(highlight ? palette.accentPrimary : palette.textPrimary);
        col.addView(val);
    }

    private static String getSideDishEmoji(Dish dish) {
        if (dish.isDessert) return "🍮";
        String t = dish.titleDe.toLowerCase(Locale.ROOT);
        if (t.contains("pommes") || t.contains("wedges")) return "🍟";
        if (t.contains("kartoffel") || t.contains("drillinge") || t.contains("kroketten") || t.contains("püree")) return "🥔";
        if (t.contains("reis")) return "🍚";
        if (t.contains("salat")) return "🥗";
        if (t.contains("gemüse") || t.contains("brokkoli") || t.contains("blumenkohl") || t.contains("rosenkohl") || t.contains("erbsen") || t.contains("karotten") || t.contains("bohnen") || t.contains("rotkohl")) return "🥦";
        if (t.contains("nudel") || t.contains("pasta") || t.contains("spätzle")) return "🍝";
        return "🥣";
    }

    private static String getGermanShortWeekday(DayOfWeek dow) {
        switch (dow) {
            case MONDAY:
                return "MO";
            case TUESDAY:
                return "DI";
            case WEDNESDAY:
                return "MI";
            case THURSDAY:
                return "DO";
            case FRIDAY:
                return "FR";
            case SATURDAY:
                return "SA";
            case SUNDAY:
                return "SO";
            default:
                return "";
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
