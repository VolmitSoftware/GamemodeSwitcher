package com.volmit.gsw.localization;

import art.arcane.volmlib.util.director.DirectorTextResolver;
import art.arcane.volmlib.util.io.AtomicFileIO;
import art.arcane.volmlib.util.localization.BukkitLanguageSwitcher;
import art.arcane.volmlib.util.localization.GitHubLanguageSource;
import art.arcane.volmlib.util.localization.LanguageAudience;
import art.arcane.volmlib.util.localization.LanguageCatalogMerge;
import art.arcane.volmlib.util.localization.LanguageDirectory;
import art.arcane.volmlib.util.localization.LanguageFileHeader;
import art.arcane.volmlib.util.localization.LanguageMeta;
import art.arcane.volmlib.util.localization.LanguageSourceManifest;
import art.arcane.volmlib.util.localization.LanguageUpstreamStore;
import art.arcane.volmlib.util.localization.LanguageUpstreamSync;
import art.arcane.volmlib.util.localization.LocaleOverlay;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationManager;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.LanguageReferenceRenderer;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.MessageArgumentKind;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.MessageValue;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.PluginLanguageService;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.volmlib.util.localization.TomlLanguageEditor;
import art.arcane.volmlib.util.localization.TomlLanguageParser;
import art.arcane.volmlib.util.localization.VolmitLocales;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class LanguageService {
    public static final String MANIFEST_RESOURCE = "gamemodeswitcher-language-source.properties";
    private static final long MAXIMUM_LANGUAGE_BYTES = LanguageDirectory.MAXIMUM_BYTES;
    private static final int MAXIMUM_BASE_DEPTH = 4;
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final MiniMessage STRICT_MINI_MESSAGE = MiniMessage.builder().strict(true).build();
    private static final PluralSelector ENGLISH_PLURALS = (locale, quantity) -> quantity.doubleValue() == 1D ? "one" : "other";
    private static final MessageCatalog CATALOG = SwitcherMessages.catalog();

    private final LanguageDirectory directory;
    private final File languageDirectory;
    private final Logger logger;
    private final LocalizationManager manager;
    private final LocalizationSnapshot englishSnapshot;
    private final AtomicReference<File> activeFile;
    private final LanguageSourceManifest manifest;
    private final LanguageUpstreamSync sync;
    private final Throwable sourceFailure;
    private final Set<String> observedLocales = new HashSet<>();
    private final Map<String, String> reloadFailures = new HashMap<>();
    private final Path preferenceFile;
    private volatile List<String> availableLocales = List.of();
    private volatile Map<String, LanguageMeta> metas = Map.of();
    private volatile BiConsumer<File, String> selfWriteListener;
    private volatile PluginLanguageService selections;

    public LanguageService(File dataFolder, Logger logger) {
        this(dataFolder, logger, GitHubLanguageSource.API_ROOT, GitHubLanguageSource.RAW_ROOT);
    }

    LanguageService(File dataFolder, Logger logger, URI apiRoot, URI rawRoot) {
        this(dataFolder, logger, apiRoot, rawRoot, LanguageUpstreamSync.MINIMUM_CHECK_INTERVAL);
    }

    LanguageService(File dataFolder, Logger logger, URI apiRoot, URI rawRoot, Duration minimumCheckInterval) {
        languageDirectory = new File(dataFolder, "languages");
        directory = new LanguageDirectory(languageDirectory);
        preferenceFile = directory.root().resolve("language-preferences.properties");
        this.logger = Objects.requireNonNull(logger, "logger");
        validateCatalogTemplates();
        manager = new LocalizationManager(LocalizationCandidate.english(CATALOG, ENGLISH_PLURALS));
        englishSnapshot = manager.snapshot();
        activeFile = new AtomicReference<>(directory.languageFile(CATALOG.englishLocale()).toFile());
        LanguageSourceManifest loadedManifest = null;
        LanguageUpstreamSync loadedSync = null;
        Throwable failure = null;
        try {
            loadedManifest = LanguageSourceManifest.load(LanguageService.class.getClassLoader(), MANIFEST_RESOURCE);
            GitHubLanguageSource source = new GitHubLanguageSource(loadedManifest, "GamemodeSwitcher", apiRoot, rawRoot);
            LanguageUpstreamStore store = new LanguageUpstreamStore(directory, loadedManifest);
            loadedSync = new LanguageUpstreamSync(new LanguageUpstreamSync.Options(
                    "GamemodeSwitcher",
                    CATALOG,
                    directory,
                    loadedManifest,
                    source,
                    store,
                    () -> LanguageReferenceRenderer.render(CATALOG, englishHeader(CATALOG.englishLocale())),
                    this::validateContent,
                    this::commitUpstream,
                    this::noteUpstreamWrite,
                    this::applyUpstream,
                    logger
            ), minimumCheckInterval);
        } catch (RuntimeException | LinkageError exception) {
            failure = exception;
        }
        manifest = loadedManifest;
        sync = loadedSync;
        sourceFailure = failure;
    }

    public void initialize(String locale) throws IOException {
        reload(locale);
    }

    public void reload(String locale) throws IOException {
        commitLanguageUpdate(() -> {
            synchronized (this) {
                install(prepare(locale));
                return null;
            }
        });
    }

    public synchronized PreparedLanguage prepare(String locale) throws IOException {
        String requiredLocale = canonicalLocale(locale);
        directory.prepare();
        ensureEnglish();
        File file = languageFile(requiredLocale);
        Path filePath = file.toPath().toAbsolutePath().normalize();
        if (Files.exists(filePath, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(filePath))) {
            throw new IOException("Language path is not a regular file: " + file.getName());
        }
        if (!file.exists() && !isRepositoryLocale(requiredLocale)) {
            createCustomLanguageIfMissing(requiredLocale);
        }
        ArrayList<LocaleOverlay> overlays = new ArrayList<>();
        if (file.isFile()) {
            try {
                overlays.add(createOverlay(requiredLocale, file.getPath(), loadEditableLanguage(file)));
                overlays.addAll(baseOverlays(requiredLocale));
            } catch (IOException | RuntimeException exception) {
                logger.log(Level.WARNING, "Using English for unreadable language file " + file, exception);
            }
        }
        boolean selectionReady = file.isFile();
        PreparedLanguage prepared = createPrepared(requiredLocale, file, overlays, selectionReady);
        refreshAvailableLocales();
        return prepared;
    }

    public synchronized PreparedLanguage englishFallback(String locale) throws IOException {
        String requiredLocale = canonicalLocale(locale);
        directory.prepare();
        ensureEnglish();
        refreshAvailableLocales();
        return new PreparedLanguage(
                requiredLocale,
                languageFile(requiredLocale),
                LocalizationSnapshot.create(LocalizationCandidate.english(CATALOG, ENGLISH_PLURALS)),
                requiredLocale.equalsIgnoreCase(CATALOG.englishLocale())
        );
    }

    public void install(PreparedLanguage prepared) {
        manager.install(prepared.snapshot());
        activeFile.set(prepared.file());
        PluginLanguageService activeSelections = selections;
        if (activeSelections != null) {
            activeSelections.cache(prepared.locale(), prepared.snapshot());
        }
    }

    public void reloadInstalledLocales() throws IOException {
        commitLanguageUpdate(this::reloadInstalledLocalesLocked);
    }

    private synchronized Void reloadInstalledLocalesLocked() throws IOException {
        observedLocales.addAll(directory.scan());
        String defaultLocale = activeFile.get().getName().replace(".toml", "");
        for (String locale : observedLocales) {
            if (sameLocale(locale, defaultLocale)) {
                continue;
            }
            try {
                File file = languageFile(locale);
                FileSource source = readFileSource(file);
                ArrayList<LocaleOverlay> overlays = new ArrayList<>();
                if (source.existed()) {
                    overlays.add(createOverlay(locale, file.getPath(),
                            TomlLanguageParser.parseValidText(source.content(), CATALOG)));
                    overlays.addAll(baseOverlays(locale, source.content()));
                }
                LocalizationSnapshot snapshot = createPrepared(locale, file, overlays, source.existed()).snapshot();
                PluginLanguageService activeSelections = selections;
                if (activeSelections != null) {
                    activeSelections.cache(locale, selectionSnapshot(locale, snapshot));
                }
                reloadFailures.remove(locale);
            } catch (IOException | RuntimeException failure) {
                String message = failure.toString();
                if (!Objects.equals(reloadFailures.put(locale, message), message)) {
                    logger.log(Level.WARNING, "Language hot reload retained the previous " + locale + " messages", failure);
                }
            }
        }
        return null;
    }

    public synchronized PluginLanguageService initializeSelections(
            Supplier<String> defaultLocale,
            PluginLanguageService.DefaultSelection defaultSelection
    ) {
        if (selections != null) {
            return selections;
        }
        PluginLanguageService created = new PluginLanguageService(new PluginLanguageService.Options(
                preferenceFile,
                this::availableLocales,
                defaultLocale,
                manager::snapshot,
                this::loadSelectionSnapshot,
                defaultSelection,
                logger
        ));
        selections = created;
        return created;
    }

    public PluginLanguageService selections() {
        PluginLanguageService activeSelections = selections;
        if (activeSelections == null) {
            throw new IllegalStateException("Language selections are not initialized");
        }
        return activeSelections;
    }

    public PluginLanguageEditor.Options editorOptions() {
        return new PluginLanguageEditor.Options(this::loadSelectionSnapshot, this::saveEditorMessage);
    }

    public BukkitLanguageSwitcher.LanguageUpdateAction updateAction() {
        return this::startUpstreamCheck;
    }

    public BukkitLanguageSwitcher.LocaleDisplayNames displayNames() {
        return locale -> metas.getOrDefault(locale, LanguageMeta.empty()).name().orElse(null);
    }

    public CompletableFuture<LanguageUpstreamSync.SyncReport> startUpstreamCheck() {
        if (sync == null) {
            return CompletableFuture.completedFuture(LanguageUpstreamSync.SyncReport.failed(
                    sourceDescription(), System.currentTimeMillis()));
        }
        return sync.checkAsync().thenApply(this::logUpstreamReport);
    }

    public CompletableFuture<String> requestInstall(String locale) {
        String requiredLocale = canonicalLocale(locale);
        if (languageFile(requiredLocale).isFile()) {
            try {
                return CompletableFuture.completedFuture(directory.read(requiredLocale));
            } catch (IOException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }
        if (sync == null || !isRepositoryLocale(requiredLocale)) {
            return CompletableFuture.failedFuture(new IOException("Language " + requiredLocale
                    + " is not available from " + sourceDescription()));
        }
        logger.info("Installing GamemodeSwitcher language " + requiredLocale + " from " + sourceDescription() + "...");
        return sync.installAsync(requiredLocale).thenApply(content -> {
            logger.info("Installed GamemodeSwitcher language " + requiredLocale + " to "
                    + languageFile(requiredLocale).toPath().toAbsolutePath().normalize() + ".");
            return content;
        });
    }

    public Optional<Throwable> sourceFailure() {
        return Optional.ofNullable(sourceFailure);
    }

    public String sourceDescription() {
        if (manifest == null) {
            return "unavailable";
        }
        return manifest.repository() + "@" + manifest.branch();
    }

    public Optional<LanguageUpstreamSync.Status> upstreamStatus() {
        return sync == null ? Optional.empty() : Optional.of(sync.status());
    }

    public boolean isRepositoryLocale(String locale) {
        return manifest != null && manifest.contains(locale);
    }

    public synchronized List<EditableMessage> editableMessages(String locale) throws IOException {
        PreparedLanguage prepared = prepare(locale);
        if (!prepared.selectionReady()) {
            throw new IOException("Language file is not installed: " + prepared.locale());
        }
        ArrayList<MessageKey> definitions = new ArrayList<>(CATALOG.keys());
        definitions.sort(Comparator.comparing(MessageKey::id, String.CASE_INSENSITIVE_ORDER));
        ArrayList<EditableMessage> messages = new ArrayList<>(definitions.size());
        for (MessageKey definition : definitions) {
            if (!(definition instanceof TextKey textKey)) {
                throw new IOException("Language editor does not support key shape: " + definition.id());
            }
            MessageValue effectiveValue = prepared.snapshot().value(textKey);
            if (!(effectiveValue instanceof TextValue textValue)) {
                throw new IOException("Language editor resolved a non-text value: " + definition.id());
            }
            messages.add(new EditableMessage(
                    definition.id(),
                    textValue.template(),
                    previewValue(prepared.snapshot(), definition, textValue.template()),
                    textValue.placeholders()
            ));
        }
        return List.copyOf(messages);
    }

    public PreparedLanguage updateMessage(String locale, String key, String value) throws IOException {
        return commitLanguageUpdate(() -> {
            synchronized (this) {
                return mutateMessage(locale, key, value);
            }
        });
    }

    public void setSelfWriteListener(BiConsumer<File, String> listener) {
        selfWriteListener = listener;
    }

    public String render(TextKey key) {
        return render(key, MessageArgs.empty());
    }

    public String render(TextKey key, MessageArgs arguments) {
        LocalizationSnapshot snapshot = selectedSnapshot(null);
        return render(snapshot, key, arguments, renderPrefix(snapshot));
    }

    public String render(CommandSender sender, TextKey key) {
        return render(sender, key, MessageArgs.empty());
    }

    public String render(CommandSender sender, TextKey key, MessageArgs arguments) {
        LocalizationSnapshot snapshot = selectedSnapshot(sender);
        return render(snapshot, key, arguments, renderPrefix(snapshot));
    }

    public String renderWithoutPrefix(TextKey key, MessageArgs arguments) {
        return render(selectedSnapshot(null), key, arguments, "");
    }

    public String renderWithoutPrefix(CommandSender sender, TextKey key, MessageArgs arguments) {
        return render(selectedSnapshot(sender), key, arguments, "");
    }

    public String renderPrefixed(TextKey key, MessageArgs arguments) {
        return render(key, arguments);
    }

    public String renderPrefixed(CommandSender sender, TextKey key, MessageArgs arguments) {
        return render(sender, key, arguments);
    }

    public void send(CommandSender sender, TextKey key) {
        ComponentMessenger.sendMarkup(sender, render(sender, key));
    }

    public void send(CommandSender sender, TextKey key, MessageArgs arguments) {
        ComponentMessenger.sendMarkup(sender, render(sender, key, arguments));
    }

    public void sendPrefixed(CommandSender sender, TextKey key) {
        sendPrefixed(sender, key, MessageArgs.empty());
    }

    public void sendPrefixed(CommandSender sender, TextKey key, MessageArgs arguments) {
        ComponentMessenger.sendMarkup(sender, renderPrefixed(sender, key, arguments));
    }

    public String legacy(TextKey key) {
        return ComponentText.markup(render(key)).legacy();
    }

    public String legacy(TextKey key, MessageArgs arguments) {
        return ComponentText.markup(render(key, arguments)).legacy();
    }

    public String legacy(CommandSender sender, TextKey key) {
        return ComponentText.markup(render(sender, key)).legacy();
    }

    public String legacy(CommandSender sender, TextKey key, MessageArgs arguments) {
        return ComponentText.markup(render(sender, key, arguments)).legacy();
    }

    public DirectorTextResolver directorResolver() {
        return this::renderWithoutPrefix;
    }

    public File activeFile() {
        return activeFile.get();
    }

    public File languageFile(String locale) {
        return directory.languageFile(canonicalLocale(locale)).toFile();
    }

    public File languageDirectory() {
        return languageDirectory;
    }

    public boolean isLanguageFile(File candidate) {
        return directory.isLanguageFile(candidate);
    }

    public List<String> availableLocales() {
        return availableLocales;
    }

    public String localeDisplayName(String locale) {
        String normalized = locale == null ? "" : locale.trim();
        LanguageMeta meta = metas.getOrDefault(normalized, LanguageMeta.empty());
        return meta.name().or(() -> VolmitLocales.displayName(normalized)).orElse("Custom language");
    }

    public LanguageMeta meta(String locale) {
        return metas.getOrDefault(locale == null ? "" : locale.trim(), LanguageMeta.empty());
    }

    public Optional<String> availableLocale(String requested) {
        String normalized;
        try {
            normalized = LanguageDirectory.requireLocale(requested);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        return availableLocales.stream()
                .filter(locale -> sameLocale(locale, normalized))
                .findFirst();
    }

    public void close() {
        selfWriteListener = null;
        PluginLanguageService activeSelections = selections;
        selections = null;
        if (activeSelections != null) {
            activeSelections.close();
        }
        synchronized (this) {
            observedLocales.clear();
            reloadFailures.clear();
        }
        if (sync != null) {
            sync.close();
        }
    }

    private <T> T commitUpstream(LanguageUpstreamSync.CommitAction<T> action) throws IOException {
        return commitLanguageUpdate(action::apply);
    }

    private void noteUpstreamWrite(Path file, String content) {
        notifyLanguageWrite(file.toFile(), content);
    }

    private void applyUpstream(String locale, String content, LanguageCatalogMerge.MergeResult result) throws IOException {
        File file = languageFile(locale);
        ArrayList<LocaleOverlay> overlays = new ArrayList<>();
        overlays.add(createOverlay(locale, file.getPath(), parseStrictValues(content, locale)));
        overlays.addAll(baseOverlays(locale, content));
        PreparedLanguage prepared = createPrepared(locale, file, overlays, true);
        refreshAvailableLocales();
        PluginLanguageService activeSelections = selections;
        if (activeSelections != null) {
            activeSelections.cache(locale, selectionSnapshot(locale, prepared.snapshot()));
        }
        if (file.getAbsoluteFile().equals(activeFile.get().getAbsoluteFile())) {
            manager.install(prepared.snapshot());
        }
    }

    private LanguageUpstreamSync.SyncReport logUpstreamReport(LanguageUpstreamSync.SyncReport report) {
        String revision = report.revision().map(sha -> sha.substring(0, 7)).orElse("unknown");
        for (String locale : report.changedLocales()) {
            LanguageCatalogMerge.MergeResult result = report.merged().get(locale);
            String source = locale.equals(CATALOG.englishLocale())
                    ? "the built-in catalog" : sourceDescription() + " (" + revision + ")";
            logger.info("Updated GamemodeSwitcher language " + locale + " from " + source + ": " + result.summary() + ".");
        }
        if (report.coolingDown()) {
            logger.info("GamemodeSwitcher language source " + sourceDescription()
                    + " was checked recently; the next check is allowed in " + report.cooldownRemaining() + ".");
        } else if (report.failure().isEmpty() && report.changedLocales().isEmpty() && report.failures().isEmpty()
                && report.revision().isPresent()) {
            logger.info("GamemodeSwitcher languages match " + sourceDescription() + " (" + revision + ").");
        }
        return report;
    }

    private void notifyLanguageWrite(File file, String content) {
        BiConsumer<File, String> listener = selfWriteListener;
        if (listener != null) {
            try {
                listener.accept(file, content);
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Language was saved but the hot reload notification failed: " + file, exception);
            }
        }
    }

    private PreparedLanguage mutateMessage(String locale, String key, String value) throws IOException {
        String requiredLocale = canonicalLocale(locale);
        MessageKey definition = CATALOG.require(key);
        if (!(definition instanceof TextKey)) {
            throw new IOException("Language editor does not support key shape: " + definition.id());
        }
        try {
            validateTemplate("language:" + key, value, sampleArguments(definition.placeholders()));
            LocalizationSnapshot.create(new LocalizationCandidate(CATALOG,
                    List.of(LocaleOverlay.builder("editor", requiredLocale).text(key, value).build()), ENGLISH_PLURALS));
        } catch (RuntimeException exception) {
            throw new IOException("Language file contains invalid message markup: " + key, exception);
        }
        PreparedLanguage current = prepare(requiredLocale);
        if (!current.selectionReady()) {
            throw new IOException("Language file is not installed: " + requiredLocale);
        }
        File file = languageFile(requiredLocale);
        FileSource source = readFileSource(file);
        TomlLanguageEditor.EditResult edit = TomlLanguageEditor.upsertText(
                source.content(), definition.id(), value);
        String content = edit.content();
        if (content.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_LANGUAGE_BYTES) {
            throw new IOException("Language file exceeds the 2 MiB safety limit");
        }
        Map<String, String> values = TomlLanguageParser.parseValidText(content, CATALOG);
        ArrayList<LocaleOverlay> overlays = new ArrayList<>();
        overlays.add(createOverlay(requiredLocale, file.getPath(), values));
        overlays.addAll(baseOverlays(requiredLocale, content));
        PreparedLanguage prepared = createPrepared(
                requiredLocale, file, overlays, true);
        verifyUnchanged(file, source);
        AtomicFileIO.writeString(file.toPath(), content);
        refreshAvailableLocales();
        notifyLanguageWrite(file, content);
        PluginLanguageService activeSelections = selections;
        if (sameLocale(requiredLocale, activeFile.get().getName().replace(".toml", ""))) {
            manager.install(prepared.snapshot());
        }
        if (activeSelections != null) {
            activeSelections.cache(requiredLocale, prepared.snapshot());
            if (sameLocale(requiredLocale, activeSelections.defaultLocale())) {
                manager.install(prepared.snapshot());
                activeFile.set(prepared.file());
            }
        }
        return prepared;
    }

    private synchronized LocalizationSnapshot loadSelectionSnapshot(String locale) throws IOException {
        String requiredLocale = canonicalLocale(locale);
        directory.prepare();
        ensureEnglish();
        File target = languageFile(requiredLocale);
        if (!target.exists() && isRepositoryLocale(requiredLocale)) {
            if (sync == null) {
                throw new IOException("Language " + requiredLocale + " is not installed and the language source is "
                        + sourceDescription());
            }
            logger.info("Installing GamemodeSwitcher language " + requiredLocale + " from " + sourceDescription() + "...");
            sync.install(requiredLocale);
            logger.info("Installed GamemodeSwitcher language " + requiredLocale + " to "
                    + target.toPath().toAbsolutePath().normalize() + ".");
        }
        PreparedLanguage prepared = prepare(requiredLocale);
        if (!prepared.selectionReady()) {
            throw new IOException("Language file is not installed: " + requiredLocale);
        }
        observedLocales.add(requiredLocale);
        return selectionSnapshot(requiredLocale, prepared.snapshot());
    }

    private LocalizationSnapshot selectionSnapshot(String locale, LocalizationSnapshot prepared) {
        if (sameLocale(locale, CATALOG.englishLocale())) {
            return prepared;
        }
        ArrayList<LocaleOverlay> overlays = new ArrayList<>(prepared.overlays());
        LocaleOverlay.Builder englishFallback = LocaleOverlay.builder("code-owned-English:" + locale, locale);
        for (MessageKey key : CATALOG.keys()) {
            englishFallback.put(key.id(), key.englishValue());
        }
        overlays.add(englishFallback.build());
        return LocalizationSnapshot.create(new LocalizationCandidate(CATALOG, overlays, ENGLISH_PLURALS));
    }

    private LocalizationSnapshot saveEditorMessage(PluginLanguageEditor.Edit edit) throws IOException {
        return commitLanguageUpdate(() -> {
            synchronized (this) {
                return saveCurrentEditorMessage(edit);
            }
        });
    }

    private LocalizationSnapshot saveCurrentEditorMessage(PluginLanguageEditor.Edit edit) throws IOException {
        LocalizationSnapshot current = loadSelectionSnapshot(edit.locale());
        MessageKey definition = CATALOG.require(edit.key());
        if (!current.value(definition).equals(edit.expected())) {
            throw new IOException("Language message changed while it was being edited: " + edit.key());
        }
        if (!(edit.value() instanceof TextValue textValue)) {
            throw new IllegalArgumentException("Unsupported language message shape: " + edit.key());
        }
        return mutateMessage(edit.locale(), edit.key(), textValue.template()).snapshot();
    }

    private <T> T commitLanguageUpdate(PluginLanguageService.CommitUpdate<T> update) throws IOException {
        PluginLanguageService activeSelections = selections;
        return activeSelections == null ? update.apply() : activeSelections.commitUpdate(update);
    }

    private FileSource readFileSource(File file) throws IOException {
        Path path = file.toPath().toAbsolutePath().normalize();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return new FileSource(false, new byte[0], "");
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("Language path is not a regular file: " + file.getName());
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > MAXIMUM_LANGUAGE_BYTES) {
            throw new IOException("Language file exceeds the 2 MiB safety limit");
        }
        try {
            return new FileSource(true, bytes, LanguageDirectory.decode(bytes));
        } catch (IOException exception) {
            throw new IOException("Invalid UTF-8 in " + file.getName(), exception);
        }
    }

    private void verifyUnchanged(File file, FileSource expected) throws IOException {
        Path path = file.toPath().toAbsolutePath().normalize();
        boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        byte[] current = exists
                ? Files.readAllBytes(path)
                : new byte[0];
        if (exists != expected.existed() || !Arrays.equals(expected.bytes(), current)) {
            throw new IOException("Language file changed while the editor was saving; try again");
        }
    }

    private PreparedLanguage createPrepared(
            String locale,
            File file,
            List<LocaleOverlay> overlays,
            boolean selectionReady
    ) throws IOException {
        try {
            LocalizationSnapshot snapshot = LocalizationSnapshot.create(
                    new LocalizationCandidate(CATALOG, overlays, ENGLISH_PLURALS)
            );
            return new PreparedLanguage(locale, file, snapshot, selectionReady);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid language selection " + locale, exception);
        }
    }

    private LocaleOverlay createOverlay(String locale, String source, Map<String, String> values) throws IOException {
        LocaleOverlay.Builder overlay = LocaleOverlay.builder(source, locale);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            MessageKey definition = CATALOG.key(entry.getKey());
            if (definition == null) {
                continue;
            }
            if (!(definition instanceof TextKey)) {
                throw new IOException("Language key is not text: " + entry.getKey());
            }
            try {
                validateTemplate("language:" + entry.getKey(), entry.getValue(),
                        sampleArguments(definition.placeholders()));
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Using English for invalid language message " + source + ":" + entry.getKey(), exception);
                continue;
            }
            overlay.text(entry.getKey(), entry.getValue());
        }
        return overlay.build();
    }

    private List<LocaleOverlay> baseOverlays(String locale) throws IOException {
        File file = languageFile(locale);
        if (!file.isFile()) {
            return List.of();
        }
        return baseOverlays(locale, LanguageDirectory.readContent(file.toPath()));
    }

    private List<LocaleOverlay> baseOverlays(String locale, String content) throws IOException {
        ArrayList<LocaleOverlay> overlays = new ArrayList<>();
        HashSet<String> visited = new HashSet<>();
        visited.add(normalizedLocale(locale));
        LanguageMeta meta = metaFromContent(locale, content);
        int depth = 0;
        while (meta.base().isPresent() && depth < MAXIMUM_BASE_DEPTH) {
            String base = canonicalLocale(meta.base().get());
            if (!visited.add(normalizedLocale(base)) || sameLocale(base, CATALOG.englishLocale())) {
                break;
            }
            File baseFile = languageFile(base);
            if (!baseFile.isFile()) {
                break;
            }
            String baseContent = LanguageDirectory.readContent(baseFile.toPath());
            overlays.add(createOverlay(base, baseFile.getPath(), TomlLanguageParser.parseValidText(baseContent, CATALOG)));
            meta = metaFromContent(base, baseContent);
            depth++;
        }
        return overlays;
    }

    private LanguageMeta metaFromContent(String locale, String content) {
        try {
            return LanguageMeta.parse(content).withoutSelfBase(locale);
        } catch (IOException exception) {
            return LanguageMeta.empty();
        }
    }

    private Map<String, String> loadEditableLanguage(File file) throws IOException {
        String content;
        try {
            content = LanguageDirectory.readContent(file.toPath());
        } catch (IOException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("UTF-8")) {
                throw new IOException("Invalid UTF-8 in " + file.getName(), exception);
            }
            throw exception;
        }
        try {
            return TomlLanguageParser.parseValidText(content, CATALOG);
        } catch (IOException exception) {
            throw new IOException("Invalid TOML in " + file.getName() + ": " + exception.getMessage(), exception);
        }
    }

    private void ensureEnglish() throws IOException {
        if (sync != null) {
            try {
                sync.mergeEnglish().ifPresent(result -> logger.info("Updated GamemodeSwitcher language "
                        + CATALOG.englishLocale() + " from the built-in catalog: " + result.summary() + "."));
                return;
            } catch (IOException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IOException("Could not refresh the English language file", exception);
            }
        }
        File english = languageFile(CATALOG.englishLocale());
        if (english.exists()) {
            return;
        }
        AtomicFileIO.writeString(english.toPath(), LanguageReferenceRenderer.render(
                CATALOG, englishHeader(CATALOG.englishLocale())));
    }

    private void createCustomLanguageIfMissing(String locale) throws IOException {
        File target = languageFile(locale);
        if (target.exists()) {
            return;
        }
        String content = LanguageReferenceRenderer.render(CATALOG, englishHeader(locale),
                LanguageMeta.of(locale, CATALOG.englishLocale()));
        AtomicFileIO.writeString(target.toPath(), content);
        notifyLanguageWrite(target, content);
    }

    void validateContent(String locale, String content) throws IOException {
        Map<String, String> values = parseStrictValues(content, locale);
        LocaleOverlay overlay = createOverlay(locale, "upstream:" + locale, values);
        try {
            LocalizationSnapshot.create(new LocalizationCandidate(CATALOG, List.of(overlay), ENGLISH_PLURALS));
        } catch (RuntimeException exception) {
            throw new IOException("Language content is invalid: " + locale, exception);
        }
    }

    private Map<String, String> parseStrictValues(String content, String locale) throws IOException {
        if (content.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_LANGUAGE_BYTES) {
            throw new IOException("Language file exceeds the 2 MiB safety limit");
        }
        try {
            return TomlLanguageParser.parseValidText(content, CATALOG);
        } catch (IOException exception) {
            throw new IOException("Invalid TOML in " + locale + ".toml: " + exception.getMessage(), exception);
        }
    }

    private void refreshAvailableLocales() throws IOException {
        LinkedHashSet<String> choices = new LinkedHashSet<>();
        choices.add(CATALOG.englishLocale());
        if (manifest != null) {
            choices.addAll(manifest.locales());
        }
        List<String> installed = directory.scan();
        choices.addAll(installed);
        LinkedHashMap<String, LanguageMeta> discoveredMetas = new LinkedHashMap<>();
        for (String locale : installed) {
            try {
                LanguageMeta meta = directory.meta(locale);
                if (!meta.isEmpty()) {
                    discoveredMetas.put(locale, meta);
                }
            } catch (IOException exception) {
                logger.log(Level.FINE, "Could not read language metadata for " + locale, exception);
            }
        }
        List<String> discovered = new ArrayList<>(choices);
        discovered.sort(String.CASE_INSENSITIVE_ORDER);
        metas = Map.copyOf(discoveredMetas);
        availableLocales = List.copyOf(discovered);
    }

    private String canonicalLocale(String locale) {
        String requiredLocale = LanguageDirectory.requireLocale(locale);
        if (sameLocale(CATALOG.englishLocale(), requiredLocale)) {
            return CATALOG.englishLocale();
        }
        if (manifest != null && manifest.contains(requiredLocale)) {
            return manifest.canonical(requiredLocale);
        }
        for (String available : availableLocales) {
            if (sameLocale(available, requiredLocale)) {
                return available;
            }
        }
        return requiredLocale;
    }

    private static String normalizedLocale(String locale) {
        return locale.replace('-', '_').toLowerCase(Locale.ROOT);
    }

    private static List<String> englishHeader(String locale) {
        return LanguageFileHeader.render(new LanguageFileHeader.Options(
                "GamemodeSwitcher", locale,
                List.of("runtime.prefix supplies {prefix}. Remove {prefix} from a message to hide it.",
                        "Only English is created at startup. Other translations download from GitHub when selected.",
                        "Local edits remain intact. Missing or invalid entries use built-in English.",
                        "Personal selections are saved in languages/language-preferences.properties."),
                List.of("Colors and styles: &0-&f, &k-&r.",
                        "RGB colors: &#RRGGBB, &xRRGGBB, &x&R&R&G&G&B&B, [RRGGBB].",
                        "MiniMessage supports custom formatting. Escape & or [ with a backslash for literal text."),
                Map.ofEntries(
                        Map.entry("added", "Messages added by a language update"),
                        Map.entry("after", "Value after the change"),
                        Map.entry("argument", "Unexpected command argument"),
                        Map.entry("before", "Value before the change"),
                        Map.entry("command", "Command path"),
                        Map.entry("count", "Number of matching entries"),
                        Map.entry("enabled", "Game mode switching state"),
                        Map.entry("folder", "World folder name"),
                        Map.entry("group", "Message category name"),
                        Map.entry("hot_reload", "Automatic file reload state"),
                        Map.entry("java", "Required Java version"),
                        Map.entry("kept", "Local edits kept by a language update"),
                        Map.entry("key", "Parameter or message key"),
                        Map.entry("language", "Server language"),
                        Map.entry("line", "Message line number"),
                        Map.entry("locale", "Locale identifier"),
                        Map.entry("maximum", "Maximum accepted value"),
                        Map.entry("minecraft", "Supported Minecraft version range"),
                        Map.entry("mode", "Game mode name"),
                        Map.entry("new", "New setting value"),
                        Map.entry("number", "List entry number"),
                        Map.entry("old", "Previous setting value"),
                        Map.entry("page", "Current page number"),
                        Map.entry("pages", "Total page count"),
                        Map.entry("parameter", "Parameter name"),
                        Map.entry("path", "Local diagnostic report path"),
                        Map.entry("permission", "Required permission"),
                        Map.entry("personal", "Personal language when different from the server default"),
                        Map.entry("plugin", "Plugin name"),
                        Map.entry("prefix", "Global runtime.prefix value; optional per message"),
                        Map.entry("reason", "Failure reason"),
                        Map.entry("remaining", "Time until the next language check is allowed"),
                        Map.entry("scheduler", "Scheduler implementation"),
                        Map.entry("sound", "Namespaced sound identifier"),
                        Map.entry("seconds", "Remaining cooldown or confirmation time in seconds"),
                        Map.entry("section", "Language editor section"),
                        Map.entry("setting", "Setting name"),
                        Map.entry("state", "Player gesture or feedback setting"),
                        Map.entry("step", "Numeric setting change from one ordinary click"),
                        Map.entry("target", "Language selection target"),
                        Map.entry("type", "Parameter type"),
                        Map.entry("updated", "Messages changed by a language update"),
                        Map.entry("url", "Public diagnostic report URL"),
                        Map.entry("value", "Current, default, or raw value"),
                        Map.entry("variables", "Placeholders valid for the selected message"),
                        Map.entry("version", "Plugin version"),
                        Map.entry("world", "World name")
                )));
    }

    private void validateCatalogTemplates() {
        for (MessageKey key : CATALOG.keys()) {
            MessageValue value = key.englishValue();
            if (value instanceof TextValue text) {
                validateTemplate("catalog:" + key.id(), text.template(), sampleArguments(value.placeholders()));
            }
        }
    }

    private LocalizationSnapshot selectedSnapshot(CommandSender sender) {
        PluginLanguageService activeSelections = selections;
        if (activeSelections == null) {
            return manager.snapshot();
        }
        UUID playerId = sender instanceof Player player ? player.getUniqueId() : LanguageAudience.current();
        LocalizationSnapshot selected = activeSelections.snapshot(playerId);
        if (playerId != null && selected == manager.snapshot()) {
            Optional<String> preference = activeSelections.playerLocale(playerId);
            if (preference.isPresent() && !sameLocale(preference.get(), activeSelections.defaultLocale())) {
                return englishSnapshot;
            }
        }
        return selected;
    }

    private String render(LocalizationSnapshot snapshot, TextKey key, MessageArgs arguments, String prefix) {
        MessageArgs resolvedArguments = argumentsWithPrefix(key, arguments, prefix);
        String template = snapshot.resolve(key, resolvedArguments).template();
        return interpolate(template, resolvedArguments);
    }

    private String renderPrefix(LocalizationSnapshot snapshot) {
        String template = snapshot.resolve(SwitcherMessages.PREFIX, MessageArgs.empty()).template();
        return interpolate(template, MessageArgs.empty());
    }

    private boolean sameLocale(String first, String second) {
        return first.replace('-', '_').equalsIgnoreCase(second.replace('-', '_'));
    }

    private MessageArgs argumentsWithPrefix(TextKey key, MessageArgs arguments, String prefix) {
        MessageArgs resolved = arguments == null ? MessageArgs.empty() : arguments;
        if (!key.placeholders().contains("prefix")) {
            return resolved;
        }
        if (resolved.names().contains("prefix")) {
            throw new IllegalArgumentException("The prefix message argument is managed by GamemodeSwitcher");
        }
        MessageArgs.Builder builder = MessageArgs.builder();
        for (MessageArgument argument : resolved.arguments().values()) {
            builder.add(argument);
        }
        builder.trusted("prefix", prefix);
        return builder.build();
    }

    private void validateTemplate(String path, String template, MessageArgs arguments) {
        validatePlaceholderPlacement(path, template);
        try {
            STRICT_MINI_MESSAGE.deserialize(interpolate(template, arguments));
            String normalized = ComponentText.normalizeMarkup(template);
            MINI_MESSAGE.deserialize(interpolate(normalized, arguments));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(path + ": invalid message markup", exception);
        }
    }

    private void validatePlaceholderPlacement(String path, String template) {
        boolean insideTag = false;
        for (int index = 0; index < template.length(); index++) {
            char current = template.charAt(index);
            if (current == '\\') {
                index++;
                continue;
            }
            if (current == '<') {
                insideTag = true;
                continue;
            }
            if (current == '>') {
                insideTag = false;
                continue;
            }
            if (insideTag && current == '{'
                    && (index + 1 >= template.length() || template.charAt(index + 1) != '{')) {
                throw new IllegalArgumentException(path + ": message placeholders cannot be used inside MiniMessage tags");
            }
        }
    }

    private MessageArgs sampleArguments(Set<String> placeholders) {
        MessageArgs.Builder builder = MessageArgs.builder();
        for (String placeholder : placeholders) {
            builder.untrusted(placeholder, "value");
        }
        return builder.build();
    }

    private String interpolate(String template, MessageArgs arguments) {
        StringBuilder output = new StringBuilder(template.length());
        int index = 0;
        while (index < template.length()) {
            char current = template.charAt(index);
            if (current == '{' && index + 1 < template.length() && template.charAt(index + 1) == '{') {
                output.append('{');
                index += 2;
                continue;
            }
            if (current == '}' && index + 1 < template.length() && template.charAt(index + 1) == '}') {
                output.append('}');
                index += 2;
                continue;
            }
            if (current != '{') {
                output.append(current);
                index++;
                continue;
            }
            int end = template.indexOf('}', index + 1);
            if (end < 0) {
                throw new IllegalArgumentException("Unclosed message placeholder");
            }
            String name = template.substring(index + 1, end);
            MessageArgument argument = arguments.require(name);
            String replacement = String.valueOf(argument.value());
            if (argument.kind() == MessageArgumentKind.UNTRUSTED) {
                replacement = escapeUntrusted(replacement);
            }
            output.append(replacement);
            index = end + 1;
        }
        return output.toString();
    }

    private String previewValue(LocalizationSnapshot snapshot, MessageKey definition, String template) {
        MessageArgs.Builder arguments = MessageArgs.builder();
        for (String placeholder : definition.placeholders()) {
            if (placeholder.equals("prefix")) {
                MessageValue prefixValue = snapshot.value(SwitcherMessages.PREFIX);
                if (!(prefixValue instanceof TextValue prefixText)) {
                    throw new IllegalStateException("Language prefix is not text");
                }
                arguments.trusted(placeholder, prefixText.template());
            } else {
                arguments.untrusted(placeholder, "[" + placeholder + "]");
            }
        }
        return interpolate(template, arguments.build());
    }

    private String escapeUntrusted(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '&' || current == '[') {
                escaped.append('\\');
            }
            escaped.append(current);
        }
        return MINI_MESSAGE.escapeTags(escaped.toString());
    }

    public record EditableMessage(
            String id,
            String effectiveValue,
            String previewValue,
            Set<String> placeholders
    ) {
        public EditableMessage {
            placeholders = Set.copyOf(placeholders);
        }
    }

    private record FileSource(boolean existed, byte[] bytes, String content) {
        private FileSource {
            bytes = Arrays.copyOf(bytes, bytes.length);
        }
    }

    public record PreparedLanguage(
            String locale,
            File file,
            LocalizationSnapshot snapshot,
            boolean selectionReady
    ) {
    }

}
