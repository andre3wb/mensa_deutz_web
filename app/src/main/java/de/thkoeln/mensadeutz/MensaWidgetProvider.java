package de.thkoeln.mensadeutz;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

import de.thkoeln.mensadeutz.MensaModels.DayMenu;
import de.thkoeln.mensadeutz.MensaModels.Dish;
import de.thkoeln.mensadeutz.MensaModels.PriceTier;
import de.thkoeln.mensadeutz.MensaModels.WeekMenuResponse;

public class MensaWidgetProvider extends AppWidgetProvider {

    private static final String PREFS_NAME = "mensa_deutz_prefs";
    private static final String KEY_PRICE_TIER = "price_tier";
    private static final String KEY_LOCATION = "location_name";
    private static final String KEY_WIDGET_DAY_OFFSET = "widget_day_offset_";
    private static final String KEY_WIDGET_DISH_INDEX = "widget_dish_index_";

    public static final String ACTION_PREV_DAY = "de.thkoeln.mensadeutz.ACTION_PREV_DAY";
    public static final String ACTION_NEXT_DAY = "de.thkoeln.mensadeutz.ACTION_NEXT_DAY";
    public static final String ACTION_NEXT_DISH = "de.thkoeln.mensadeutz.ACTION_NEXT_DISH";
    public static final String ACTION_REFRESH = "de.thkoeln.mensadeutz.ACTION_REFRESH";

