package com.telethon.sf4debug;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Arrays;
import java.util.List;

/**
 * Client-side {@code /tickrate} command. Registered through
 * {@link ClientCommandHandler} so chat input is intercepted before it
 * reaches the server — which is critical because the change is
 * applied locally (to the integrated server and client {@code Timer})
 * and would be meaningless to forward to a remote dedicated server.
 *
 * <p>Usage:
 *
 * <ul>
 *   <li>{@code /tickrate}          — print the current rate.</li>
 *   <li>{@code /tickrate reset}    — restore the vanilla 20 TPS.</li>
 *   <li>{@code /tickrate <tps>}    — set the rate (1-100). Only works
 *       in single-player; the command rejects the call in multiplayer
 *       with a useful error message because
 *       {@link Minecraft#isIntegratedServerRunning()} is {@code false}
 *       there.</li>
 * </ul>
 *
 * <p>Permission: {@link #getRequiredPermissionLevel()} returns
 * {@code 0} and {@link #checkPermission(MinecraftServer, ICommandSender)}
 * returns {@code true} unconditionally. This is intentional — the
 * request is "give permission to the client in single-player by
 * default". Client-command execution already bypasses the vanilla
 * {@code sender.canUseCommand} check when {@code server == null}, but
 * in single-player {@code ClientCommandHandler.getServer()} returns
 * the integrated server (non-null), so without an explicit override
 * the default OP-level-4 check would reject the call when the world
 * was created with "Cheats: OFF".</p>
 */
@SideOnly(Side.CLIENT)
public final class TickRateCommand extends CommandBase {

    /** Register via {@code ClientCommandHandler.instance.registerCommand(new TickRateCommand())}. */
    public TickRateCommand() {}

    @Override
    public String getName() {
        return "tickrate";
    }

    @Override
    public List<String> getAliases() {
        // Namespaced alias in case another mod ships a /tickrate.
        return Arrays.asList("sf4tickrate");
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/tickrate [<tps>|reset]";
    }

    /**
     * No OP check. The command mutates only client-local state (the
     * integrated server thread + the client {@code Timer}) so it's
     * safe to expose at permission level 0 to any single-player
     * session — including worlds created with "Cheats: OFF" where
     * the player would normally be permission level 0.
     */
    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    /**
     * Always permitted on the client. {@code ClientCommandHandler}
     * would otherwise require OP-level equal to
     * {@link #getRequiredPermissionLevel()} because in single-player
     * it passes the integrated {@link MinecraftServer} into this
     * method (so {@code CommandBase.checkPermission}'s
     * {@code server == null} short-circuit doesn't kick in).
     */
    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return true;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            int current = TickRateRoutes.getTargetTps();
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY
                    + "Current tick rate: " + TextFormatting.AQUA + current + " TPS"
                    + TextFormatting.GRAY + " (vanilla " + TickRateRoutes.DEFAULT_TPS + ")"));
            return;
        }
        if (args.length > 1) {
            throw new WrongUsageException(getUsage(sender));
        }
        String arg = args[0].trim();
        if (arg.equalsIgnoreCase("reset") || arg.equalsIgnoreCase("default")) {
            String err = TickRateRoutes.applyTickRate(TickRateRoutes.DEFAULT_TPS);
            if (err != null) throw new CommandException(err);
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN
                    + "Tick rate reset to " + TickRateRoutes.DEFAULT_TPS + " TPS"));
            return;
        }
        int tps;
        try {
            tps = Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            throw new WrongUsageException(getUsage(sender));
        }
        String err = TickRateRoutes.applyTickRate(tps);
        if (err != null) {
            throw new CommandException(err);
        }
        sender.sendMessage(new TextComponentString(TextFormatting.GREEN
                + "Tick rate set to " + TextFormatting.AQUA + tps + " TPS"
                + TextFormatting.GRAY
                + (tps == TickRateRoutes.DEFAULT_TPS
                    ? " (vanilla)"
                    : tps < TickRateRoutes.DEFAULT_TPS
                        ? " (slower than vanilla)"
                        : " (faster than vanilla — server capped at 20, client rendering faster)")));
    }

    /** Call from {@code SF4Debug.init} once the mod is loading on the client. */
    public static void register() {
        ClientCommandHandler.instance.registerCommand(new TickRateCommand());
    }
}
