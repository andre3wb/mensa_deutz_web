package de.thkoeln.mensadeutz;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.thkoeln.mensadeutz.MensaModels.DayMenu;
import de.thkoeln.mensadeutz.MensaModels.Dish;
import de.thkoeln.mensadeutz.MensaModels.WeekMenuResponse;

public final class MensaApiClient {

    public static final String DEFAULT_LOCATION = "Mensa Deutz";

    public static final String[] AVAILABLE_LOCATIONS = new String[]{
            "Mensa Deutz",
            "Mensa Südstadt",
            "Mensa Claudiusstraße",
            "Mensa Zülpicher Straße",
            "Mensa Lindenthal",
            "Mensa Gummersbach",
            "Mensa Leverkusen",
            "Mensa Kunsthochschule",
            "Mensa Musikhochschule",
            "Mensa Am Sportpark Müngersdorf"
    };

    private static final String SUPABASE_BASE = "https://axxiebkvmfjmiaanviob.supabase.co/rest/v1/rpc/";
    private static final String RPC_WEEK_MENU_CACHED = SUPABASE_BASE + "public_get_week_menu_cached";
    private static final String RPC_WEEK_MENU = SUPABASE_BASE + "public_get_week_menu";
    private static final String API_KEY = "sb_publishable_G6p4Gfhzcx2AM5ToCWAChA_pY-PKF2a";
    private static final String KSTW_ORG_ID = "4c89c35f-16ac-413f-af04-ec9ffe610f67";

    private static final ZoneId BERLIN_ZONE = ZoneId.of("Europe/Berlin");
    private static final Pattern PAREN_ALLERGEN_PATTERN = Pattern.compile("\\s*\\([0-9a-zA-Z,\\s]+\\)");
    private static final Pattern INTERNAL_WORDS_PATTERN = Pattern.compile("(?i)\\b(TK|Grundrezept|Tagesrestproduktion)\\b");

    private MensaApiClient() {}

    public static WeekMenuResponse fetchWeekMenu(Context context, String locationName) throws Exception {
        String loc = (locationName == null || locationName.trim().isEmpty()) ? DEFAULT_LOCATION : locationName.trim();

        LocalDate startDate = LocalDate.now(BERLIN_ZONE);
        // Fetch 14 days ahead so the current day and all upcoming days (including next week) are available
        LocalDate endDate = startDate.plusDays(13);

        HttpResult result;
        try {
            result = postMenuRpc(RPC_WEEK_MENU_CACHED, startDate, endDate);
        } catch (Exception e) {
            result = postMenuRpc(RPC_WEEK_MENU, startDate, endDate);
        }

        // If server date differs significantly from local device clock, re-query using server date
        if (result.serverDate != null && Math.abs(result.serverDate.toEpochDay() - startDate.toEpochDay()) > 1) {
            startDate = result.serverDate;
            endDate = startDate.plusDays(13);
            result = postMenuRpc(RPC_WEEK_MENU_CACHED, startDate, endDate);
        }

        LocalDate effectiveToday = result.serverDate != null ? result.serverDate : startDate;
        long nowMillis = System.currentTimeMillis();

        if (context != null) {
            saveCache(context, effectiveToday.toString(), nowMillis, result.rawBody);
        }

        return parseWeekMenuJson(result.rawBody, effectiveToday, endDate, loc, nowMillis, false);
    }

