package com.volmit.gsw;

import art.arcane.volmlib.util.config.BukkitConfigEditor;
import art.arcane.volmlib.util.config.ConfigEditorDocument;
import art.arcane.volmlib.util.config.TomlDocumentEditor;
import art.arcane.volmlib.util.diagnostics.BukkitDebugDump;
import art.arcane.volmlib.util.localization.BukkitLanguageSwitcher;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.RemoteLanguageCatalog;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.google.gson.JsonPrimitive;
import com.volmit.gsw.command.CommandService;
import com.volmit.gsw.config.ConfigReloader;
import com.volmit.gsw.config.ConfigService;
import com.volmit.gsw.config.RuntimeConfig;
import com.volmit.gsw.debug.SwitcherDebugContributor;
import com.volmit.gsw.gameplay.SwitchService;
import com.volmit.gsw.gui.ConfigMenu;
import com.volmit.gsw.gui.ModeSelector;
import com.volmit.gsw.localization.LanguageService;
import com.volmit.gsw.localization.SwitcherMessages;
import com.volmit.gsw.metrics.MetricsService;
import com.volmit.gsw.presentation.ChatMenuStyle;
import com.volmit.gsw.presentation.SplashScreen;
import com.volmit.gsw.presentation.SwitchFeedback;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class GamemodeSwitcher extends JavaPlugin {
    private final Object configurationLock = new Object();

    private ConfigService configService;
    private LanguageService languageService;
    private SwitchService switchService;
    private BukkitLanguageSwitcher languageSwitcher;
    private BukkitConfigEditor configEditor;
    private BukkitDebugDump debugDump;
    private ModeSelector selector;
    private ConfigReloader reloader;
    private MetricsService metricsService;
    private ExecutorService reloadWorker;
    private volatile boolean closing;

    @Override
    public void onEnable() {
        closing = false;
        try {
            configService = new ConfigService(getDataFolder());
            configService.initialize();
            languageService = new LanguageService(getDataFolder(), getLogger());
            languageService.remoteCatalogFailure().ifPresent(failure -> getLogger().log(Level.WARNING,
                    "Could not load GamemodeSwitcher language download sources", failure));
            languageService.initialize(configService.runtime().language());
            languageService.initializeSelections(() -> configService.runtime().language(), this::selectDefaultLanguage);
            reloadWorker = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "GamemodeSwitcher-Reload");
                thread.setDaemon(true);
                return thread;
            });
            selector = new ModeSelector(this);
            SwitchFeedback feedback = new SwitchFeedback(new SwitchFeedback.Dependencies(
                    this, configService, languageService));
            switchService = new SwitchService(new SwitchService.Dependencies(this, configService, languageService, feedback));
            getServer().getPluginManager().registerEvents(switchService, this);
            languageSwitcher = BukkitLanguageSwitcher.register(this, languageService.selections(),
                    new BukkitLanguageSwitcher.Options("gsw", "gamemodeswitcher.config", ChatMenuStyle.theme(),
                            languageService.directorResolver(), languageService.editorOptions(),
                            (sender, change) -> ComponentText.markup(languageService.render(sender,
                                    SwitcherMessages.CONFIG_SAVED, MessageArgs.builder()
                                            .untrusted("setting", change.key())
                                            .untrusted("old", compact(change.before()))
                                            .untrusted("new", compact(change.after())).build()))));
            configEditor = BukkitConfigEditor.register(this, new BukkitConfigEditor.Options(
                    this::loadConfigurationDocument, this::saveConfiguration,
                    new BukkitConfigEditor.Presentation("gamemodeswitcher.config", ChatMenuStyle.theme(),
                            languageService.directorResolver())));
            ConfigMenu.configure(this);
            debugDump = BukkitDebugDump.create(this, new BukkitDebugDump.Options(
                    () -> configService.runtime().debugUpload(), new SwitcherDebugContributor(this),
                    new BukkitDebugDump.Presentation("/gsw debug dump", "/gsw debug", ChatMenuStyle.theme(),
                            (key, arguments) -> ComponentText.literal(languageService.directorResolver().resolve(key, arguments)))));
            new CommandService(this).register();
            metricsService = new MetricsService(this);
            metricsService.reload(configService.runtime().metricsEnabled());
            reloader = new ConfigReloader(new ConfigReloader.Dependencies(this, configService,
                    this::hotReload, metricsService.configFile()));
            languageService.setSelfWriteListener(reloader::noteSelfWrite);
            reloader.start();
            requestConfiguredLanguage();
            SplashScreen.print(this);
            getLogger().info("GamemodeSwitcher enabled with " + schedulerName() + " scheduling.");
        } catch (Exception | LinkageError failure) {
            getLogger().log(Level.SEVERE, "GamemodeSwitcher could not initialize", failure);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        closing = true;
        if (languageService != null) {
            languageService.setSelfWriteListener(null);
        }
        closeService("configuration watcher", reloader);
        stopReloadWorker();
        closeService("bStats metrics", metricsService);
        closeService("game mode selector", selector);
        closeService("configuration editor", configEditor);
        closeService("language editor", languageSwitcher);
        closeService("diagnostics", debugDump);
        closeService("gesture preferences", switchService);
        if (languageService != null) {
            try {
                languageService.close();
            } catch (RuntimeException | LinkageError failure) {
                getLogger().log(Level.SEVERE, "Could not close GamemodeSwitcher languages", failure);
            }
        }
    }

    public String modeName(CommandSender sender, GameMode mode) {
        return ComponentText.markup(languageService.renderWithoutPrefix(sender, SwitcherMessages.mode(mode),
                MessageArgs.empty())).plain();
    }

    public String schedulerName() {
        return FoliaScheduler.isFolia(this) ? "Folia region/entity" : "Bukkit main thread";
    }

    public ConfigService getConfigService() {
        return configService;
    }

    public LanguageService getLanguageService() {
        return languageService;
    }

    public SwitchService getSwitchService() {
        return switchService;
    }

    public BukkitLanguageSwitcher getLanguageSwitcher() {
        return languageSwitcher;
    }

    public BukkitConfigEditor getConfigEditor() {
        return configEditor;
    }

    public ModeSelector getSelector() {
        return selector;
    }

    public BukkitDebugDump getDebugDump() {
        return debugDump;
    }

    public MetricsService getMetricsService() {
        return metricsService;
    }

    private boolean reloadConfiguration() {
        try {
            boolean applied = languageService.selections().commitUpdate(this::reloadConfigurationLocked);
            if (applied) {
                requestConfiguredLanguage();
            }
            return applied;
        } catch (IOException | RuntimeException failure) {
            getLogger().log(Level.SEVERE, "Could not reload GamemodeSwitcher; previous settings remain active", failure);
            return false;
        }
    }

    private boolean reloadConfigurationLocked() throws IOException {
        synchronized (configurationLock) {
            if (closing) {
                return false;
            }
            metricsService.reload(configService.runtime().metricsEnabled());
            ConfigService.PreparedConfig configuration = configService.prepare();
            LanguageService.PreparedLanguage language = languageService.prepare(configuration.runtime().language());
            if (closing) {
                return false;
            }
            configService.install(configuration);
            languageService.install(language);
            languageService.reloadInstalledLocales();
            metricsService.reload(configuration.runtime().metricsEnabled());
            switchService.clear();
            return true;
        }
    }

    private ConfigEditorDocument loadConfigurationDocument() throws IOException {
        synchronized (configurationLock) {
            requireActive();
            return configService.editorDocument(configService.prepare().source());
        }
    }

    private ConfigEditorDocument saveConfiguration(ConfigEditorDocument.Edit edit) throws IOException {
        ConfigEditorDocument saved = languageService.selections().commitUpdate(() -> saveConfigurationLocked(edit));
        requestConfiguredLanguage();
        return saved;
    }

    private ConfigEditorDocument saveConfigurationLocked(ConfigEditorDocument.Edit edit) throws IOException {
        synchronized (configurationLock) {
            requireActive();
            String replacement = TomlDocumentEditor.set(edit.original().source(), edit.path(), edit.value());
            RuntimeConfig configuration = ConfigService.parse(replacement);
            LanguageService.PreparedLanguage language = languageService.prepare(configuration.language());
            requireActive();
            configService.save(edit.original().source(), replacement);
            languageService.install(language);
            metricsService.reload(configuration.metricsEnabled());
            switchService.clear();
            return configService.editorDocument(configService.source());
        }
    }

    private void selectDefaultLanguage(String locale, LocalizationSnapshot snapshot) throws IOException {
        synchronized (configurationLock) {
            requireActive();
            String original = configService.source();
            String replacement = TomlDocumentEditor.set(original, List.of("general", "language"), new JsonPrimitive(locale));
            configService.save(original, replacement);
            languageService.install(new LanguageService.PreparedLanguage(locale, languageService.languageFile(locale), snapshot, true));
        }
    }

    private void hotReload() {
        boolean success = reloadConfiguration();
        if (closing) {
            return;
        }
        if (success) {
            getLogger().info("Applied GamemodeSwitcher configuration and language file changes.");
        }
        FoliaScheduler.runGlobal(this, () -> {
            for (Player player : getServer().getOnlinePlayers()) {
                FoliaScheduler.runEntity(this, player, () -> {
                    if (player.hasPermission("gamemodeswitcher.config")) {
                        languageService.sendPrefixed(player, success
                                ? SwitcherMessages.HOT_RELOAD_SUCCESS : SwitcherMessages.HOT_RELOAD_FAILED);
                    }
                });
            }
        });
    }

    private void requestConfiguredLanguage() {
        if (!closing) {
            languageService.requestRemote(configService.runtime().language(), this::scheduleLanguageReload);
        }
    }

    private void scheduleLanguageReload(RemoteLanguageCatalog.DownloadResult result) {
        if (closing || reloadWorker == null) {
            return;
        }
        if (!result.successful()) {
            getLogger().log(Level.WARNING, "Could not download GamemodeSwitcher language " + result.locale()
                    + " from " + result.source() + "; English remains available", result.failure());
            return;
        }
        try {
            reloadWorker.execute(this::hotReload);
        } catch (RejectedExecutionException failure) {
            if (!closing) {
                getLogger().log(Level.WARNING, "Could not apply downloaded GamemodeSwitcher language", failure);
            }
        }
    }

    private void requireActive() throws IOException {
        if (closing || Thread.currentThread().isInterrupted()) {
            throw new IOException("GamemodeSwitcher is stopping");
        }
    }

    private void stopReloadWorker() {
        if (reloadWorker == null) {
            return;
        }
        reloadWorker.shutdownNow();
        try {
            if (!reloadWorker.awaitTermination(5, TimeUnit.SECONDS)) {
                getLogger().warning("GamemodeSwitcher configuration work did not stop within five seconds.");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            getLogger().log(Level.WARNING, "Interrupted while stopping GamemodeSwitcher configuration work", failure);
        }
    }

    private void closeService(String name, AutoCloseable service) {
        if (service == null) {
            return;
        }
        try {
            service.close();
        } catch (Exception | LinkageError failure) {
            getLogger().log(Level.SEVERE, "Could not close GamemodeSwitcher " + name, failure);
        }
    }

    private static String compact(String value) {
        String singleLine = value.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() > 100 ? singleLine.substring(0, 97) + "..." : singleLine;
    }
}
