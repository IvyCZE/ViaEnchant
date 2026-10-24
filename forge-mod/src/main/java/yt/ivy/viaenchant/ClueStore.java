package yt.ivy.viaenchant;

import java.util.Arrays;

/**
 * Enchantment clues of the enchanting table window the server sent last. Written from the network thread and read
 * from the render thread.
 * <p>
 * 1.8 sends the clue of each slot as window property 4 + slot, packed as {@code enchantmentId | level << 8}, and
 * -1 if the slot has no clue.
 */
public final class ClueStore {

    public static final int SLOTS = 3;

    private static final int FIRST_CLUE_PROPERTY = 4;

    private static int windowId = -1;
    private static final short[] packedClues = new short[SLOTS];

    static {
        clear();
    }

    private ClueStore() {}

    public static synchronized void clear() {
        windowId = -1;
        Arrays.fill(packedClues, (short) -1);
    }

    public static synchronized void update(int windowId, int property, int value) {
        if (ClueStore.windowId != windowId) {
            clear();
            ClueStore.windowId = windowId;
        }
        int slot = property - FIRST_CLUE_PROPERTY;
        if (slot >= 0 && slot < SLOTS) {
            packedClues[slot] = (short) value;
        }
    }

    /** @return the enchantment id of the clue, or -1 if the slot has none for this window */
    public static synchronized int getClueId(int windowId, int slot) {
        if (ClueStore.windowId != windowId || getClueLevel(windowId, slot) <= 0) {
            return -1;
        }
        return packedClues[slot] & 0xFF;
    }

    /** @return the enchantment level of the clue, or -1 if the slot has none for this window */
    public static synchronized int getClueLevel(int windowId, int slot) {
        if (ClueStore.windowId != windowId) {
            return -1;
        }
        int level = packedClues[slot] >> 8;
        return level > 0 ? level : -1;
    }
}