    public static WeekMenuResponse loadCachedMenu(Context context, String locationName) {
        if (context == null) return null;
        String loc = (locationName == null || locationName.trim().isEmpty()) ? DEFAULT_LOCATION : locationName.trim();
        File cacheFile = new File(context.getFilesDir(), "kstw_week_menu_cache_v1.json");
        if (!cacheFile.exists()) return null;
        try (FileInputStream fis = new FileInputStream(cacheFile);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            JSONObject wrapper = new JSONObject(sb.toString());
            String serverTodayStr = wrapper.optString("serverToday", LocalDate.now(BERLIN_ZONE).toString());
            long fetchedAt = wrapper.optLong("fetchedAt", cacheFile.lastModified());
            String rawArray = wrapper.getString("rawArray");

            LocalDate startDate = LocalDate.parse(serverTodayStr);
            LocalDate endDate = startDate.plusDays(13);
            return parseWeekMenuJson(rawArray, startDate, endDate, loc, fetchedAt, true);
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveCache(Context context, String serverToday, long fetchedAt, String rawArray) {
        try {
            File cacheFile = new File(context.getFilesDir(), "kstw_week_menu_cache_v1.json");
            JSONObject wrapper = new JSONObject();
            wrapper.put("serverToday", serverToday);
            wrapper.put("fetchedAt", fetchedAt);
            wrapper.put("rawArray", rawArray);
            byte[] bytes = wrapper.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                fos.write(bytes);
            }
        } catch (Exception ignored) {
        }
    }

    private static final class HttpResult {
        final String rawBody;
        final LocalDate serverDate;

        HttpResult(String rawBody, LocalDate serverDate) {
            this.rawBody = rawBody;
            this.serverDate = serverDate;
        }
    }

    private static HttpResult postMenuRpc(String rpcUrl, LocalDate startDate, LocalDate endDate) throws Exception {
        URL url = new URL(rpcUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(12000);
        conn.setDoOutput(true);
        conn.setRequestProperty("apikey", API_KEY);
        conn.setRequestProperty("Authorization", "Bearer " + API_KEY);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");

        JSONObject payload = new JSONObject();
        payload.put("p_organization_id", KSTW_ORG_ID);
        payload.put("p_start_date", startDate.toString());
        payload.put("p_end_date", endDate.toString());
        JSONArray dedup = new JSONArray();
        dedup.put("name_de");
        dedup.put("location");
        dedup.put("ort_id");
        dedup.put("");
        payload.put("p_dedup_fields", dedup);

        byte[] bodyBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
        }

        int status = conn.getResponseCode();
        LocalDate srvDate = null;
        String dateHeader = conn.getHeaderField("Date");
        if (dateHeader != null && !dateHeader.isEmpty()) {
            try {
                ZonedDateTime zdt = ZonedDateTime.parse(dateHeader, DateTimeFormatter.RFC_1123_DATE_TIME);
                srvDate = zdt.withZoneSameInstant(BERLIN_ZONE).toLocalDate();
            } catch (Exception ignored) {
            }
        }

        InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
        }
        conn.disconnect();

        if (status < 200 || status >= 300) {
            throw new Exception("HTTP " + status + ": " + sb);
        }
        return new HttpResult(sb.toString(), srvDate);
    }

    public static WeekMenuResponse parseWeekMenuJson(
            String rawJsonArray,
            LocalDate startDate,
            LocalDate endDate,
            String targetLocation,
            long fetchedAtMillis,
            boolean fromCache) throws Exception {

        JSONArray array = new JSONArray(rawJsonArray);
        Map<String, DayMenu> dayMap = new HashMap<>();

        for (int i = 0; i < array.length(); i++) {
            JSONObject dayObj = array.getJSONObject(i);
            String dateIso = dayObj.optString("date", "");
            if (dateIso.isEmpty()) continue;

            // Skip Sunday leftover test entries if any
            try {
                LocalDate parsedDate = LocalDate.parse(dateIso);
                if (parsedDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    continue;
                }
            } catch (Exception ignored) {
            }

            JSONArray dishesArr = dayObj.optJSONArray("dishes");
            List<Dish> mainDishes = new ArrayList<>();
            List<Dish> sideDishes = new ArrayList<>();
            List<Dish> desserts = new ArrayList<>();

            if (dishesArr != null) {
                for (int j = 0; j < dishesArr.length(); j++) {
                    JSONObject dishObj = dishesArr.optJSONObject(j);
                    if (dishObj == null) continue;
                    Dish dish = parseSingleDish(dishObj, targetLocation);
                    if (dish == null) continue;

                    if (dish.isSideDish) {
                        if (dish.isDessert) {
                            desserts.add(dish);
                        } else {
                            sideDishes.add(dish);
                        }
                    } else {
                        mainDishes.add(dish);
                    }
                }
            }

            // Sort main dishes: regular hot dishes first (by price desc), then soups, then salad buffet at the end of mains
            Collections.sort(mainDishes, new Comparator<Dish>() {
                @Override
                public int compare(Dish a, Dish b) {
                    int rankA = a.isSaladBuffet ? 2 : (a.isSoup ? 1 : 0);
                    int rankB = b.isSaladBuffet ? 2 : (b.isSoup ? 1 : 0);
                    if (rankA != rankB) return Integer.compare(rankA, rankB);
                    return Double.compare(b.priceStudent, a.priceStudent);
                }
            });

            // Sort side dishes alphabetically / by price
            Collections.sort(sideDishes, new Comparator<Dish>() {
                @Override
                public int compare(Dish a, Dish b) {
                    return a.titleDe.compareToIgnoreCase(b.titleDe);
                }
            });

            Collections.sort(desserts, new Comparator<Dish>() {
                @Override
                public int compare(Dish a, Dish b) {
                    return a.titleDe.compareToIgnoreCase(b.titleDe);
                }
            });

            dayMap.put(dateIso, new DayMenu(dateIso, mainDishes, sideDishes, desserts));
        }

        // Build contiguous list of weekdays (Mon-Fri) + any Saturday that has dishes
        List<DayMenu> orderedDays = new ArrayList<>();
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            String iso = cursor.toString();
            DayMenu existing = dayMap.get(iso);
            DayOfWeek dow = cursor.getDayOfWeek();
            boolean isWeekday = dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
            if (existing != null && !existing.isEmpty()) {
                orderedDays.add(existing);
            } else if (isWeekday || cursor.equals(startDate)) {
                orderedDays.add(new DayMenu(iso, Collections.emptyList(), Collections.emptyList(), Collections.emptyList()));
            }
            cursor = cursor.plusDays(1);
        }

