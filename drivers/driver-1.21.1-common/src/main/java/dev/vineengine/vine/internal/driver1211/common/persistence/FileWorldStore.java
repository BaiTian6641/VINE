package dev.vineengine.vine.internal.driver1211.common.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.internal.spi.WorldStoreSpi;

/**
 * File-backed per-world store (sub-14 Stage B, generalized in sub-02 Stage D):
 * one blob file per engine key under {@code <world>/vine/}, written atomically
 * (temp + move) so a crash mid-save can never leave a half-written store.
 * Loader-neutral — every driver mounts one on world load and flushes it on
 * world save.
 */
public final class FileWorldStore implements WorldStoreSpi {

    private static final Logger LOG = LoggerFactory.getLogger(FileWorldStore.class);

    private final Path directory;

    public FileWorldStore(Path directory) {
        this.directory = directory;
    }

    @Override
    public byte[] load(String key) {
        Path file = file(key);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            LOG.warn("[VINE] world store '{}' unreadable ({}), starting empty", key, file, e);
            return null;
        }
    }

    @Override
    public void save(String key, byte[] blob) {
        Path file = file(key);
        try {
            Files.createDirectories(directory);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, blob);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.info("[VINE] world store '{}' flushed ({} bytes)", key, blob.length);
        } catch (IOException e) {
            // The engine logs and continues; world save must never crash on this.
            throw new IllegalStateException("world store write failed: " + file, e);
        }
    }

    private Path file(String key) {
        return directory.resolve(key + ".vbl");
    }
}
