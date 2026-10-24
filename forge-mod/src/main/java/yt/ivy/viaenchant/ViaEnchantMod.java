package yt.ivy.viaenchant;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import yt.ivy.viaenchant.client.ClientHandler;

@Mod(
    modid = ViaEnchantMod.MODID,
    version = Tags.VERSION,
    name = "ViaEnchant",
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*")
public class ViaEnchantMod {

    public static final String MODID = "viaenchant";
    public static final Logger LOG = LogManager.getLogger(MODID);

    /** Plugin message channel used by the ViaEnchant ViaProxy plugin. */
    public static final String CHANNEL = "ViaEnchant";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        if (event.getSide()
            .isClient()) {
            ClientHandler.register();
        }
    }
}