        return new WeekMenuResponse(startDate.toString(), targetLocation, fetchedAtMillis, fromCache, orderedDays);
    }

    private static Dish parseSingleDish(JSONObject dishObj, String targetLocation) {
        Map<String, String> cf = new HashMap<>();
        JSONArray cfArr = dishObj.optJSONArray("custom_fields");
        if (cfArr != null) {
            for (int i = 0; i < cfArr.length(); i++) {
                JSONObject item = cfArr.optJSONObject(i);
                if (item != null) {
                    String fid = item.optString("field_id", "");
                    String val = item.optString("value", "");
                    if (!fid.isEmpty() && val != null && !"null".equals(val)) {
                        cf.put(fid, val.trim());
                    }
                }
            }
        }

        // Check location match
        String dishLocation = cf.getOrDefault("location", "");
        boolean matchesLocation = dishLocation.equalsIgnoreCase(targetLocation);
        String counterLocation = null;
        boolean anyActiveScreen = false;
        boolean allActiveSoldOut = true;

        JSONArray screensArr = dishObj.optJSONArray("screens");
        if (screensArr != null) {
            for (int i = 0; i < screensArr.length(); i++) {
                JSONObject sc = screensArr.optJSONObject(i);
                if (sc == null) continue;
                String sgName = sc.optString("screen_group_name", "");
                if (sgName.equalsIgnoreCase(targetLocation)) {
                    matchesLocation = true;
                    boolean poweredOff = sc.optBoolean("is_powered_off", false);
                    boolean soldOut = sc.optBoolean("is_sold_out", false);
                    String locLabel = sc.optString("location", "");
                    if (locLabel.contains("Theke")) {
                        int idx = locLabel.indexOf("Theke");
                        counterLocation = locLabel.substring(idx).trim();
                    }
                    if (!poweredOff) {
                        anyActiveScreen = true;
                        if (!soldOut) {
                            allActiveSoldOut = false;
                        }
                    }
                }
            }
        }

        if (!matchesLocation) {
            return null;
        }

        // Apply KStW visibility rules (bundle-default: hide empty menu_type, xBeilagen, xSonstiges)
        String menuType = cf.getOrDefault("menu_type", "");
        String menuTypeLower = menuType.toLowerCase(Locale.ROOT);
        if (menuType.isEmpty() || menuTypeLower.contains("xbeilagen") || menuTypeLower.contains("xsonstiges")) {
            return null;
        }

        String rawNameDe = dishObj.optString("name_de", "").trim();
        String rawNameEn = dishObj.optString("name_en", "").trim();

        List<String> partsDe = new ArrayList<>();
        List<String> partsEn = new ArrayList<>();
        for (int idx = 1; idx <= 5; idx++) {
            String pDe = cleanDishText(cf.getOrDefault("dish_ger_" + idx, ""));
            if (!pDe.isEmpty()) partsDe.add(pDe);
            String pEn = cleanDishText(cf.getOrDefault("dish_" + idx + "_eng", ""));
            if (!pEn.isEmpty()) partsEn.add(pEn);
        }

        String titleDe = cleanDishText(rawNameDe);
        if (titleDe.isEmpty() && !partsDe.isEmpty()) {
            titleDe = partsDe.get(0);
        }
        String titleEn = cleanDishText(rawNameEn);
        if (titleEn.isEmpty() && !partsEn.isEmpty()) {
            titleEn = partsEn.get(0);
        }

        // Build subtitle from additional components (dish_ger_2..5, or dish_ger_1 if different from titleDe)
        List<String> extraDe = new ArrayList<>();
        for (int i = 0; i < partsDe.size(); i++) {
            String part = partsDe.get(i);
            if (i == 0 && isSameText(part, titleDe)) continue;
            extraDe.add(part);
        }
        String subtitleDe = String.join(" · ", extraDe);

        List<String> extraEn = new ArrayList<>();
        for (int i = 0; i < partsEn.size(); i++) {
            String part = partsEn.get(i);
            if (i == 0 && isSameText(part, titleEn)) continue;
            extraEn.add(part);
        }
        String subtitleEn = String.join(" · ", extraEn);

        String hinweise = cf.getOrDefault("hinweise", "");
        boolean isSideDish = menuTypeLower.contains("beilage");
        boolean isSaladBuffet = titleDe.toLowerCase(Locale.ROOT).contains("salatbuffet");
        boolean isSoup = hinweise.toLowerCase(Locale.ROOT).contains("suppenpool")
                || titleDe.toLowerCase(Locale.ROOT).contains("suppe")
                || titleDe.toLowerCase(Locale.ROOT).contains("eintopf")
                || titleDe.toLowerCase(Locale.ROOT).contains("rahmtopf");

        String lowerTitle = titleDe.toLowerCase(Locale.ROOT);
        boolean isDessert = isSideDish && (
                lowerTitle.contains("pudding")
                        || lowerTitle.contains("quark")
                        || lowerTitle.contains("joghurt")
                        || lowerTitle.contains("mousse")
                        || lowerTitle.contains("dessert")
                        || lowerTitle.contains("obst")
                        || lowerTitle.contains("kompott")
                        || lowerTitle.contains("creme")
                        || lowerTitle.contains("tiramisu")
                        || lowerTitle.contains("milchreis")
                        || lowerTitle.contains("grieß")
                        || lowerTitle.contains("apfelmus")
                        || lowerTitle.contains("kuchen")
                        || lowerTitle.contains("brownie")
        );

        double priceStudent = dishObj.optDouble("price", 0.0);
        double priceEmployee = parseGermanPrice(cf.getOrDefault("price_2", ""));
        double priceGuest = parseGermanPrice(cf.getOrDefault("price_3", ""));

        String imageUrl = dishObj.isNull("image_url") ? null : dishObj.optString("image_url", null);
        if (imageUrl != null && imageUrl.trim().isEmpty()) {
            imageUrl = null;
        }

        List<String> foodIcons = new ArrayList<>();
        String rawIcons = cf.getOrDefault("food_icon", "");
        if (!rawIcons.isEmpty()) {
            for (String token : rawIcons.split(",")) {
                String t = token.trim().toUpperCase(Locale.ROOT);
                if (!t.isEmpty() && !foodIcons.contains(t)) {
                    foodIcons.add(t);
                }
            }
        }

        List<String> allergensList = parseAllergens(cf.getOrDefault("allergens_names", ""));
        boolean isSoldOut = (anyActiveScreen && allActiveSoldOut) || !dishObj.optBoolean("is_available", true);

        return new Dish(
                dishObj.optString("id", titleDe),
                titleDe,
                titleEn,
                subtitleDe,
                subtitleEn,
                partsDe,
                menuType,
                hinweise,
                isSideDish,
                isSaladBuffet,
                isSoup,
                isDessert,
                priceStudent,
                priceEmployee,
                priceGuest,
                imageUrl,
                foodIcons,
                allergensList,
                counterLocation,
                isSoldOut
        );
    }

    public static String cleanDishText(String input) {
        if (input == null || input.isEmpty()) return "";
        String s = PAREN_ALLERGEN_PATTERN.matcher(input).replaceAll("");
        s = INTERNAL_WORDS_PATTERN.matcher(s).replaceAll("");
        s = s.replaceAll("\\s{2,}", " ").trim();
        s = s.replaceAll("^[,\\s]+|[,\\s]+$", "");
        return s;
    }

    private static boolean isSameText(String a, String b) {
        if (a == null || b == null) return false;
        String na = a.toLowerCase(Locale.ROOT).replaceAll("[^a-zäöüß0-9]", "");
        String nb = b.toLowerCase(Locale.ROOT).replaceAll("[^a-zäöüß0-9]", "");
        return na.equals(nb);
    }

    public static double parseGermanPrice(String raw) {
        if (raw == null || raw.trim().isEmpty()) return 0.0;
        try {
            String cleaned = raw.trim().replace("€", "").replace(",", ".").trim();
            double val = Double.parseDouble(cleaned);
            return Double.isFinite(val) ? val : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    public static List<String> parseAllergens(String rawAllergens) {
        if (rawAllergens == null || rawAllergens.trim().isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> result = new LinkedHashSet<>();
        String[] parts = rawAllergens.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            // Format is e.g. "18=Enthält Laktose | contains lactose"
            int eqIdx = trimmed.indexOf('=');
            String code = eqIdx > 0 ? trimmed.substring(0, eqIdx).trim() : "";
            String rest = eqIdx > 0 ? trimmed.substring(eqIdx + 1).trim() : trimmed;
            int pipeIdx = rest.indexOf('|');
            String germanName = pipeIdx > 0 ? rest.substring(0, pipeIdx).trim() : rest;
            if (!germanName.isEmpty()) {
                if (!code.isEmpty()) {
                    result.add(germanName + " (" + code + ")");
                } else {
                    result.add(germanName);
                }
            }
        }
        return new ArrayList<>(result);
    }
}
