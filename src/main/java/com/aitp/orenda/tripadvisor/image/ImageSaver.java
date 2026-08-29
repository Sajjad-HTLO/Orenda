package com.aitp.orenda.tripadvisor.image;

import com.aitp.orenda.tripadvisor.util.DiskSpaceGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Downloads image URLs for a POI, saves the binary to local disk under
 * {@code data/tripadvisor-images/{osmId}/{uuid}.{ext}} and records the file
 * reference in the {@code poi_image} table. Storing binaries on local disk
 * (rather than in the DB) keeps the DB small (important for Neon's 512MB limit)
 * while the DB row references the file for later retrieval/serving.
 */
@Slf4j
@Component
public class ImageSaver {

    private final ImageDownloader downloader;
    private final ImageRepository imageRepository;
    private final DiskSpaceGuard diskSpaceGuard;
    private final int maxImagesPerPoi;
    private final boolean enabled;
    private final Path baseDir;

    public ImageSaver(
            ImageDownloader downloader,
            ImageRepository imageRepository,
            DiskSpaceGuard diskSpaceGuard,
            @Value("${tripadvisor.crawler.image-download.max-images-per-poi:50}") int maxImagesPerPoi,
            @Value("${tripadvisor.crawler.image-download.enabled:true}") boolean enabled,
            @Value("${tripadvisor.crawler.image-download.base-dir:data/tripadvisor-images}") String baseDir) {
        this.downloader = downloader;
        this.imageRepository = imageRepository;
        this.diskSpaceGuard = diskSpaceGuard;
        this.maxImagesPerPoi = Math.max(1, maxImagesPerPoi);
        this.enabled = enabled;
        this.baseDir = Path.of(baseDir);
    }

    /**
     * @return the number of images successfully downloaded, saved to disk and
     *         recorded in the DB.
     */
    public int save(long osmId, String osmType, String source, List<String> imageUrls) {
        if (!enabled) {
            log.info("Tripadvisor image download disabled; skipping images for osmId={}.", osmId);
            return 0;
        }
        if (imageUrls == null || imageUrls.isEmpty()) {
            return 0;
        }
        Path poiDir = baseDir.resolve(String.valueOf(osmId));

        int saved = 0;
        int considered = 0;
        for (String url : imageUrls) {
            if (considered >= maxImagesPerPoi) {
                log.info("Tripadvisor image saver reached max-images-per-poi={} for osmId={}; stopping.", maxImagesPerPoi, osmId);
                break;
            }
            considered++;
            if (!diskSpaceGuard.hasEnoughSpace(poiDir)) {
                log.warn("Tripadvisor image saver stopped: free disk space is below {} bytes (available={}) for osmId={}. " +
                                "The crawler will continue without images; free space to resume image downloads.",
                        diskSpaceGuard.minFreeBytes(), diskSpaceGuard.freeBytes(poiDir), osmId);
                break;
            }
            try {
                ImageDownloader.ImageData data = downloader.download(url);
                if (data == null) {
                    continue;
                }
                if (!ensureDir(poiDir)) {
                    return 0;
                }
                Path file = writeFile(poiDir, data);
                if (file == null) {
                    continue;
                }
                int rows = imageRepository.upsertImage(
                        osmId, osmType, source, url, data.mimeType(), null, null,
                        file.toString(), data.data().length);
                saved += rows;
            } catch (Exception e) {
                log.debug("Failed to save image for osmId={} url={}: {}", osmId, url, e.getMessage());
            }
        }
        log.info("Tripadvisor image saver done. osmId={}, osmType={}, source={}, imageUrls={}, considered={}, stored={}, dir={}",
                osmId, osmType, source, imageUrls.size(), considered, saved, poiDir);
        return saved;
    }

    private boolean ensureDir(Path poiDir) {
        try {
            Files.createDirectories(poiDir);
            return true;
        } catch (IOException e) {
            log.warn("Tripadvisor image saver: cannot create directory {}. error={}", poiDir, e.getMessage());
            return false;
        }
    }

    private Path writeFile(Path poiDir, ImageDownloader.ImageData data) throws IOException {
        String ext = extensionFor(data.mimeType());
        Path file = poiDir.resolve(UUID.randomUUID().toString().replace("-", "") + "." + ext);
        Files.write(file, data.data());
        return file;
    }

    private String extensionFor(String mimeType) {
        if (mimeType == null) {
            return "jpg";
        }
        return switch (mimeType) {
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/avif" -> "avif";
            case "image/svg+xml" -> "svg";
            default -> "jpg";
        };
    }
}