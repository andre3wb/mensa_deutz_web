package de.thkoeln.mensadeutz;

import java.util.ArrayList;
import java.util.List;

public final class MensaModels {

    private MensaModels() {}

    public enum PriceTier {
        STUDENT("Studierende", "Stud."),
        EMPLOYEE("Bedienstete", "Bed."),
        GUEST("Gäste", "Gäste");

        public final String label;
        public final String shortLabel;

        PriceTier(String label, String shortLabel) {
            this.label = label;
            this.shortLabel = shortLabel;
        }
    }

    public enum DietFilter {
        ALL("Alle"),
        VEGAN("Vegan"),
        VEGETARIAN("Vegetarisch"),
        MEAT_FISH("Fleisch & Fisch");

        public final String label;

        DietFilter(String label) {
            this.label = label;
        }
    }

    public static final class FoodBadge {
        public final String code;
        public final String label;
        public final int bgColorLight;
        public final int textColorLight;
        public final int bgColorDark;
        public final int textColorDark;

        public FoodBadge(String code, String label, int bgColorLight, int textColorLight, int bgColorDark, int textColorDark) {
            this.code = code;
            this.label = label;
            this.bgColorLight = bgColorLight;
            this.textColorLight = textColorLight;
            this.bgColorDark = bgColorDark;
            this.textColorDark = textColorDark;
        }
    }

    public static final class Dish {
        public final String id;
        public final String titleDe;
        public final String titleEn;
        public final String subtitleDe; // extra components e.g. "Bratensauce · Basmatireis"
        public final String subtitleEn;
        public final List<String> componentsDe;
        public final String menuType; // e.g. "Fleisch", "Fisch", "vegan/vegetarisch", "Beilagen", "Aktion"
        public final String poolHint; // e.g. "Suppenpool", "Beilagenpool"
        public final boolean isSideDish;
        public final boolean isSaladBuffet;
        public final boolean isSoup;
        public final boolean isDessert;
        public final double priceStudent;
        public final double priceEmployee;
        public final double priceGuest;
        public final String imageUrl;
        public final List<String> foodIcons; // VGN, VGT, G, R, S, F, NL...
        public final List<String> allergensList; // e.g. "Enthält Gluten (Weizen)", "Mit Farbstoff"
        public final String counterLocation; // e.g. "Theke 2"
        public final boolean isSoldOut;

        public Dish(
                String id,
                String titleDe,
                String titleEn,
                String subtitleDe,
                String subtitleEn,
                List<String> componentsDe,
                String menuType,
                String poolHint,
                boolean isSideDish,
                boolean isSaladBuffet,
                boolean isSoup,
                boolean isDessert,
                double priceStudent,
                double priceEmployee,
                double priceGuest,
                String imageUrl,
                List<String> foodIcons,
                List<String> allergensList,
                String counterLocation,
                boolean isSoldOut) {
            this.id = id;
            this.titleDe = titleDe;
            this.titleEn = titleEn;
            this.subtitleDe = subtitleDe;
            this.subtitleEn = subtitleEn;
            this.componentsDe = componentsDe != null ? componentsDe : new ArrayList<>();
            this.menuType = menuType != null ? menuType : "";
            this.poolHint = poolHint != null ? poolHint : "";
            this.isSideDish = isSideDish;
            this.isSaladBuffet = isSaladBuffet;
            this.isSoup = isSoup;
            this.isDessert = isDessert;
            this.priceStudent = priceStudent;
            this.priceEmployee = priceEmployee;
            this.priceGuest = priceGuest;
            this.imageUrl = imageUrl;
            this.foodIcons = foodIcons != null ? foodIcons : new ArrayList<>();
            this.allergensList = allergensList != null ? allergensList : new ArrayList<>();
            this.counterLocation = counterLocation;
            this.isSoldOut = isSoldOut;
        }

        public boolean isVegan() {
            return foodIcons.contains("VGN")
                    || titleDe.toLowerCase().contains("vegan");
        }

        public boolean isVegetarian() {
            return isVegan()
                    || foodIcons.contains("VGT")
                    || menuType.toLowerCase().contains("vegetarisch");
        }

