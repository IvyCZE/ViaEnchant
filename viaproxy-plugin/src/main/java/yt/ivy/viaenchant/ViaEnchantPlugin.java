package yt.ivy.viaenchant;

import com.viaversion.viarewind.protocol.v1_7_6_10to1_7_2_5.packet.ClientboundPackets1_7_2_5;
import com.viaversion.viarewind.protocol.v1_7_6_10to1_7_2_5.packet.ServerboundPackets1_7_2_5;
import com.viaversion.viarewind.protocol.v1_8to1_7_6_10.Protocol1_8To1_7_6_10;
import com.viaversion.viarewind.protocol.v1_8to1_7_6_10.storage.InventoryTracker;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_8to1_9.packet.ClientboundPackets1_8;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Level;
import net.lenni0451.lambdaevents.EventHandler;
import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.plugins.ViaProxyPlugin;
import net.raphimc.viaproxy.plugins.events.ViaProxyLoadedEvent;

/**
 * ViaRewind drops enchanting table window properties above 2 (seed and enchantment clues) when translating a 1.8
 * server to a 1.7 client. This plugin sees them first and forwards them to the client.
 * <p>
 * Payload on {@link #CHANNEL}: byte window id, short property, short value. Clue values (properties 4-6)
 * are packed as {@code enchantmentId | level << 8} with the old (1.8) enchantment ids.
 */
public class ViaEnchantPlugin extends ViaProxyPlugin {

    public static final String CHANNEL = "ViaEnchant";

    private static final int ENCHANTING_TABLE_WINDOW_TYPE = 4;

    private enum ChatMode {
        AUTO, ALWAYS, NEVER
    }

    private ChatMode chatMode = ChatMode.AUTO;
    private boolean debug;

    @Override
    public void onEnable() {
        loadConfig();
        ViaProxy.EVENT_MANAGER.register(this);
    }

    @EventHandler
    public void onViaProxyLoaded(final ViaProxyLoadedEvent event) {
        try {
            final ProtocolManager protocolManager = Via.getManager().getProtocolManager();
            protocolManager.completeMappingDataLoading(Protocol1_8To1_7_6_10.class);
            final Protocol1_8To1_7_6_10 protocol = protocolManager.getProtocol(Protocol1_8To1_7_6_10.class);
            if (protocol == null) {
                Via.getPlatform().getLogger().warning("[ViaEnchant] ViaRewind 1.8 -> 1.7 protocol not found, plugin is inactive");
                return;
            }

            protocol.appendClientbound(ClientboundPackets1_8.OPEN_SCREEN, wrapper -> state(wrapper.user()).reset());
            protocol.appendClientbound(ClientboundPackets1_8.CONTAINER_CLOSE, wrapper -> state(wrapper.user()).reset());
            protocol.appendClientbound(ClientboundPackets1_8.CONTAINER_SET_DATA, this::onWindowProperty);
            protocol.appendServerbound(ServerboundPackets1_7_2_5.CUSTOM_PAYLOAD, this::onClientPayload);
            Via.getPlatform().getLogger().info("[ViaEnchant] Hooked into ViaRewind (chat fallback: " + chatMode.name().toLowerCase(Locale.ROOT) + ")");
        } catch (final Throwable t) {
            Via.getPlatform().getLogger().log(Level.SEVERE, "[ViaEnchant] Failed to hook into ViaRewind", t);
        }
    }

    /**
     * The Forge mod announces itself with a payload on {@link #CHANNEL} after joining. Forge also lists its channels
     * in a "REGISTER" payload, which is checked as well. The announcement is not meant for the server and is dropped.
     */
    private void onClientPayload(final PacketWrapper wrapper) {
        try {
            final String channel = wrapper.get(Types.STRING, 0);
            if (CHANNEL.equals(channel)) {
                wrapper.cancel();
                markModPresent(wrapper.user());
            } else if ("REGISTER".equals(channel)) {
                final byte[] data = wrapper.passthrough(Types.REMAINING_BYTES);
                for (final String registered : new String(data, StandardCharsets.UTF_8).split("\u0000")) {
                    if (CHANNEL.equals(registered)) {
                        markModPresent(wrapper.user());
                    }
                }
            }
        } catch (final Throwable t) {
            Via.getPlatform().getLogger().log(Level.WARNING, "[ViaEnchant] Failed to process client payload", t);
        }
    }

    private void markModPresent(final UserConnection user) {
        final EnchantState state = state(user);
        if (state.markModPresent()) {
            Via.getPlatform().getLogger().info("[ViaEnchant] Client has the ViaEnchant mod, chat messages are not needed");
        }
    }

    private static boolean isOldClient(final UserConnection user) {
        final ProtocolVersion client = user.getProtocolInfo().protocolVersion();
        return client.newerThanOrEqualTo(ProtocolVersion.v1_7_2) && client.olderThan(ProtocolVersion.v1_8);
    }

