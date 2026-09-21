package com.volmit.gsw;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

final class RuntimeJarTest {
    private static final String PLUGIN_PACKAGE = "com.volmit.gsw.";
    private static final String LIBRARY_PACKAGE = PLUGIN_PACKAGE + "libs.";

    private static RuntimeLoader loader;

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void openRuntimeLoader() throws Exception {
        String artifact = Objects.requireNonNull(System.getProperty("gamemodeswitcher.runtimeJar"));
        String libraries = Objects.requireNonNull(System.getProperty("gamemodeswitcher.runtimeTestLibraries"));
        loader = new RuntimeLoader(new URL[]{Path.of(artifact).toUri().toURL(), Path.of(libraries).toUri().toURL()},
                RuntimeJarTest.class.getClassLoader());
    }

    @AfterAll
    static void closeRuntimeLoader() throws Exception {
        loader.close();
        loader = null;
    }

    @Test
    void pluginMainLoadsBeforeRuntimeLibrariesAreAvailable() throws Exception {
        String artifact = Objects.requireNonNull(System.getProperty("gamemodeswitcher.runtimeJar"));
        try (RuntimeLoader bootstrap = new RuntimeLoader(new URL[]{Path.of(artifact).toUri().toURL()},
                RuntimeJarTest.class.getClassLoader())) {
            assertThat(Class.forName(PLUGIN_PACKAGE + "GamemodeSwitcher", true, bootstrap)).isNotNull();
        }
    }

    @Test
    void packagedBootstrapIsAvailable() throws Exception {
        assertThat(loader.loadClass(LIBRARY_PACKAGE + "slimjar.app.builder.SpigotApplicationBuilder")
                .getDeclaredConstructors()).isNotEmpty();
    }

    @Test
    void packagedConfigCreatesAndReloadsToml() throws Exception {
        Class<?> serviceType = loader.loadClass(PLUGIN_PACKAGE + "config.ConfigService");
        Object service = serviceType.getConstructor(File.class).newInstance(temporaryDirectory.toFile());
        serviceType.getMethod("initialize").invoke(service);
        Path configFile = temporaryDirectory.resolve("config.toml");
        assertThat(Files.readString(configFile)).isNotBlank();
        serviceType.getMethod("reload").invoke(service);
        assertThat(serviceType.getMethod("runtime").invoke(service)).isNotNull();
    }

    @Test
    void packagedAdventureLoadsSerializers() throws Exception {
        Class<?> textType = loader.loadClass(LIBRARY_PACKAGE + "volmlib.util.plugin.ComponentText");
        Object text = textType.getMethod("markup", String.class).invoke(null, "<red>Switcher</red>");
        assertThat(textType.getMethod("plain").invoke(text)).isEqualTo("Switcher");
        assertThat((String) textType.getMethod("legacy").invoke(text)).endsWith("Switcher");
        assertThat((String) textType.getMethod("miniMessage").invoke(text)).contains("<red>Switcher");
    }

    @Test
    void packagedDirectorReflectsCommandTree() throws Exception {
        Class<?> pluginType = loader.loadClass(PLUGIN_PACKAGE + "GamemodeSwitcher");
        Class<?> commandsType = loader.loadClass(PLUGIN_PACKAGE + "command.SwitcherCommands");
        Object commands = commandsType.getConstructor(pluginType).newInstance(new Object[]{null});
        Class<?> factoryType = loader.loadClass(LIBRARY_PACKAGE + "volmlib.util.director.compat.DirectorEngineFactory");
        Object engine = factoryType.getMethod("create", Object.class).invoke(null, commands);
        Object root = engine.getClass().getMethod("getRoot").invoke(engine);
        List<?> children = (List<?>) root.getClass().getMethod("getChildren").invoke(root);
        assertThat(children).isNotEmpty();
        for (Object child : children) {
            Object descriptor = child.getClass().getMethod("getDescriptor").invoke(child);
            assertThat((String) descriptor.getClass().getMethod("getName").invoke(descriptor)).isNotBlank();
        }
    }

    private static final class RuntimeLoader extends URLClassLoader {
        private RuntimeLoader(URL[] artifacts, ClassLoader parent) {
            super(artifacts, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(PLUGIN_PACKAGE)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
