package dev.vineengine.vine.internal.driver1211.common.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.internal.spi.SessionPersistenceSpi;

/**
 * File-backed session store (sub-14 Stage B): one blob file under the world
 * directory, written atomically (temp + move) so a crash mid-save can never
 * leave a half-written store. Loader-neutral — every driver mounts one on world
 * load and flushes it on world save.
 */
public final class FileSessionStore implements SessionPersistenceSpi {

    private static final Logger LOG = LoggerFactory.getLogger(FileSessionStore.class);

    private final Path file;

    public FileSessionStore(Path file) {
        this.file = file;
    }

    @Override
    public byte[] load() {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            LOG.warn("[VINE] session store unreadable ({}), starting empty", file, e);
            return null;
        }
    }

    @Override
    public void save(byte[] blob) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, blob);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.info("[VINE] session store flushed ({} bytes)", blob.length);
        } catch (IOException e) {
            // The engine logs and continues; world save must never crash on this.
            throw new IllegalStateException("session store write failed: " + file, e);
        }
    }
}