        public boolean isMeatOrFish() {
            for (String icon : foodIcons) {
                if ("G".equals(icon) || "R".equals(icon) || "S".equals(icon)
                        || "F".equals(icon) || "L".equals(icon) || "W".equals(icon)) {
                    return true;
                }
            }
            String mt = menuType.toLowerCase();
            return mt.contains("fleisch") || mt.contains("fisch");
        }

        public boolean matchesFilter(DietFilter filter) {
            if (filter == null || filter == DietFilter.ALL) return true;
            switch (filter) {
                case VEGAN:
                    return isVegan();
                case VEGETARIAN:
                    return isVegetarian();
                case MEAT_FISH:
                    return isMeatOrFish();
                default:
                    return true;
            }
        }

        public double getPrice(PriceTier tier) {
            if (tier == PriceTier.EMPLOYEE && priceEmployee > 0) return priceEmployee;
            if (tier == PriceTier.GUEST && priceGuest > 0) return priceGuest;
            return priceStudent;
        }

        public String getFormattedPrice(PriceTier tier) {
            double p = getPrice(tier);
            if (p <= 0) return "–";
            String s = String.format(java.util.Locale.GERMANY, "%.2f €", p);
            if (isSaladBuffet) {
                return s + " / 100g";
            }
            return s;
        }

        public String getCategoryDisplayLabel() {
            if (isSaladBuffet) return "Salatbuffet";
            if (isSoup) return "Suppe / Eintopf";
            if (isDessert) return "Dessert";
            if (isSideDish) return "Beilage";
            String mt = menuType.trim();
            if (mt.equalsIgnoreCase("vegan/vegetarisch")) {
                return isVegan() ? "Vegan" : "Vegetarisch";
            }
            if (mt.equalsIgnoreCase("Fleisch")) {
                if (foodIcons.contains("G")) return "Geflügel";
                if (foodIcons.contains("R") && foodIcons.contains("S")) return "Rind & Schwein";
                if (foodIcons.contains("R")) return "Rind";
                if (foodIcons.contains("S")) return "Schwein";
                return "Fleisch";
            }
            if (mt.equalsIgnoreCase("Fisch")) return "Fisch";
            if (mt.toLowerCase().contains("querbeet")) return "Querbeet";
            if (mt.toLowerCase().contains("aktion")) return "Aktion";
            if (mt.toLowerCase().contains("streetfood")) return "Streetfood";
            if (mt.toLowerCase().contains("heimspiel")) return "Heimspiel";
            if (mt.toLowerCase().contains("worldwide")) return "Worldwide";
            if (mt.toLowerCase().contains("meisterwerk")) return "Meisterwerk";
            return mt.isEmpty() ? "Hauptgericht" : mt;
        }
    }

    public static final class DayMenu {
        public final String dateIso; // YYYY-MM-DD
        public final List<Dish> mainDishes;
        public final List<Dish> sideDishes;
        public final List<Dish> desserts;

        public DayMenu(String dateIso, List<Dish> mainDishes, List<Dish> sideDishes, List<Dish> desserts) {
            this.dateIso = dateIso;
            this.mainDishes = mainDishes != null ? mainDishes : new ArrayList<>();
            this.sideDishes = sideDishes != null ? sideDishes : new ArrayList<>();
            this.desserts = desserts != null ? desserts : new ArrayList<>();
        }

        public int getTotalDishCount() {
            return mainDishes.size() + sideDishes.size() + desserts.size();
        }

        public boolean isEmpty() {
            return getTotalDishCount() == 0;
        }
    }

    public static final class WeekMenuResponse {
        public final String serverTodayIso;
        public final String locationName;
        public final long fetchedAtMillis;
        public final boolean fromCache;
        public final List<DayMenu> days;

        public WeekMenuResponse(String serverTodayIso, String locationName, long fetchedAtMillis, boolean fromCache, List<DayMenu> days) {
            this.serverTodayIso = serverTodayIso;
            this.locationName = locationName;
            this.fetchedAtMillis = fetchedAtMillis;
            this.fromCache = fromCache;
            this.days = days != null ? days : new ArrayList<>();
        }
    }
}
