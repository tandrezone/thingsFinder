<?php
/**
 * "Add items from a photo": runs a photo of a printed/handwritten list
 * through the tesseract OCR command-line program (a system package, not a
 * PHP dependency — same idea as relying on the optional GD extension for
 * PNG output) and turns the extracted text into a list of candidate item
 * names for the person to edit before adding them.
 *
 * This reads text — it does not recognize objects in a photo of loose
 * items. Point the camera at a written/printed list (a packing list, a
 * receipt, a label), not at the items themselves.
 */

require_once __DIR__ . '/auth.php'; // for ensure_session_started()

const OCR_MAX_UPLOAD_BYTES = 8 * 1024 * 1024; // 8MB
const OCR_ALLOWED_MIME = ['image/jpeg', 'image/png', 'image/webp', 'image/bmp'];

/** Whether the server can actually run OCR: needs shell_exec and the tesseract binary. */
function ocr_available(): bool
{
    static $cached = null;
    if ($cached !== null) {
        return $cached;
    }
    if (!function_exists('shell_exec') || ocr_is_disabled_function('shell_exec')) {
        return $cached = false;
    }
    $out = @shell_exec('tesseract --version 2>&1');
    return $cached = ($out !== null && $out !== '' && stripos($out, 'tesseract') !== false);
}

function ocr_is_disabled_function(string $name): bool
{
    $disabled = array_map('trim', explode(',', (string)ini_get('disable_functions')));
    return in_array($name, $disabled, true);
}

/**
 * Runs OCR on an uploaded image file and returns the extracted candidate
 * item lines (name + default quantity of 1), or throws a RuntimeException
 * with a human-readable message on any failure — callers should catch
 * that and flash it rather than letting it become a fatal error.
 */
function ocr_extract_items(string $tmpUploadPath, string $originalName): array
{
    if (!ocr_available()) {
        throw new RuntimeException(
            'OCR isn\'t available on this server — it needs the "tesseract" command-line program installed '
            . 'and PHP\'s shell_exec() enabled. See the README for install instructions.'
        );
    }

    $size = filesize($tmpUploadPath);
    if ($size === false || $size > OCR_MAX_UPLOAD_BYTES) {
        throw new RuntimeException('That image is too large (max 8MB).');
    }

    // Confirm it's actually a decodable raster image before handing it to
    // tesseract — getimagesize() also rejects disguised non-image uploads.
    $info = @getimagesize($tmpUploadPath);
    if ($info === false) {
        throw new RuntimeException(
            'Couldn\'t read that as an image (HEIC photos from iPhones aren\'t supported — set your camera to '
            . '"Most Compatible" / JPEG, or use a PNG/JPEG file).'
        );
    }

    $ext = ['image/jpeg' => 'jpg', 'image/png' => 'png', 'image/webp' => 'webp', 'image/bmp' => 'bmp'][$info['mime']] ?? null;
    if ($ext === null) {
        throw new RuntimeException('Unsupported image type — please use a JPEG or PNG.');
    }

    $workDir = sys_get_temp_dir() . '/thingsfinder-ocr-' . bin2hex(random_bytes(8));
    if (!mkdir($workDir, 0700, true)) {
        throw new RuntimeException('Could not prepare a workspace for OCR.');
    }
    $imagePath = $workDir . '/upload.' . $ext;

    try {
        if (!copy($tmpUploadPath, $imagePath)) {
            throw new RuntimeException('Could not read the uploaded image.');
        }

        $escapedImage = escapeshellarg($imagePath);
        // "stdout" as the output base makes tesseract print the text
        // straight to stdout instead of writing a .txt file.
        $cmd = 'tesseract ' . $escapedImage . ' stdout 2>&1';
        $text = @shell_exec($cmd);

        if ($text === null || trim($text) === '') {
            return [];
        }

        return ocr_parse_lines($text);
    } finally {
        // Never keep the uploaded photo around longer than it takes to OCR it.
        @unlink($imagePath);
        @rmdir($workDir);
    }
}

/**
 * Turns raw OCR text into a deduplicated list of candidate items, each
 * defaulting to quantity 1 — the person reviews/edits/removes lines before
 * anything is actually added, so this only needs to be a reasonable first
 * guess, not perfect.
 */
function ocr_parse_lines(string $text): array
{
    $items = [];
    $seen = [];
    foreach (preg_split('/\r\n|\r|\n/', $text) as $line) {
        $line = trim(preg_replace('/\s+/u', ' ', $line));
        // Skip blank lines, stray punctuation-only OCR noise, and anything
        // implausibly short to be an item name.
        if ($line === '' || mb_strlen($line, 'UTF-8') < 2) {
            continue;
        }
        if (!preg_match('/[a-zA-Z0-9\x{00C0}-\x{024F}]/u', $line)) {
            continue;
        }
        $key = mb_strtolower($line, 'UTF-8');
        if (isset($seen[$key])) {
            continue;
        }
        $seen[$key] = true;
        $items[] = ['name' => $line, 'quantity' => 1];
    }
    return $items;
}

/**
 * The in-progress "review before adding" list lives in the session, keyed
 * per box/place (e.g. "box:5" or "place:2") so scanning a photo on one box
 * doesn't clobber an in-progress review on another. Nothing here touches
 * the database — only scan_add_all actually creates items.
 */
function ocr_review_get(string $containerKey): array
{
    ensure_session_started();
    return $_SESSION['ocr_review'][$containerKey] ?? [];
}

function ocr_review_set(string $containerKey, array $items): void
{
    ensure_session_started();
    $_SESSION['ocr_review'][$containerKey] = $items;
}

function ocr_review_clear(string $containerKey): void
{
    ensure_session_started();
    unset($_SESSION['ocr_review'][$containerKey]);
}
