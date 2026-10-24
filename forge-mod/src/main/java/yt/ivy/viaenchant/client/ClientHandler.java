package yt.ivy.viaenchant.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiEnchantment;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerEnchantment;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLEventChannel;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import yt.ivy.viaenchant.ClueStore;
import yt.ivy.viaenchant.ViaEnchantMod;

public class ClientHandler {

    // GuiEnchantment layout (same in 1.7.10 and 1.8)
    private static final int GUI_WIDTH = 176;
    private static final int GUI_HEIGHT = 166;
    private static final int BUTTON_X = 60;
    private static final int BUTTON_Y = 14;
    private static final int BUTTON_WIDTH = 108;
    private static final int BUTTON_HEIGHT = 17;
    private static final int BUTTON_SPACING = 19;

    // Enchantment added in 1.8 which has no vanilla 1.7.10 equivalent
    private static final int DEPTH_STRIDER_ID = 8;

    private final FMLEventChannel channel;

    private ClientHandler(FMLEventChannel channel) {
        this.channel = channel;
    }

    public static void register() {
        FMLEventChannel channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(ViaEnchantMod.CHANNEL);
        ClientHandler handler = new ClientHandler(channel);
        channel.register(handler);
        MinecraftForge.EVENT_BUS.register(handler);
        FMLCommonHandler.instance()
            .bus()
            .register(handler);
    }

    /**
     * Tells the ViaEnchant ViaProxy plugin that this client has the mod, so it can skip the chat messages. The plugin
     * drops the packet, a normal server never sees it.
     */
    @SubscribeEvent
    public void onConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        ByteBuf hello = Unpooled.buffer(1);
        hello.writeByte(1);
        channel.sendToServer(new FMLProxyPacket(hello, ViaEnchantMod.CHANNEL));
    }

    @SubscribeEvent
    public void onPacket(FMLNetworkEvent.ClientCustomPacketEvent event) {
        FMLProxyPacket packet = event.packet;
        ByteBuf buf = packet.payload();
        if (buf.readableBytes() < 5) {
            return;
        }
        int windowId = buf.readByte() & 0xFF;
        int property = buf.readShort();
        int value = buf.readShort();
        ViaEnchantMod.LOG.info("Enchanting table window {} property {} = {}", windowId, property, value);
        ClueStore.update(windowId, property, value);
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        ClueStore.clear();
    }

    @SubscribeEvent
    public void onDrawScreen(GuiScreenEvent.DrawScreenEvent.Post event) {
        GuiScreen gui = event.gui;
        if (!(gui instanceof GuiEnchantment)) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        Container container = mc.thePlayer == null ? null : mc.thePlayer.openContainer;
        if (!(container instanceof ContainerEnchantment)) {
            return;
        }
        ContainerEnchantment table = (ContainerEnchantment) container;

        int left = (gui.width - GUI_WIDTH) / 2;
        int top = (gui.height - GUI_HEIGHT) / 2;
        for (int slot = 0; slot < ClueStore.SLOTS; slot++) {
            int x = event.mouseX - (left + BUTTON_X);
            int y = event.mouseY - (top + BUTTON_Y + BUTTON_SPACING * slot);
            if (x < 0 || y < 0 || x >= BUTTON_WIDTH || y >= BUTTON_HEIGHT) {
                continue;
            }

            int requirement = table.enchantLevels[slot];
            int clueId = ClueStore.getClueId(table.windowId, slot);
            int clueLevel = ClueStore.getClueLevel(table.windowId, slot);
            if (requirement <= 0 || clueId < 0 || clueLevel < 0) {
                return;
            }

            drawTooltip(gui, event.mouseX, event.mouseY, buildTooltip(clueId, clueLevel));
            return;
        }
    }

    private static List<String> buildTooltip(int clueId, int clueLevel) {
        List<String> lines = new ArrayList<String>();
        lines.add(
            EnumChatFormatting.WHITE.toString() + EnumChatFormatting.ITALIC
                + enchantmentName(clueId, clueLevel)
                + " . . . ?");
        return lines;
    }

    private static String enchantmentName(int id, int level) {
        if (id >= 0 && id < Enchantment.enchantmentsList.length && Enchantment.enchantmentsList[id] != null) {
            return Enchantment.enchantmentsList[id].getTranslatedName(level);
        }
        String later = laterVersionName(id);
        if (later != null) {
            return later + " " + romanLevel(level);
        }
        return "Enchantment #" + id + " " + romanLevel(level);
    }

    /** Enchantments that 1.7.10 does not have, as numbered by 1.8 to 1.12 servers. */
    private static String laterVersionName(int id) {
        switch (id) {
            case DEPTH_STRIDER_ID:
                return "Depth Strider";
            case 9:
                return "Frost Walker";
            case 10:
                return "Curse of Binding";
            case 22:
                return "Sweeping Edge";
            case 70:
                return "Mending";
            case 71:
                return "Curse of Vanishing";
            default:
                return null;
        }
    }

    private static String romanLevel(int level) {
        String key = "enchantment.level." + level;
        String translated = StatCollector.translateToLocal(key);
        return key.equals(translated) ? String.valueOf(level) : translated;
    }

    private static void drawTooltip(GuiScreen gui, int mouseX, int mouseY, List<String> lines) {
        TooltipScreen.INSTANCE.draw(gui, lines, mouseX, mouseY);
    }

    /** drawHoveringText is protected, so the vanilla tooltip renderer is reached through a GuiScreen subclass. */
    private static class TooltipScreen extends GuiScreen {

        static final TooltipScreen INSTANCE = new TooltipScreen();

        void draw(GuiScreen from, List<String> lines, int mouseX, int mouseY) {
            this.width = from.width;
            this.height = from.height;
            this.fontRendererObj = Minecraft.getMinecraft().fontRenderer;
            drawHoveringText(lines, mouseX, mouseY, this.fontRendererObj);
        }
    }
}
