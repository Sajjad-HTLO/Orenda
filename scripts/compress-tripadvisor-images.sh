#!/usr/bin/env bash
#
# Compress the Tripadvisor images downloaded on a given day into a dated
# archive, then (optionally) remove the originals to free disk space.
#
# By default this is designed to run nightly at 00:00 and compress the PREVIOUS
# day's images (the day that just ended). Pass an explicit date to override.
#
# Archive naming: tripadvisor-images-YYYY-MM-DD.tar.gz  (the date is the day the
# images were downloaded, i.e. their file modification date).
#
# USAGE:
#   ./scripts/compress-tripadvisor-images.sh                # previous day
#   ./scripts/compress-tripadvisor-images.sh 2026-08-20     # explicit date
#   ./scripts/compress-tripadvisor-images.sh --keep ...     # keep originals
#
# ENV / CONFIG:
#   IMAGE_DIR    Source image directory  (default: data/tripadvisor-images)
#   ARCHIVE_DIR  Where archives go       (default: data/tripadvisor-images-archive)
#   COMPRESSOR   gzip | zstd | xz        (default: gzip)
#
# Exit code 0 on success (including "nothing to compress"). Non-zero on failure.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE_DIR="${IMAGE_DIR:-$ROOT_DIR/data/tripadvisor-images}"
ARCHIVE_DIR="${ARCHIVE_DIR:-$ROOT_DIR/data/tripadvisor-images-archive}"
COMPRESSOR="${COMPRESSOR:-gzip}"

KEEP_ORIGINALS=0

# --- Parse args -------------------------------------------------------------
DATE_ARG=""
for arg in "$@"; do
  case "$arg" in
    --keep) KEEP_ORIGINALS=1 ;;
    *)
      if [[ "$arg" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
        DATE_ARG="$arg"
      else
        echo "Unknown argument: $arg" >&2
        echo "Usage: $0 [YYYY-MM-DD] [--keep]" >&2
        exit 2
      fi
      ;;
  esac
done

# Default to the previous day (for a nightly 00:00 run).
if [[ -z "$DATE_ARG" ]]; then
  DATE_ARG="$(date -d 'yesterday' +%Y-%m-%d 2>/dev/null || date -v-1d +%Y-%m-%d)"
fi
TARGET_DATE="$DATE_ARG"

echo "=== Tripadvisor image nightly compression ==="
echo "Target date (images downloaded on): $TARGET_DATE"
echo "Image dir:   $IMAGE_DIR"
echo "Archive dir: $ARCHIVE_DIR"
echo "Compressor:  $COMPRESSOR"
echo "Keep originals after archive: $([ "$KEEP_ORIGINALS" -eq 1 ] && echo yes || echo no)"

if [[ ! -d "$IMAGE_DIR" ]]; then
  echo "Image dir does not exist: $IMAGE_DIR (nothing to do)"
  exit 0
fi

# Build the file list: files whose mtime falls on the target calendar day.
mapfile -d '' FILES < <(
  find "$IMAGE_DIR" -type f -newermt "$TARGET_DATE 00:00:00" ! -newermt "$TARGET_DATE 23:59:59" -print0 2>/dev/null
)

COUNT="${#FILES[@]}"
if [[ "$COUNT" -eq 0 ]]; then
  echo "No images downloaded on $TARGET_DATE; nothing to compress."
  exit 0
fi

mkdir -p "$ARCHIVE_DIR"
ARCHIVE_FILE="$ARCHIVE_DIR/tripadvisor-images-$TARGET_DATE.tar.gz"

# If an archive for this date already exists, append is unsafe -> refuse to
# clobber; the user can delete it manually if a rebuild is intended.
if [[ -e "$ARCHIVE_FILE" ]]; then
  echo "Archive already exists for $TARGET_DATE: $ARCHIVE_FILE" >&2
  echo "Refusing to overwrite. Remove it first if a rebuild is intended." >&2
  exit 1
fi

# Create a temporary file list (null-delimited paths) to feed tar.
TMP_LIST="$(mktemp)"
trap 'rm -f "$TMP_LIST"' EXIT
printf '%s\0' "${FILES[@]}" > "$TMP_LIST"

echo "Compressing $COUNT image(s) -> $ARCHIVE_FILE"
# -C sets the working dir to the parent of IMAGE_DIR so the archive stores clean
# relative paths like `tripadvisor-images/12345/a.jpg` instead of absolute paths.
# --null reads the null-delimited list; -T uses it; -czf creates a gzip tar.
PARENT_DIR="$(dirname "$IMAGE_DIR")"
# Convert the absolute file paths to paths relative to PARENT_DIR for the archive.
while IFS= read -r -d '' f; do
  rel="${f#"$PARENT_DIR"/}"
  printf '%s\0' "$rel"
done < "$TMP_LIST" | tar -C "$PARENT_DIR" --null -T - -czf "$ARCHIVE_FILE"

echo "Archive created: $ARCHIVE_FILE ($(du -sh "$ARCHIVE_FILE" | cut -f1))"

# Remove the originals unless --keep was passed. Only delete the exact files we
# archived (never the archive itself, never the whole dir).
if [[ "$KEEP_ORIGINALS" -eq 0 ]]; then
  # Re-derive the file list (safe: we only delete files from that day).
  while IFS= read -r -d '' f; do
    if [[ -f "$f" && "$f" != "$ARCHIVE_FILE" ]]; then
      rm -f "$f"
    fi
  done < "$TMP_LIST"
  # Clean up now-empty per-POI subdirectories.
  find "$IMAGE_DIR" -mindepth 1 -type d -empty -delete 2>/dev/null || true
  echo "Removed $COUNT original image file(s)."
fi

echo "=== Done. Date=$TARGET_DATE files=$COUNT archive=$ARCHIVE_FILE ==="
