package com.aitp.orenda.tripadvisor.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Guards the crawler against disk-full crashes. Chromium's renderer dies with
 * "Target crashed" when it cannot write shared memory or its profile because the
 * disk is full (the crawler's image downloads are the main disk consumer). Callers
 * should consult {@link #hasEnoughSpace(Path)} before launching a browser or
 * downloading images and stop gracefully when it returns {@code false}.
 */
@Slf4j
@Component
public class DiskSpaceGuard {

    private final long minFreeBytes;

    public DiskSpaceGuard(
            @Value("${tripadvisor.crawler.min-free-disk-bytes:1073741824}") long minFreeBytes) {
        this.minFreeBytes = Math.max(0, minFreeBytes);
    }

    public boolean hasEnoughSpace(Path path) {
        return freeBytes(path) >= minFreeBytes;
    }

    public long freeBytes(Path path) {
        if (path == null) {
            return Long.MAX_VALUE;
        }
        try {
            Path target = Files.isDirectory(path) ? path : path.getParent();
            if (target == null || !Files.exists(target)) {
                return Long.MAX_VALUE;
            }
            FileStore store = Files.getFileStore(target);
            return store.getUsableSpace();
        } catch (IOException e) {
            log.warn("Disk space guard: cannot determine free space for {}. error={}", path, e.getMessage());
            return Long.MAX_VALUE;
        }
    }

    public long minFreeBytes() {
        return minFreeBytes;
    }
}