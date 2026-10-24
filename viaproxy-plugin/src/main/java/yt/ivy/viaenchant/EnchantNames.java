package yt.ivy.viaenchant;

final class EnchantNames {

    private static final String[] NAMES = new String[72];
    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V"};

    static {
        NAMES[0] = "Protection";
        NAMES[1] = "Fire Protection";
        NAMES[2] = "Feather Falling";
        NAMES[3] = "Blast Protection";
        NAMES[4] = "Projectile Protection";
        NAMES[5] = "Respiration";
        NAMES[6] = "Aqua Affinity";
        NAMES[7] = "Thorns";
        NAMES[8] = "Depth Strider";
        NAMES[9] = "Frost Walker";
        NAMES[10] = "Curse of Binding";
        NAMES[16] = "Sharpness";
        NAMES[17] = "Smite";
        NAMES[18] = "Bane of Arthropods";
        NAMES[19] = "Knockback";
        NAMES[20] = "Fire Aspect";
        NAMES[21] = "Looting";
        NAMES[22] = "Sweeping Edge";
        NAMES[32] = "Efficiency";
        NAMES[33] = "Silk Touch";
        NAMES[34] = "Unbreaking";
        NAMES[35] = "Fortune";
        NAMES[48] = "Power";
        NAMES[49] = "Punch";
        NAMES[50] = "Flame";
        NAMES[51] = "Infinity";
        NAMES[61] = "Luck of the Sea";
        NAMES[62] = "Lure";
        NAMES[70] = "Mending";
        NAMES[71] = "Curse of Vanishing";
    }

    private EnchantNames() {
    }

    static String format(final int id, final int level) {
        final String name = id >= 0 && id < NAMES.length && NAMES[id] != null ? NAMES[id] : "Enchantment #" + id;
        final String roman = level > 0 && level < ROMAN.length ? ROMAN[level] : String.valueOf(level);
        return name + " " + roman;
    }
}
