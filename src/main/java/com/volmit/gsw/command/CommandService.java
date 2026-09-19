package com.volmit.gsw.command;

import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.DirectorEngineOptions;
import art.arcane.volmlib.util.director.context.DirectorContextRegistry;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.director.runtime.DirectorExecutionResult;
import art.arcane.volmlib.util.director.runtime.DirectorInvocation;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorSender;
import art.arcane.volmlib.util.localization.LanguageAudience;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.gsw.GamemodeSwitcher;
import com.volmit.gsw.localization.LanguageService;
import com.volmit.gsw.localization.SwitcherMessages;
import com.volmit.gsw.presentation.ChatMenuStyle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public final class CommandService implements CommandExecutor, TabCompleter {
    private final GamemodeSwitcher plugin;
    private final DirectorRuntimeEngine director;

    public CommandService(GamemodeSwitcher plugin) {
        this.plugin = plugin;
        director = DirectorEngineFactory.create(new SwitcherCommands(plugin), DirectorEngineOptions.builder()
                .contexts(contexts()).textResolver(plugin.getLanguageService().directorResolver()).build());
    }

    public void register() {
        PluginCommand command = Objects.requireNonNull(plugin.getCommand("gamemodeswitcher"), "Missing gamemodeswitcher command declaration");
        command.setExecutor(this);
        command.setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("gamemodeswitcher")) {
            return false;
        }
        String[] arguments = args.clone();
        Runnable execution = () -> execute(sender, label, arguments);
        if (sender instanceof Player player) {
            FoliaScheduler.runEntity(plugin, player, execution);
        } else {
            execution.run();
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("gamemodeswitcher")) {
            return List.of();
        }
        UUID audience = sender instanceof Player player ? player.getUniqueId() : null;
        return LanguageAudience.call(audience, () -> complete(sender, alias, args));
    }

    private void execute(CommandSender sender, String label, String[] args) {
        UUID audience = sender instanceof Player player ? player.getUniqueId() : null;
        LanguageAudience.call(audience, () -> {
            executeOwned(sender, label, args);
            return true;
        });
    }

    private void executeOwned(CommandSender sender, String label, String[] args) {
        try {
            if (args.length > 0 && args[0].equalsIgnoreCase("language")) {
                plugin.getLanguageSwitcher().command(sender, Arrays.copyOfRange(args, 1, args.length));
                return;
            }
            Optional<DirectorMiniMenu.DirectorHelpPage> help = DirectorMiniMenu.resolveHelp(director, Arrays.asList(args));
            if (help.isPresent()) {
                deliverHelp(sender, help.get());
                return;
            }
            List<String> arguments = normalizeArguments(args);
            if (args.length == 1 && args[0].equalsIgnoreCase("toggle") && sender instanceof Player player) {
                arguments = List.of("toggle", "enabled=" + !plugin.getSwitchService().enabled(player));
            }
            DirectorExecutionResult result = director.execute(new DirectorInvocation(
                    new BukkitSender(sender, plugin.getLanguageService()), label, arguments));
            if (!result.isHandled()) {
                DirectorMiniMenu.resolveHelp(director, List.of()).ifPresent(page -> deliverHelp(sender, page));
            }
        } catch (RuntimeException | LinkageError failure) {
            plugin.getLogger().log(Level.SEVERE, "GamemodeSwitcher command failed", failure);
            plugin.getLanguageService().sendPrefixed(sender, SwitcherMessages.COMMAND_FAILED);
        }
    }

    private List<String> complete(CommandSender sender, String alias, String[] args) {
        try {
            if (args.length > 0 && args[0].equalsIgnoreCase("language")) {
                return plugin.getLanguageSwitcher().complete(sender, Arrays.copyOfRange(args, 1, args.length));
            }
            return director.tabComplete(new DirectorInvocation(new BukkitSender(sender, plugin.getLanguageService()),
                    alias, Arrays.asList(args)));
        } catch (RuntimeException | LinkageError failure) {
            plugin.getLogger().log(Level.SEVERE, "GamemodeSwitcher completion failed", failure);
            return List.of();
        }
    }

    private void deliverHelp(CommandSender sender, DirectorMiniMenu.DirectorHelpPage help) {
        if (!sender.hasPermission("gamemodeswitcher.command")) {
            plugin.getLanguageService().sendPrefixed(sender, SwitcherMessages.NO_PERMISSION);
            return;
        }
        DirectorMiniMenu.deliver(sender, help, ChatMenuStyle.theme(), plugin.getLanguageService().directorResolver());
    }

    private DirectorContextRegistry contexts() {
        DirectorContextRegistry contexts = new DirectorContextRegistry();
        contexts.register(CommandSender.class, (invocation, values) ->
                invocation.getSender() instanceof BukkitSender sender ? sender.sender() : null);
        return contexts;
    }

    static List<String> normalizeArguments(String[] arguments) {
        ArrayList<String> normalized = new ArrayList<>(Arrays.asList(arguments));
        if (arguments.length == 2 && !arguments[1].contains("=")) {
            String parameter = switch (arguments[0].toLowerCase(Locale.ROOT)) {
                case "set" -> "mode";
                case "toggle" -> "enabled";
                default -> null;
            };
            if (parameter != null) {
                normalized.set(1, parameter + "=" + arguments[1]);
            }
        }
        if (arguments.length == 3 && arguments[0].equalsIgnoreCase("debug")
                && arguments[1].equalsIgnoreCase("dump") && !arguments[2].contains("=")) {
            normalized.set(2, "upload=" + arguments[2]);
        }
        return List.copyOf(normalized);
    }

    private record BukkitSender(CommandSender sender, LanguageService language) implements DirectorSender {
        @Override
        public String getName() {
            return sender.getName();
        }

        @Override
        public boolean isPlayer() {
            return sender instanceof Player;
        }

        @Override
        public void sendMessage(String message) {
            if (message != null && !message.isBlank()) {
                ComponentMessenger.send(sender, ComponentText.markup(language.render(sender, SwitcherMessages.PREFIX))
                        .append(ComponentText.literal(message)));
            }
        }
    }
}
