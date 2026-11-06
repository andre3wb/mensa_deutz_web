package de.thkoeln.mensadeutz;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

public final class ThemePalette {

    public final boolean isDark;

    public final int bgWindow;
    public final int bgHeader;
    public final int bgCard;
    public final int bgCardSubtle;
    public final int bgChipUnselected;
    public final int bgChipSelected;
    public final int borderSubtle;
    public final int borderHighlight;

    public final int textPrimary;
    public final int textSecondary;
    public final int textMuted;
    public final int textOnAccent;

    public final int accentPrimary;
    public final int accentPrimarySoft;
    public final int accentGreen;
    public final int accentGreenSoft;

    public ThemePalette(boolean isDark) {
        this.isDark = isDark;
        if (isDark) {
            bgWindow = 0xFF0B0F19;
            bgHeader = 0xFF111827;
            bgCard = 0xFF161F32;
            bgCardSubtle = 0xFF1E293B;
            bgChipUnselected = 0xFF1E293B;
            bgChipSelected = 0xFFF97316;
            borderSubtle = 0xFF26334D;
            borderHighlight = 0xFFF97316;

            textPrimary = 0xFFF8FAFC;
            textSecondary = 0xFFCBD5E1;
            textMuted = 0xFF94A3B8;
            textOnAccent = 0xFFFFFFFF;

            accentPrimary = 0xFFF97316;
            accentPrimarySoft = 0xFF3B1D0A;
            accentGreen = 0xFF10B981;
            accentGreenSoft = 0xFF064E3B;
        } else {
            bgWindow = 0xFFF4F6FA;
            bgHeader = 0xFFFFFFFF;
            bgCard = 0xFFFFFFFF;
            bgCardSubtle = 0xFFF8FAFC;
            bgChipUnselected = 0xFFEEF2F6;
            bgChipSelected = 0xFFEA580C;
            borderSubtle = 0xFFE2E8F0;
            borderHighlight = 0xFFEA580C;

            textPrimary = 0xFF0F172A;
            textSecondary = 0xFF475569;
            textMuted = 0xFF64748B;
            textOnAccent = 0xFFFFFFFF;

            accentPrimary = 0xFFEA580C;
            accentPrimarySoft = 0xFFFFF7ED;
            accentGreen = 0xFF059669;
            accentGreenSoft = 0xFFECFDF5;
        }
    }

    public static int dp(Context ctx, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable roundedRect(int fillColor, int cornerRadiusPx, int strokeWidthPx, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(fillColor);
        gd.setCornerRadius(cornerRadiusPx);
        if (strokeWidthPx > 0) {
            gd.setStroke(strokeWidthPx, strokeColor);
        }
        return gd;
    }

    public static int[] getBadgeColorsForIcon(String iconCode, boolean isDark) {
        switch (iconCode) {
            case "VGN":
                return isDark ? new int[]{0xFF064E3B, 0xFF6EE7B7} : new int[]{0xFFDCFCE7, 0xFF15803D};
            case "VGT":
                return isDark ? new int[]{0xFF065F46, 0xFFA7F3D0} : new int[]{0xFFD1FAE5, 0xFF047857};
            case "G":
                return isDark ? new int[]{0xFF451A03, 0xFFFCD34D} : new int[]{0xFFFEF3C7, 0xFFB45309};
            case "R":
            case "S":
            case "L":
            case "W":
                return isDark ? new int[]{0xFF450A0A, 0xFFFCA5A5} : new int[]{0xFFFEE2E2, 0xFFB91C1C};
            case "F":
                return isDark ? new int[]{0xFF082F49, 0xFF7DD3FC} : new int[]{0xFFE0F2FE, 0xFF0369A1};
            case "NL":
                return isDark ? new int[]{0xFF422006, 0xFFFDE047} : new int[]{0xFFFEF9C3, 0xFF854D0E};
            default:
                return isDark ? new int[]{0xFF1E293B, 0xFFCBD5E1} : new int[]{0xFFF1F5F9, 0xFF475569};
        }
    }

    public static String getBadgeLabelForIcon(String iconCode) {
        switch (iconCode) {
            case "VGN":
                return "🌱 Vegan";
            case "VGT":
                return "🧀 Vegetarisch";
            case "G":
                return "🍗 Geflügel";
            case "R":
                return "🥩 Rind";
            case "S":
                return "🥓 Schwein";
            case "F":
                return "🐟 Fisch";
            case "L":
                return "🍖 Lamm";
            case "W":
                return "🦌 Wild";
            case "A":
                return "🍷 Alkohol";
            case "NL":
                return "🌾 Neuland";
            case "RK":
                return "🥔 Rettet die Knolle";
            default:
                return iconCode;
        }
    }

    public static int getCategoryAccentColor(MensaModels.Dish dish, boolean isDark) {
        if (dish.isSaladBuffet) return isDark ? 0xFF34D399 : 0xFF059669;
        if (dish.isSoup) return isDark ? 0xFFFBBF24 : 0xFFD97706;
        if (dish.isDessert) return isDark ? 0xFFF472B6 : 0xFFDB2777;
        if (dish.isSideDish) return isDark ? 0xFF94A3B8 : 0xFF64748B;
        if (dish.isVegan()) return isDark ? 0xFF34D399 : 0xFF16A34A;
        if (dish.isVegetarian()) return isDark ? 0xFF2DD4BF : 0xFF0D9488;
        String mt = dish.menuType.toLowerCase();
        if (mt.contains("fisch") || dish.foodIcons.contains("F")) {
            return isDark ? 0xFF38BDF8 : 0xFF0284C7;
        }
        if (mt.contains("aktion")) {
            return isDark ? 0xFFFBBF24 : 0xFFD97706;
        }
        return isDark ? 0xFFF87171 : 0xFFDC2626;
    }
}
