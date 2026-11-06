package dev.pulse.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.pulse.client.module.ModuleManager;
import dev.pulse.client.spotify.SpotifyClient;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class PulseCommands {
    private PulseCommands() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> d, CommandBuildContext ctx) {
        d.register(literal("pulse")
                .executes(c -> {
                    c.getSource().sendFeedback(Component.literal("Press Right Shift (rebindable in Client Settings) to open the Lone Client menu.")
                            .withStyle(ChatFormatting.LIGHT_PURPLE));
                    c.getSource().sendFeedback(Component.literal("/pulse spotify id|login|logout|play|pause|next|previous, /pulse reset, /pulse waypoint add|remove|list|clear")
                            .withStyle(ChatFormatting.GRAY));
                    return 1;
                })
                .then(literal("reset").executes(c -> {
                    ModuleManager.resetLayout();
                    PulseConfig.save();
                    c.getSource().sendFeedback(Component.literal("HUD layout reset.").withStyle(ChatFormatting.GREEN));
                    return 1;
                }))
                .then(literal("waypoint")
                        .then(literal("add").then(argument("name", StringArgumentType.word()).executes(c -> {
                            String n = StringArgumentType.getString(c, "name");
                            boolean ok = ModuleManager.waypoints().add(Minecraft.getInstance(), n);
                            c.getSource().sendFeedback(Component.literal(ok ? "Waypoint '" + n + "' saved here." : "Join a world first.")
                                    .withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED));
                            return 1;
                        })))
                        .then(literal("remove").then(argument("name", StringArgumentType.word()).executes(c -> {
                            String n = StringArgumentType.getString(c, "name");
                            boolean ok = ModuleManager.waypoints().remove(n);
                            c.getSource().sendFeedback(Component.literal(ok ? "Removed '" + n + "'." : "No waypoint named '" + n + "'.")
                                    .withStyle(ok ? ChatFormatting.YELLOW : ChatFormatting.RED));
                            return 1;
                        })))
                        .then(literal("list").executes(c -> {
                            var all = ModuleManager.waypoints().all();
                            if (all.isEmpty()) {
                                c.getSource().sendFeedback(Component.literal("No waypoints saved.").withStyle(ChatFormatting.GRAY));
                            }
                            for (var w : all) {
                                c.getSource().sendFeedback(Component.literal(w.name + "  " + (int) w.x + " " + (int) w.y + " " + (int) w.z)
                                        .withStyle(ChatFormatting.AQUA));
                            }
                            return 1;
                        }))
                        .then(literal("clear").executes(c -> {
                            ModuleManager.waypoints().clear();
                            c.getSource().sendFeedback(Component.literal("All waypoints cleared.").withStyle(ChatFormatting.YELLOW));
                            return 1;
                        })))
                .then(literal("spotify")
                        .then(literal("id").then(argument("clientId", StringArgumentType.word()).executes(c -> {
                            SpotifyClient.setClientId(StringArgumentType.getString(c, "clientId"));
                            c.getSource().sendFeedback(Component.literal("Spotify client ID saved. Redirect URI to register: "
                                    + SpotifyClient.REDIRECT).withStyle(ChatFormatting.GREEN));
                            return 1;
                        })))
                        .then(literal("login").executes(c -> {
                            SpotifyClient.login();
                            return 1;
                        }))
                        .then(literal("logout").executes(c -> {
                            SpotifyClient.logout();
                            c.getSource().sendFeedback(Component.literal("Spotify disconnected.").withStyle(ChatFormatting.YELLOW));
                            return 1;
                        }))
                        .then(literal("play").executes(c -> {
                            SpotifyClient.control("play");
                            return 1;
                        }))
                        .then(literal("pause").executes(c -> {
                            SpotifyClient.control("pause");
                            return 1;
                        }))
                        .then(literal("next").executes(c -> {
                            SpotifyClient.control("next");
                            return 1;
                        }))
                        .then(literal("previous").executes(c -> {
                            SpotifyClient.control("previous");
                            return 1;
                        }))));
    }
}
