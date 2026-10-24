package yt.ivy.viaenchant;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import java.util.Arrays;

/**
 * Last enchanting table properties seen for the currently open table window of one connection.
 * <p>
 * 1.8 window properties of the enchanting table: 0-2 level requirements, 3 enchantment seed, 4-6 the clue of each
 * slot packed as {@code enchantmentId | level << 8} (-1 if the slot has no clue).
 */
public class EnchantState extends StoredObject {

    public static final int PROPERTY_COUNT = 7;
    public static final int LAST_PROPERTY = PROPERTY_COUNT - 1;
    public static final int FIRST_CLUE_PROPERTY = 4;

    private final short[] values = new short[PROPERTY_COUNT];
    private final boolean[] known = new boolean[PROPERTY_COUNT];
    private int windowId = -1;
    private boolean dirty;
    private boolean announced;
    private boolean modPresent;

    public EnchantState(final UserConnection user) {
        super(user);
    }

    public void reset() {
        windowId = -1;
        dirty = false;
        Arrays.fill(known, false);
    }

    /**
     * @return true if the value differs from the last one seen for this window
     */
    public boolean update(final int windowId, final int property, final short value) {
        if (this.windowId != windowId) {
            reset();
            this.windowId = windowId;
        }
        if (known[property] && values[property] == value) {
            return false;
        }
        known[property] = true;
        values[property] = value;
        dirty = true;
        return true;
    }

    public short get(final int property) {
        return values[property];
    }

    public int clueEnchantmentId(final int slot) {
        return values[FIRST_CLUE_PROPERTY + slot] & 0xFF;
    }

    public int clueLevel(final int slot) {
        return values[FIRST_CLUE_PROPERTY + slot] >> 8;
    }

    public boolean hasClue(final int slot) {
        return known[FIRST_CLUE_PROPERTY + slot] && clueLevel(slot) > 0;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }

    public boolean isModPresent() {
        return modPresent;
    }

    /**
     * @return true the first time it is called for this connection
     */
    public boolean markModPresent() {
        final boolean first = !modPresent;
        modPresent = true;
        return first;
    }

    /**
     * @return true the first time it is called for this connection
     */
    public boolean markAnnounced() {
        final boolean first = !announced;
        announced = true;
        return first;
    }
}