    private void onWindowProperty(final PacketWrapper wrapper) {
        try {
            final UserConnection user = wrapper.user();
            if (!isOldClient(user)) {
                return;
            }
            final InventoryTracker inventoryTracker = user.get(InventoryTracker.class);
            if (inventoryTracker == null) {
                return;
            }

            final short windowId = wrapper.get(Types.UNSIGNED_BYTE, 0);
            if (inventoryTracker.get(windowId) != ENCHANTING_TABLE_WINDOW_TYPE) {
                return;
            }

            final short property = wrapper.get(Types.SHORT, 0);
            final short value = wrapper.get(Types.SHORT, 1);
            if (property < 0 || property > EnchantState.LAST_PROPERTY) {
                return;
            }

            final EnchantState state = state(user);
            final boolean changed = state.update(windowId, property, value);
            if (changed) {
                if (debug) {
                    Via.getPlatform().getLogger().info("[ViaEnchant] window " + windowId + " property " + property + " = " + value
                            + (property >= EnchantState.FIRST_CLUE_PROPERTY && value != -1 ? " (enchantment id " + (value & 0xFF) + ", level " + (value >> 8) + ")" : ""));
                }
                if (property >= 3) {
                    sendPayload(user, windowId, property, value);
                }
            }

            // The server sends the properties in order, so the last one completes a burst
            if (property == EnchantState.LAST_PROPERTY && state.isDirty()) {
                state.clearDirty();
                if (state.markAnnounced()) {
                    Via.getPlatform().getLogger().info("[ViaEnchant] Receiving enchanting table clue data from the server (window " + windowId + ")");
                }
                if (chatMode == ChatMode.ALWAYS || chatMode == ChatMode.AUTO && !state.isModPresent()) {
                    sendChat(user, state);
                }
            }
        } catch (final Throwable t) {
            Via.getPlatform().getLogger().log(Level.WARNING, "[ViaEnchant] Failed to process window property", t);
        }
    }

    private void sendPayload(final UserConnection user, final short windowId, final short property, final short value) throws Exception {
        final byte[] data = ByteBuffer.allocate(5).put((byte) windowId).putShort(property).putShort(value).array();

        final PacketWrapper packet = PacketWrapper.create(ClientboundPackets1_7_2_5.CUSTOM_PAYLOAD, user);
        packet.write(Types.STRING, CHANNEL);
        packet.write(Types.SHORT_BYTE_ARRAY, data);
        packet.send(Protocol1_8To1_7_6_10.class);
    }

    private void sendChat(final UserConnection user, final EnchantState state) throws Exception {
        for (int slot = 0; slot < 3; slot++) {
            final int cost = state.get(slot);
            if (cost <= 0 || !state.hasClue(slot)) {
                continue;
            }

            final String message = "\u00a7d[Enchant] \u00a77Slot " + (slot + 1) + " (\u00a7aLvl " + cost + "\u00a77): \u00a7f"
                    + EnchantNames.format(state.clueEnchantmentId(slot), state.clueLevel(slot)) + " \u00a77. . . ?";
            final PacketWrapper packet = PacketWrapper.create(ClientboundPackets1_7_2_5.CHAT, user);
            packet.write(Types.STRING, "{\"text\":\"" + message + "\"}");
            packet.send(Protocol1_8To1_7_6_10.class);
        }
    }

    private static EnchantState state(final UserConnection user) {
        EnchantState state = user.get(EnchantState.class);
        if (state == null) {
            state = new EnchantState(user);
            user.put(state);
        }
        return state;
    }

    private void loadConfig() {
        final File folder = getDataFolder();
        final File file = new File(folder, "config.properties");
        final Properties properties = new Properties();
        try {
            if (file.exists()) {
                try (InputStream in = Files.newInputStream(file.toPath())) {
                    properties.load(in);
                }
            } else {
                folder.mkdirs();
                properties.setProperty("chat-fallback", "auto");
                properties.setProperty("debug", "false");
                try (OutputStream out = Files.newOutputStream(file.toPath())) {
                    properties.store(out, "chat-fallback: auto = print the enchantment clue in chat only if the client does not have the Forge mod, true = always, false = never\n"
                            + "debug: log every enchanting table window property in the ViaProxy log");
                }
            }
        } catch (final IOException e) {
            Via.getPlatform().getLogger().log(Level.WARNING, "[ViaEnchant] Could not read config, using defaults", e);
        }
        final String mode = properties.getProperty("chat-fallback", "auto").trim().toLowerCase(Locale.ROOT);
        chatMode = mode.equals("true") ? ChatMode.ALWAYS : mode.equals("false") ? ChatMode.NEVER : ChatMode.AUTO;
        debug = Boolean.parseBoolean(properties.getProperty("debug", "false"));
    }
}
