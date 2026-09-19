package com.volmit.gsw.command;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.localization.TextKey;
import com.volmit.gsw.GamemodeSwitcher;
import com.volmit.gsw.config.ConfigService;
import com.volmit.gsw.config.RuntimeConfig;
import com.volmit.gsw.localization.LanguageService;
import com.volmit.gsw.localization.SwitcherMessages;
import com.volmit.gsw.presentation.ChatMenuStyle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CommandServiceTest {
    @TempDir
    Path directory;

    @Test
    public void acceptsPositionalAndNamedArguments() {
        assertEquals(List.of("set", "mode=creative"), CommandService.normalizeArguments(new String[]{"set", "creative"}));
        assertEquals(List.of("toggle", "enabled=false"), CommandService.normalizeArguments(new String[]{"toggle", "false"}));
        assertEquals(List.of("debug", "dump", "upload=false"),
                CommandService.normalizeArguments(new String[]{"debug", "dump", "false"}));
        assertEquals(List.of("set", "mode=adventure"),
                CommandService.normalizeArguments(new String[]{"set", "mode=adventure"}));
    }

    @Test
    public void preservesUnknownArgumentsForParserValidation() {
        assertEquals(List.of("unknown", "creative"),
                CommandService.normalizeArguments(new String[]{"unknown", "creative"}));
        assertEquals(List.of("set", "creative", "extra"),
                CommandService.normalizeArguments(new String[]{"set", "creative", "extra"}));
    }

    @Test
    public void everyCommandAndVisibleParameterHasAnEnglishCatalogEntry() {
        for (Class<?> type : List.of(SwitcherCommands.class, SwitcherDebugCommands.class)) {
            Director group = type.getAnnotation(Director.class);
            requireDescription(group.descriptionKey(), group.description());
            for (Method method : type.getDeclaredMethods()) {
                Director command = method.getAnnotation(Director.class);
                if (command == null) {
                    continue;
                }
                requireDescription(command.descriptionKey(), command.description());
                for (Parameter parameter : method.getParameters()) {
                    Param definition = parameter.getAnnotation(Param.class);
                    if (definition != null && !definition.contextual()) {
                        requireDescription(definition.descriptionKey(), definition.description());
                    }
                }
            }
        }
    }

    @Test
    public void directorDiscoversTheNestedDebugGroup() {
        DirectorRuntimeEngine director = DirectorEngineFactory.create(new SwitcherCommands(null));
        DirectorMiniMenu.DirectorHelpPage root = DirectorMiniMenu.resolveHelp(director, List.of()).orElseThrow();
        DirectorMiniMenu.DirectorHelpPage debug = DirectorMiniMenu.resolveHelp(director, List.of("debug")).orElseThrow();
        assertFalse(root.entries().stream().anyMatch(node -> node.getDescriptor().getName().equals("version")));
        assertTrue(debug.entries().stream().anyMatch(node -> node.getDescriptor().getName().equals("version")));
    }

    @Test
    public void bothVersionPathsSendOnlyTheInstalledVersion() {
        LanguageService language = new LanguageService(directory.toFile(), Logger.getAnonymousLogger());
        GamemodeSwitcher plugin = commandPlugin(language);
        when(plugin.getDescription()).thenReturn(new PluginDescriptionFile("GamemodeSwitcher", "2.7.4", "example.Main"));
        RemoteConsoleCommandSender sender = mock(RemoteConsoleCommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        Command command = mock(Command.class);
        when(command.getName()).thenReturn("gamemodeswitcher");
        CommandService service = new CommandService(plugin);
        try {
            for (String[] arguments : List.of(new String[]{"version"}, new String[]{"debug", "version"})) {
                clearInvocations(sender);
                assertTrue(service.onCommand(sender, command, "gsw", arguments));
                ArgumentCaptor<String> output = ArgumentCaptor.forClass(String.class);
                verify(sender).sendMessage(output.capture());
                assertEquals("GamemodeSwitcher v2.7.4", output.getValue());
            }
        } finally {
            language.close();
        }
    }

    @Test
    public void rendersRootAndDebugHelpThroughThePluginLanguageResolver() {
        LanguageService language = new LanguageService(directory.toFile(), Logger.getAnonymousLogger());
        DirectorRuntimeEngine director = DirectorEngineFactory.create(new SwitcherCommands(null));
        try {
            for (List<String> arguments : List.of(List.<String>of(), List.of("debug"))) {
                DirectorMiniMenu.DirectorHelpPage page = DirectorMiniMenu.resolveHelp(director, arguments).orElseThrow();
                assertFalse(DirectorMiniMenu.render(page, ChatMenuStyle.theme(), language.directorResolver()).isEmpty());
                assertFalse(DirectorMiniMenu.renderConsole(page, language.directorResolver()).isEmpty());
            }
        } finally {
            language.close();
        }
    }

    @Test
    public void remoteConsoleReceivesHelpAndStatusBeforeTheCommandReturns() {
        LanguageService language = new LanguageService(directory.toFile(), Logger.getAnonymousLogger());
        GamemodeSwitcher plugin = commandPlugin(language);
        RemoteConsoleCommandSender sender = mock(RemoteConsoleCommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        Command command = mock(Command.class);
        when(command.getName()).thenReturn("gamemodeswitcher");
        CommandService service = new CommandService(plugin);
        try {
            for (String[] arguments : List.of(new String[0], new String[]{"status"})) {
                clearInvocations(sender);
                assertTrue(service.onCommand(sender, command, "gsw", arguments));
                ArgumentCaptor<String> output = ArgumentCaptor.forClass(String.class);
                verify(sender, atLeastOnce()).sendMessage(output.capture());
                assertTrue(output.getAllValues().stream().anyMatch(line -> !line.isBlank()));
                for (String line : output.getAllValues()) {
                    assertFalse(line.matches("(?is).*&[0-9a-fklmnor].*"), line);
                }
            }
        } finally {
            language.close();
        }
    }

    @Test
    public void completesTheCurrentPlayerAndAdministrationCommands() {
        LanguageService language = new LanguageService(directory.toFile(), Logger.getAnonymousLogger());
        GamemodeSwitcher plugin = commandPlugin(language);
        RemoteConsoleCommandSender sender = mock(RemoteConsoleCommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        Command command = mock(Command.class);
        when(command.getName()).thenReturn("gamemodeswitcher");
        try {
            List<String> completions = new CommandService(plugin).onTabComplete(sender, command, "gsw", new String[]{""});
            assertEquals(List.of("config", "debug", "language", "menu", "set", "status", "toggle"),
                    completions.stream().sorted().toList());
        } finally {
            language.close();
        }
    }

    @Test
    public void debugReportsUseTheSuiteUploadDefault() throws NoSuchMethodException {
        Method dump = SwitcherDebugCommands.class.getDeclaredMethod("dump", boolean.class, CommandSender.class);
        assertEquals("true", dump.getParameters()[0].getAnnotation(Param.class).defaultValue());
    }

    private GamemodeSwitcher commandPlugin(LanguageService language) {
        GamemodeSwitcher plugin = mock(GamemodeSwitcher.class);
        when(plugin.getLanguageService()).thenReturn(language);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.schedulerName()).thenReturn("Bukkit main thread");
        ConfigService config = mock(ConfigService.class);
        RuntimeConfig runtime = mock(RuntimeConfig.class);
        when(runtime.language()).thenReturn("en_US");
        when(config.runtime()).thenReturn(runtime);
        when(plugin.getConfigService()).thenReturn(config);
        return plugin;
    }

    private void requireDescription(String key, String english) {
        assertFalse(key.isBlank());
        assertNotNull(SwitcherMessages.catalog().require(key));
        assertEquals(SwitcherMessages.catalog().require(key), TextKey.of(key, english));
    }
}
