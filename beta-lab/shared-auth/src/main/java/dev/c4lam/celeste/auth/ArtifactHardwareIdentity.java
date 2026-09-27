package dev.c4lam.celeste.auth;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.function.Supplier;

/** Loads only the hardware reader against the game's existing JNA owner. */
public final class ArtifactHardwareIdentity {
    private static final String READER = "dev.c4lam.celeste.auth.WindowsArtifactHardware";
    private ArtifactHardwareIdentity() { }

    public static Supplier<String> forGame(ClassLoader gameLoader) throws GeneralSecurityException {
        try {
            HardwareLoader loader = new HardwareLoader(gameLoader);
            // Fail before any request if the game cannot supply this reader's JNA ABI.
            for (String suffix : new String[] {"", "$Processor", "$SystemApi"}) {
                Class<?> type = Class.forName(READER + suffix, false, loader);
                type.getDeclaredMethods(); type.getDeclaredFields(); type.getDeclaredConstructors();
            }
            Method current = Class.forName(READER, true, loader).getMethod("current");
            return () -> {
                try { return (String) current.invoke(null); }
                catch (IllegalAccessException | InvocationTargetException failure) {
                    throw new IllegalStateException("Windows hardware identity unavailable", failure);
                }
            };
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new GeneralSecurityException("Windows hardware identity unavailable", failure);
        }
    }

    /** Reader and its protected decoder dependencies share one owner; JNA stays game-owned. */
    private static final class HardwareLoader extends ClassLoader {
        private final ClassLoader game;
        private final ClassLoader archiveOwner;
        HardwareLoader(ClassLoader game) {
            super(ClassLoader.getPlatformClassLoader());
            this.game = Objects.requireNonNull(game, "gameLoader");
            this.archiveOwner = ArtifactHardwareIdentity.class.getClassLoader();
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> value = findLoadedClass(name);
                if (value == null) {
                    if (name.startsWith("com.sun.jna.")) value = game.loadClass(name);
                    else if (name.equals(READER) || name.startsWith(READER + "$")) value = findClass(name);
                    else return super.loadClass(name, resolve);
                }
                if (resolve) resolveClass(value);
                return value;
            }
        }
        @Override public URL getResource(String name) {
            URL platform = super.getResource(name);
            return platform != null ? platform : archiveOwner.getResource(name);
        }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            // The already verified A archive is the only source of decoder classes.
            // Parent delegation would split the decoder's caller identity from the reader.
            if (name.startsWith("com.sun.jna.")) throw new ClassNotFoundException(name);
            try (InputStream source = archiveOwner.getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (source == null) throw new ClassNotFoundException(name);
                byte[] code = source.readNBytes(131073);
                if (code.length > 131072) throw new ClassNotFoundException("Hardware reader exceeds class bound");
                return defineClass(name, code, 0, code.length);
            } catch (IOException failure) { throw new ClassNotFoundException(name, failure); }
        }
    }
}