    private static final ZoneId BERLIN_ZONE = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter SHORT_DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMANY);

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        updateAllWidgets(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager, int appWidgetId, Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
        updateSingleWidget(context, appWidgetManager, appWidgetId);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (action == null) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);

        if (ACTION_PREV_DAY.equals(action)) {
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                int offset = prefs.getInt(KEY_WIDGET_DAY_OFFSET + appWidgetId, 0);
                if (offset > 0) {
                    prefs.edit()
                            .putInt(KEY_WIDGET_DAY_OFFSET + appWidgetId, offset - 1)
                            .putInt(KEY_WIDGET_DISH_INDEX + appWidgetId, 0)
                            .apply();
                }
            }
            updateAllWidgets(context);
        } else if (ACTION_NEXT_DAY.equals(action)) {
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                int offset = prefs.getInt(KEY_WIDGET_DAY_OFFSET + appWidgetId, 0);
                if (offset < 10) {
                    prefs.edit()
                            .putInt(KEY_WIDGET_DAY_OFFSET + appWidgetId, offset + 1)
                            .putInt(KEY_WIDGET_DISH_INDEX + appWidgetId, 0)
                            .apply();
                }
            }
            updateAllWidgets(context);
        } else if (ACTION_NEXT_DISH.equals(action)) {
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                int curDish = prefs.getInt(KEY_WIDGET_DISH_INDEX + appWidgetId, 0);
                prefs.edit().putInt(KEY_WIDGET_DISH_INDEX + appWidgetId, curDish + 1).apply();
            }
            updateAllWidgets(context);
        } else if (ACTION_REFRESH.equals(action)) {
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                prefs.edit()
                        .putInt(KEY_WIDGET_DAY_OFFSET + appWidgetId, 0)
                        .putInt(KEY_WIDGET_DISH_INDEX + appWidgetId, 0)
                        .apply();
            }
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    String loc = prefs.getString(KEY_LOCATION, MensaApiClient.DEFAULT_LOCATION);
                    MensaApiClient.fetchWeekMenu(context, loc);
                    updateAllWidgets(context);
                } catch (Exception ignored) {
                }
            });
        }
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        for (int id : appWidgetIds) {
            editor.remove(KEY_WIDGET_DAY_OFFSET + id);
            editor.remove(KEY_WIDGET_DISH_INDEX + id);
        }
        editor.apply();
    }

    public static void updateAllWidgets(final Context context) {
        final AppWidgetManager manager = AppWidgetManager.getInstance(context);
        final int[] ids = manager.getAppWidgetIds(new ComponentName(context, MensaWidgetProvider.class));
        if (ids == null || ids.length == 0) return;

        for (int widgetId : ids) {
            updateSingleWidget(context, manager, widgetId);
        }
    }

    private static void updateSingleWidget(Context context, AppWidgetManager manager, int widgetId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String location = prefs.getString(KEY_LOCATION, MensaApiClient.DEFAULT_LOCATION);
        String savedTier = prefs.getString(KEY_PRICE_TIER, PriceTier.STUDENT.name());
        PriceTier tier = PriceTier.STUDENT;
        try {
            tier = PriceTier.valueOf(savedTier);
        } catch (Exception ignored) {
        }

        final PriceTier activeTier = tier;
        final String activeLocation = location;

        WeekMenuResponse cached = MensaApiClient.loadCachedMenu(context, activeLocation);
        if (cached != null) {
            applyResponseToWidget(context, manager, widgetId, cached, activeLocation, activeTier);
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                WeekMenuResponse fresh = MensaApiClient.fetchWeekMenu(context, activeLocation);
                applyResponseToWidget(context, manager, widgetId, fresh, activeLocation, activeTier);
            } catch (Exception ignored) {
            }
        });
    }

    private static void applyResponseToWidget(
            Context context,
            AppWidgetManager manager,
            int widgetId,
            WeekMenuResponse resp,
            String locationName,
            PriceTier priceTier) {

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int dayOffset = prefs.getInt(KEY_WIDGET_DAY_OFFSET + widgetId, 0);

        ZonedDateTime nowBerlin = ZonedDateTime.now(BERLIN_ZONE);
        DayOfWeek currentDow = nowBerlin.getDayOfWeek();
        LocalTime currentTime = nowBerlin.toLocalTime();
        LocalDate todayDate = nowBerlin.toLocalDate();

        boolean isWeekday = currentDow != DayOfWeek.SATURDAY && currentDow != DayOfWeek.SUNDAY;
        boolean isAfterLunch = currentTime.isAfter(LocalTime.of(14, 45));

        List<DayMenu> availableDays = new ArrayList<>();
        if (resp != null && resp.days != null) {
            for (DayMenu dm : resp.days) {
                if (!dm.isEmpty()) {
                    availableDays.add(dm);
                }
            }
        }

        int baseIndex = 0;
        if (isAfterLunch || !isWeekday) {
            for (int i = 0; i < availableDays.size(); i++) {
                try {
                    LocalDate d = LocalDate.parse(availableDays.get(i).dateIso);
                    if (d.isAfter(todayDate)) {
                        baseIndex = i;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        int targetIndex = baseIndex + dayOffset;
        if (targetIndex >= availableDays.size()) {
            targetIndex = Math.max(0, availableDays.size() - 1);
        }
        if (targetIndex < 0) {
            targetIndex = 0;
        }

        DayMenu targetDay = (targetIndex < availableDays.size()) ? availableDays.get(targetIndex) : null;
        String dayBadgeText = "HEUTE";
        String daySubText = "11:30–14:45 Uhr";

        List<Dish> mains = new ArrayList<>();
        List<String> sideNames = new ArrayList<>();

        if (targetDay != null) {
            try {
                LocalDate d = LocalDate.parse(targetDay.dateIso);
                String dowShort = getGermanWeekdayShort(d.getDayOfWeek());
                if (d.equals(todayDate)) {
                    dayBadgeText = "HEUTE";
                    daySubText = "11:30–14:45";
                } else if (d.equals(todayDate.plusDays(1))) {
                    dayBadgeText = "MORGEN";
                    daySubText = dowShort + ", " + d.format(SHORT_DATE_FMT);
                } else {
                    dayBadgeText = dowShort.toUpperCase(Locale.GERMANY);
                    daySubText = d.format(SHORT_DATE_FMT);
                }
            } catch (Exception ignored) {
            }

            for (Dish dish : targetDay.mainDishes) {
                if (!dish.isSideDish && !dish.isDessert && !dish.isSaladBuffet) {
                    mains.add(dish);
                }
            }
            if (mains.isEmpty()) {
                for (Dish dish : targetDay.mainDishes) {
                    mains.add(dish);
                }
            }

            for (Dish side : targetDay.sideDishes) {
                sideNames.add(side.titleDe);
            }
            if (sideNames.isEmpty()) {
                for (Dish des : targetDay.desserts) {
                    sideNames.add(des.titleDe);
                }
            }
        }

        // Open App Intent
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openAppPending = PendingIntent.getActivity(
                context, widgetId, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Prev Day Intent
        Intent prevIntent = new Intent(context, MensaWidgetProvider.class);
        prevIntent.setAction(ACTION_PREV_DAY);
        prevIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        PendingIntent prevPending = PendingIntent.getBroadcast(
                context, widgetId * 10 + 1, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Next Day Intent
        Intent nextIntent = new Intent(context, MensaWidgetProvider.class);
        nextIntent.setAction(ACTION_NEXT_DAY);
        nextIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        PendingIntent nextPending = PendingIntent.getBroadcast(
                context, widgetId * 10 + 2, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Next Dish (Cycle in 2x1 mode) Intent
        Intent nextDishIntent = new Intent(context, MensaWidgetProvider.class);
        nextDishIntent.setAction(ACTION_NEXT_DISH);
        nextDishIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        PendingIntent nextDishPending = PendingIntent.getBroadcast(
                context, widgetId * 10 + 4, nextDishIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Determine if small format (e.g. 2x1, 3x1, or height <= 80dp)
        Bundle options = manager.getAppWidgetOptions(widgetId);
        int minHeight = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) : 0;
        boolean isSmallFormat = (minHeight <= 80);

        RemoteViews views;

        if (isSmallFormat) {
            views = new RemoteViews(context.getPackageName(), R.layout.widget_mensa_small);
            views.setTextViewText(R.id.widget_day_chip, dayBadgeText);
            views.setOnClickPendingIntent(R.id.widget_btn_prev, prevPending);
            views.setOnClickPendingIntent(R.id.widget_btn_next, nextPending);

            int savedDishIdx = prefs.getInt(KEY_WIDGET_DISH_INDEX + widgetId, 0);
            int activeDishIdx = 0;
            if (!mains.isEmpty()) {
                activeDishIdx = savedDishIdx % mains.size();
                Dish curDish = mains.get(activeDishIdx);
                String icon = getDishIcon(curDish);

                // Full clear title + component if space
                String fullTitle = icon + " " + curDish.titleDe;
                if (curDish.subtitleDe != null && !curDish.subtitleDe.isEmpty()) {
                    fullTitle += " (" + curDish.subtitleDe + ")";
                }

                views.setTextViewText(R.id.widget_compact_dish, fullTitle);
                views.setTextViewText(R.id.widget_compact_counter, (activeDishIdx + 1) + "/" + mains.size() + " ↻");
                views.setTextViewText(R.id.widget_compact_price, curDish.getFormattedPrice(priceTier));

                String sidesHint = (!sideNames.isEmpty()) ? ("Beilagen: " + String.join(", ", sideNames)) : daySubText;
                views.setTextViewText(R.id.widget_compact_sub, sidesHint);

                // Clicking the dish cycles through the other main dishes of the day!
                views.setOnClickPendingIntent(R.id.widget_compact_dish, nextDishPending);
                views.setOnClickPendingIntent(R.id.widget_compact_counter, nextDishPending);
            } else {
                views.setTextViewText(R.id.widget_compact_dish, "Keine Speisen gemeldet");
                views.setTextViewText(R.id.widget_compact_counter, "");
                views.setTextViewText(R.id.widget_compact_price, "–");
                views.setTextViewText(R.id.widget_compact_sub, daySubText);
                views.setOnClickPendingIntent(R.id.widget_compact_dish, openAppPending);
            }

            // Clicking the price or bottom subtitle opens the full app
            views.setOnClickPendingIntent(R.id.widget_compact_price, openAppPending);
            views.setOnClickPendingIntent(R.id.widget_compact_sub, openAppPending);
            views.setOnClickPendingIntent(R.id.widget_day_chip, openAppPending);

        } else {
            // Multi-dish layout (2x2, 3x2, 4x2)
            views = new RemoteViews(context.getPackageName(), R.layout.widget_mensa);
            views.setTextViewText(R.id.widget_title, locationName.equals("Mensa Deutz") ? "Mensa Deutz" : locationName);
            views.setTextViewText(R.id.widget_subtitle, dayBadgeText + " · " + daySubText);
            views.setOnClickPendingIntent(R.id.widget_title, openAppPending);
            views.setOnClickPendingIntent(R.id.widget_btn_prev, prevPending);
            views.setOnClickPendingIntent(R.id.widget_btn_next, nextPending);

            bindDishSlot(views, R.id.widget_dish1_container, R.id.widget_dish1_icon, R.id.widget_dish1_title, R.id.widget_dish1_price,
                    mains.size() > 0 ? mains.get(0) : null, priceTier, openAppPending);

            bindDishSlot(views, R.id.widget_dish2_container, R.id.widget_dish2_icon, R.id.widget_dish2_title, R.id.widget_dish2_price,
                    mains.size() > 1 ? mains.get(1) : null, priceTier, openAppPending);

            if (minHeight > 0 && minHeight < 140) {
                views.setViewVisibility(R.id.widget_dish3_container, View.GONE);
            } else {
                bindDishSlot(views, R.id.widget_dish3_container, R.id.widget_dish3_icon, R.id.widget_dish3_title, R.id.widget_dish3_price,
                        mains.size() > 2 ? mains.get(2) : null, priceTier, openAppPending);
            }

            if (!sideNames.isEmpty()) {
                views.setViewVisibility(R.id.widget_sides, View.VISIBLE);
                views.setTextViewText(R.id.widget_sides, "+ " + String.join(" · ", sideNames));
            } else {
                views.setViewVisibility(R.id.widget_sides, View.GONE);
            }
        }

        manager.updateAppWidget(widgetId, views);
    }

    private static void bindDishSlot(
            RemoteViews views,
            int containerId,
            int iconId,
            int titleId,
            int priceId,
            Dish dish,
            PriceTier tier,
            PendingIntent clickIntent) {

        if (dish == null) {
            views.setViewVisibility(containerId, View.GONE);
            return;
        }

        views.setViewVisibility(containerId, View.VISIBLE);
        views.setOnClickPendingIntent(containerId, clickIntent);
        views.setTextViewText(iconId, getDishIcon(dish));
        views.setTextViewText(titleId, dish.titleDe);
        views.setTextViewText(priceId, dish.getFormattedPrice(tier));
    }

    private static String getDishIcon(Dish dish) {
        if (dish == null) return "🍽️";
        if (dish.isVegan()) return "🌱";
        if (dish.isVegetarian()) return "🧀";
        if (dish.foodIcons.contains("G")) return "🍗";
        if (dish.foodIcons.contains("R") || dish.foodIcons.contains("S")) return "🥩";
        if (dish.foodIcons.contains("F")) return "🐟";
        return "🍽️";
    }

    private static String getGermanWeekdayShort(DayOfWeek dow) {
        switch (dow) {
            case MONDAY: return "Mo";
            case TUESDAY: return "Di";
            case WEDNESDAY: return "Mi";
            case THURSDAY: return "Do";
            case FRIDAY: return "Fr";
            case SATURDAY: return "Sa";
            case SUNDAY: return "So";
            default: return "";
        }
    }
}
