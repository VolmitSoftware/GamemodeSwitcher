package com.volmit.gsw.localization;

import art.arcane.volmlib.util.localization.LanguageAudience;
import art.arcane.volmlib.util.localization.LocaleOverlay;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.PluginLanguageService;
import art.arcane.volmlib.util.localization.RemoteLanguageCatalog;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.volmlib.util.localization.TomlLanguageParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LanguageServiceTest {
    @TempDir
    Path directory;

    @Test
    void createsOnlyEnglishAndListsSeventeenRemoteTranslations() throws IOException {
        LanguageService service = service();
        service.initialize("en_US");
        assertThat(service.availableLocales()).hasSize(18).contains("en_US", "ja-JP", "zh_TW");
        assertThat(service.languageDirectory().list()).containsExactly("en_US.toml");
        assertThat(service.remoteCatalogFailure()).isEmpty();
        assertThat(service.remoteCatalogReference()).contains("main");
        String content = Files.readString(service.languageFile("en_US").toPath());
        assertThat(content).contains("=== Variables ===", "MiniMessage", "{mode}", "{world}", "GitHub");
        assertVariableHeader(content, SwitcherMessages.catalog());
        service.close();
    }

    @Test
    void remoteLocalePreparationUsesEnglishWithoutCreatingTheMissingFile() throws IOException {
        LanguageService service = service();
        service.initialize("de_DE");

        assertThat(service.prepare("de_DE").selectionReady()).isFalse();
        assertThat(service.languageDirectory().list()).containsExactly("en_US.toml");
        assertThat(service.render(SwitcherMessages.MODE_CREATIVE)).isEqualTo("Creative");
        assertThat(service.hasRemoteCatalogLocale("JA_jp")).isTrue();
        service.close();
    }

    @Test
    void repositoryCatalogsAreCompleteAndDocumentEveryVariable() throws IOException {
        LanguageService service = service();
        service.initialize("en_US");
        MessageCatalog catalog = SwitcherMessages.catalog();
        Path sources = Path.of(System.getProperty("gamemodeswitcher.projectDir"), "src/main/resources/languages");
        for (String locale : service.availableLocales()) {
            if (locale.equals("en_US")) {
                continue;
            }
            String content = Files.readString(sources.resolve(locale + ".toml"));
            Map<String, String> values = TomlLanguageParser.parseText(content);
            assertThat(values.keySet()).as(locale).containsExactlyInAnyOrderElementsOf(catalog.ids());
            assertThat(TomlLanguageParser.parseValidText(content, catalog)).as(locale).hasSameSizeAs(values);
            service.validateDownloadedContent(locale, content);
            LocaleOverlay.Builder overlay = LocaleOverlay.builder(locale);
            values.forEach(overlay::text);
            LocalizationSnapshot.create(new LocalizationCandidate(catalog, List.of(overlay.build()), PluralSelector.oneOther()));
            for (MessageKey key : catalog.keys()) {
                assertThat(values.get(key.id())).as(locale + ":" + key.id()).isNotBlank();
            }
            assertVariableHeader(content, catalog);
        }
        service.close();
    }

    @Test
    void downloadedLanguageStillCompletesWhenTheWatcherNotificationFails() throws IOException {
        LanguageService service = service();
        write("de_DE", "[mode]\ncreative = \"Bauen\"\n");
        service.setSelfWriteListener((file, content) -> {
            throw new IllegalStateException("Observer failed");
        });
        Path installed = service.languageFile("de_DE").toPath();
        RemoteLanguageCatalog.DownloadResult result = new RemoteLanguageCatalog.DownloadResult("de_DE",
                URI.create("https://raw.githubusercontent.com/VolmitSoftware/GamemodeSwitcher/main/"
                        + "src/main/resources/languages/de_DE.toml"), installed, null);
        AtomicReference<RemoteLanguageCatalog.DownloadResult> completed = new AtomicReference<>();

        service.remoteInstallCompleted(installed.toFile(), result, completed::set);

        assertThat(completed.get()).isEqualTo(result);
        assertThat(service.prepare("de_DE").selectionReady()).isTrue();
        service.close();
    }

    @Test
    void remoteRequestsPreserveAnExistingEditedLanguageFile() throws IOException {
        LanguageService service = service();
        String content = "# Local edit\n[mode]\ncreative = \"Bauen\"\n";
        write("de_DE", content);
        AtomicReference<RemoteLanguageCatalog.DownloadResult> completed = new AtomicReference<>();

        assertThat(service.requestRemote("de_DE", completed::set)).isEqualTo(RemoteLanguageCatalog.RequestState.CURRENT);
        assertThat(completed.get()).isNull();
        assertThat(Files.readString(service.languageFile("de_DE").toPath())).isEqualTo(content);
        service.close();
    }

    @Test
    void preservesExistingPartialFilesAndFallsBackToBuiltInEnglish() throws IOException {
        LanguageService service = service();
        write("fr_FR", "[mode]\nsurvival = \"Local survival\"\ncreative = 42\nadventure = \"{wrong}\"\n");
        String original = Files.readString(service.languageFile("fr_FR").toPath());
        service.reload("fr_FR");

        assertThat(service.render(SwitcherMessages.MODE_SURVIVAL)).isEqualTo("Local survival");
        assertThat(service.render(SwitcherMessages.MODE_CREATIVE)).isEqualTo("Creative");
        assertThat(service.render(SwitcherMessages.MODE_ADVENTURE)).isEqualTo("Adventure");
        assertThat(Files.readString(service.languageFile("fr_FR").toPath())).isEqualTo(original);
    }

    @Test
    void malformedSelectedFileUsesEnglishWithoutReplacingTheFile() throws IOException {
        LanguageService service = service();
        byte[] content = "[mode\ncreative = \"broken\"\n".getBytes(StandardCharsets.UTF_8);
        write("fr_FR", new String(content, StandardCharsets.UTF_8));
        service.reload("fr_FR");

        assertThat(service.render(SwitcherMessages.MODE_CREATIVE)).isEqualTo("Creative");
        assertThat(Files.readAllBytes(service.languageFile("fr_FR").toPath())).containsExactly(content);
    }

    @Test
    void personalMissingEntriesNeverUseTheNonEnglishServerLocale() throws Exception {
        LanguageService service = service();
        write("fr_FR", "[mode]\ncreative = \"Server creative\"\n");
        write("de_DE", "[mode]\nsurvival = \"Personal survival\"\n");
        service.reload("fr_FR");
        PluginLanguageService selections = service.initializeSelections(() -> "fr_FR", (locale, snapshot) -> service.reload(locale));
        UUID playerId = UUID.randomUUID();
        try {
            selections.selectPlayer(playerId, "de_DE").get(5, TimeUnit.SECONDS);
            assertThat(LanguageAudience.call(playerId, () -> service.render(SwitcherMessages.MODE_SURVIVAL)))
                    .isEqualTo("Personal survival");
            assertThat(LanguageAudience.call(playerId, () -> service.render(SwitcherMessages.MODE_CREATIVE)))
                    .isEqualTo("Creative");
            assertThat(service.render(SwitcherMessages.MODE_CREATIVE)).isEqualTo("Server creative");
            assertThat(directory.resolve("languages/language-preferences.properties")).isRegularFile();
        } finally {
            service.close();
        }
    }

    @Test
    void pendingPersonalSelectionUsesEnglishInsteadOfTheServerLanguage() throws Exception {
        LanguageService service = service();
        write("fr_FR", "[mode]\ncreative = \"Server creative\"\n");
        write("de_DE", "[mode]\nsurvival = \"Personal survival\"\n");
        service.reload("fr_FR");
        PluginLanguageService selections = service.initializeSelections(() -> "fr_FR", (locale, snapshot) -> service.reload(locale));
        UUID playerId = UUID.randomUUID();
        try {
            selections.selectPlayer(playerId, "de_DE").get(5, TimeUnit.SECONDS);
            selections.invalidate();
            synchronized (service) {
                assertThat(LanguageAudience.call(playerId, () -> service.render(SwitcherMessages.MODE_CREATIVE)))
                        .isEqualTo("Creative");
            }
        } finally {
            service.close();
        }
    }

    @Test
    void malformedPersonalCatalogSurvivesAnUnrelatedDefaultReloadAndCanBeRepaired() throws Exception {
        LanguageService service = service();
        write("fr_FR", "[mode]\ncreative = \"Server creative\"\n");
        write("de_DE", "[mode]\ncreative = \"Personal creative\"\n");
        service.reload("fr_FR");
        PluginLanguageService selections = service.initializeSelections(() -> "fr_FR",
                (locale, snapshot) -> service.reload(locale));
        UUID player = UUID.randomUUID();
        try {
            selections.selectPlayer(player, "de_DE").get(5, TimeUnit.SECONDS);
            write("de_DE", "[unterminated");
            write("fr_FR", "[mode]\ncreative = \"Updated server creative\"\n");
            service.reload("fr_FR");
            service.reloadInstalledLocales();

            assertThat(LanguageAudience.call(player, () -> service.render(SwitcherMessages.MODE_CREATIVE)))
                    .isEqualTo("Personal creative");
            assertThat(service.render(SwitcherMessages.MODE_CREATIVE)).isEqualTo("Updated server creative");
            assertThat(Files.readString(service.languageFile("de_DE").toPath())).isEqualTo("[unterminated");

            write("de_DE", "[mode]\ncreative = \"Repaired personal creative\"\n");
            service.reloadInstalledLocales();
            assertThat(LanguageAudience.call(player, () -> service.render(SwitcherMessages.MODE_CREATIVE)))
                    .isEqualTo("Repaired personal creative");
            assertThat(selections.playerLocale(player)).contains("de_DE");
        } finally {
            service.close();
        }
    }

    @Test
    void personalHotReloadUsesEnglishForMissingEntriesAndKeepsOtherTranslations() throws Exception {
        LanguageService service = service();
        write("fr_FR", "[mode]\ncreative = \"Server creative\"\n");
        write("de_DE", "[mode]\ncreative = \"Personal creative\"\n");
        service.reload("fr_FR");
        PluginLanguageService selections = service.initializeSelections(() -> "fr_FR",
                (locale, snapshot) -> service.reload(locale));
        UUID player = UUID.randomUUID();
        try {
            selections.selectPlayer(player, "de_DE").get(5, TimeUnit.SECONDS);
            write("de_DE", "[mode]\nsurvival = \"Personal survival\"\n");
            service.reloadInstalledLocales();
            assertThat(LanguageAudience.call(player, () -> service.render(SwitcherMessages.MODE_CREATIVE)))
                    .isEqualTo("Creative");
            assertThat(LanguageAudience.call(player, () -> service.render(SwitcherMessages.MODE_SURVIVAL)))
                    .isEqualTo("Personal survival");
            assertThat(service.render(SwitcherMessages.MODE_CREATIVE)).isEqualTo("Server creative");
            assertThat(selections.playerLocale(player)).contains("de_DE");
        } finally {
            service.close();
        }
    }

    @Test
    void deletedPersonalCatalogUsesEnglishWithoutRecreatingItOrChangingTheSavedChoice() throws Exception {
        LanguageService service = service();
        write("fr_FR", "[mode]\ncreative = \"Server creative\"\n");
        write("de_DE", "[mode]\ncreative = \"Personal creative\"\n");
        service.reload("fr_FR");
        PluginLanguageService selections = service.initializeSelections(() -> "fr_FR",
                (locale, snapshot) -> service.reload(locale));
        UUID player = UUID.randomUUID();
        try {
            selections.selectPlayer(player, "de_DE").get(5, TimeUnit.SECONDS);
            Files.delete(service.languageFile("de_DE").toPath());
            service.reload("fr_FR");
            service.reloadInstalledLocales();
            assertThat(LanguageAudience.call(player, () -> service.render(SwitcherMessages.MODE_CREATIVE)))
                    .isEqualTo("Creative");
            assertThat(service.languageFile("de_DE")).doesNotExist();
            assertThat(selections.playerLocale(player)).contains("de_DE");
            assertThat(Files.readString(directory.resolve("languages/language-preferences.properties")))
                    .contains(player + "=de_DE");

            write("de_DE", "[mode]\ncreative = \"Restored personal creative\"\n");
            service.reloadInstalledLocales();
            assertThat(LanguageAudience.call(player, () -> service.render(SwitcherMessages.MODE_CREATIVE)))
                    .isEqualTo("Restored personal creative");
            assertThat(service.render(SwitcherMessages.MODE_CREATIVE)).isEqualTo("Server creative");
        } finally {
            service.close();
        }
    }

    @Test
    void editorPreservesCommentsAndUnrelatedValues() throws IOException {
        LanguageService service = service();
        write("en_US", "# Local messages\n[mode]\ncreative = \"Build\"\n\n[custom]\nnumber = 4\n");
        service.reload("en_US");
        service.updateMessage("en_US", SwitcherMessages.MODE_CREATIVE.id(), "Builder");

        assertThat(service.render(SwitcherMessages.MODE_CREATIVE)).isEqualTo("Builder");
        assertThat(Files.readString(service.languageFile("en_US").toPath()))
                .contains("# Local messages", "number = 4", "Builder");
    }

    @Test
    void editorRejectsInvalidPlaceholdersAndMarkupBeforeWriting() throws IOException {
        LanguageService service = service();
        service.reload("en_US");
        byte[] before = Files.readAllBytes(service.languageFile("en_US").toPath());

        assertThatThrownBy(() -> service.updateMessage("en_US", SwitcherMessages.SWITCH_SUCCESS.id(), "Missing mode"))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> service.updateMessage("en_US", SwitcherMessages.SWITCH_SUCCESS.id(), "<red>{mode}"))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> service.updateMessage("en_US", SwitcherMessages.SWITCH_SUCCESS.id(), "<hover:show_text:'{mode}'>text</hover>"))
                .isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(service.languageFile("en_US").toPath())).containsExactly(before);
    }

    @Test
    void editorRejectsAStaleMessage() throws Exception {
        LanguageService service = service();
        service.reload("en_US");
        PluginLanguageEditor.Options editor = service.editorOptions();
        TextValue original = (TextValue) editor.loader().load("en_US").value(SwitcherMessages.MODE_CREATIVE);
        service.updateMessage("en_US", SwitcherMessages.MODE_CREATIVE.id(), "Building");

        assertThatThrownBy(() -> editor.writer().write(new PluginLanguageEditor.Edit("en_US",
                SwitcherMessages.MODE_CREATIVE.id(), original, new TextValue("Other"))))
                .isInstanceOf(IOException.class).hasMessageContaining("changed");
        assertThat(service.render(SwitcherMessages.MODE_CREATIVE)).isEqualTo("Building");
    }

    @Test
    void escapesUntrustedArgumentsAndResolvesLocaleAliases() throws IOException {
        LanguageService service = service();
        service.reload("en_US");
        String rendered = service.render(SwitcherMessages.SWITCH_BLOCKED_WORLD,
                MessageArgs.builder().untrusted("world", "<red>&c[FF0000]world").build());

        assertThat(rendered).contains("\\<red>", "\\&c", "\\[FF0000]");
        assertThat(service.languageFile("JA_jp")).isEqualTo(service.languageFile("ja-JP"));
        assertThat(service.availableLocale("ja_JP")).contains("ja-JP");
        assertThatThrownBy(() -> service.prepare("../outside")).isInstanceOf(IllegalArgumentException.class);
    }

    private LanguageService service() {
        return new LanguageService(directory.toFile(), Logger.getLogger(LanguageServiceTest.class.getName()));
    }

    private void assertVariableHeader(String content, MessageCatalog catalog) {
        String header = content.substring(0, content.indexOf("\n["));
        for (MessageKey key : catalog.keys()) {
            for (String variable : key.placeholders()) {
                assertThat(header).as(key.id()).contains("#   {" + variable + "}  ");
            }
        }
    }

    private void write(String locale, String content) throws IOException {
        Files.createDirectories(directory.resolve("languages"));
        Files.writeString(directory.resolve("languages/" + locale + ".toml"), content);
    }
}
