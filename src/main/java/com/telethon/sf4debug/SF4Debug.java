package com.telethon.sf4debug;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.Logger;

/**
 * Client-only Forge mod that starts a loopback HTTP server exposing
 * read-only JSON snapshots of Minecraft client state.
 *
 * <p>Configure the listen port with -Dsf4debug.port=25580 (default 25580).
 * Configure the bind host with -Dsf4debug.host=127.0.0.1 (default loopback).
 */
@Mod(
    modid = SF4Debug.MODID,
    name = "SkyFactory 4 Debug Port",
    version = "0.6.4",
    clientSideOnly = true,
    acceptableRemoteVersions = "*"
)
public final class SF4Debug {

    public static final String MODID = "sf4debug";

    public static Logger LOG;

    @Mod.EventHandler
    @SideOnly(Side.CLIENT)
    public void preInit(FMLPreInitializationEvent event) {
        LOG = event.getModLog();
    }

    @Mod.EventHandler
    @SideOnly(Side.CLIENT)
    public void init(FMLInitializationEvent event) {
        // Register the tick-based input simulator (used by /walk, /jump,
        // /holdAttack, ...). Must happen before DebugHttpServer.start
        // so the first incoming request never races the registration.
        TickInput.register();
        // Register event-bus subscribers for /events long-poll.
        EventStream.init();
        // Register perception event subscribers (particles, sounds).
        PerceptionRoutes.init();
        // Register the "don't pause on minimize" event subscriber.
        // Picks up -Dsf4debug.noPauseOnMinimize=1 as the startup default.
        PauseRoutes.init();
        // Register the tick-rate throttle subscriber (ServerTickEvent).
        // Idle until /tickrate or the /tickrate command changes the rate.
        TickRateRoutes.init();
        // Register the client-side /tickrate command via Forge's
        // ClientCommandHandler. Chat input is intercepted locally so
        // the command never reaches a remote server.
        TickRateCommand.register();
        int port = Integer.getInteger("sf4debug.port", 25580);
        String host = System.getProperty("sf4debug.host", "127.0.0.1");
        try {
            DebugHttpServer.start(host, port);
            LOG.info("sf4debug listening on http://{}:{}/", host, port);
        } catch (Throwable t) {
            LOG.error("sf4debug failed to start on {}:{}", host, port, t);
        }
    }
}
