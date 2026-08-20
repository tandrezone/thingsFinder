<?php
/** Small shared helpers used by both the UI (index.php) and the API (api.php). */

/**
 * Limits for JSON item import — referenced by the schema, the prompt and the
 * parser at the bottom of this file, so the documented shape and the enforced
 * shape can't drift apart.
 */
const IMPORT_MAX_ITEMS = 500;
const IMPORT_MAX_NAME_LENGTH = 200;
const IMPORT_MAX_QUANTITY = 100000;

function json_response($data, int $status = 200): void
{
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($data, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES);
    exit;
}

function json_error(string $message, int $status = 400): void
{
    json_response(['error' => $message], $status);
}

/** Reads a JSON request body; falls back to $_POST for form submissions. */
function read_body(): array
{
    $raw = file_get_contents('php://input');
    if ($raw !== false && $raw !== '') {
        $decoded = json_decode($raw, true);
        if (is_array($decoded)) {
            return $decoded;
        }
    }
    return $_POST;
}

function h(string $text): string
{
    return htmlspecialchars($text, ENT_QUOTES, 'UTF-8');
}

/**
 * Small dependency-free inline SVG icons (feather-icon style), used to give
 * buttons and headings a bit of visual scannability without any icon font
 * or build step. The raw path data lives in icon_paths() so the printable
 * label generator (includes/label.php) can reuse the exact same glyphs.
 */
function icon_paths(): array
{
    return [
        'search'   => '<circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>',
        'plus'     => '<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>',
        'trash'    => '<polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6"/><path d="M14 11v6"/><path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>',
        'edit'     => '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/>',
        'external' => '<path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/>',
        'download' => '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>',
        'upload'   => '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/>',
        'dots'     => '<circle cx="12" cy="5" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="12" cy="19" r="1.6"/>',
        'chevron'  => '<polyline points="9 6 15 12 9 18"/>',
        'check'    => '<polyline points="20 6 9 17 4 12"/>',
        'alert'    => '<circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>',
        'inbox'    => '<polyline points="22 12 16 12 14 15 10 15 8 12 2 12"/><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/>',
        'box'      => '<path d="M21 8v13H3V8"/><path d="M1 3h22v5H1z"/><line x1="10" y1="12" x2="14" y2="12"/>',
        'qr'       => '<rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/><line x1="14" y1="14" x2="14" y2="21"/><line x1="21" y1="14" x2="21" y2="14.01"/><line x1="14" y1="17.5" x2="17.5" y2="17.5"/><line x1="21" y1="21" x2="17.5" y2="21"/><line x1="17.5" y1="17.5" x2="17.5" y2="21"/>',
        'barcode'  => '<line x1="3" y1="5" x2="3" y2="19"/><line x1="7" y1="5" x2="7" y2="19"/><line x1="10" y1="5" x2="10" y2="19"/><line x1="14" y1="5" x2="14" y2="19"/><line x1="18" y1="5" x2="18" y2="19"/><line x1="21" y1="5" x2="21" y2="19"/>',
        'camera'   => '<path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/>',
        'pin'      => '<path d="M12 22s7-7.58 7-12A7 7 0 0 0 5 10c0 4.42 7 12 7 12z"/><circle cx="12" cy="10" r="2.5"/>',
        'hash'     => '<line x1="4" y1="9" x2="20" y2="9"/><line x1="4" y1="15" x2="20" y2="15"/><line x1="10" y1="3" x2="8" y2="21"/><line x1="16" y1="3" x2="14" y2="21"/>',
        'printer'  => '<polyline points="6 9 6 2 18 2 18 9"/><path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"/><rect x="6" y="14" width="12" height="8"/>',
        'users'    => '<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
        'lock'     => '<rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>',
        'user'     => '<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>',
        'log-out'  => '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/>',
        'eye'      => '<path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/>',
        'copy'     => '<rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1"/>',
        'sparkle'  => '<path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z"/><path d="M18.5 16.5l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7z"/>',
        'move'     => '<polyline points="5 9 2 12 5 15"/><polyline points="9 5 12 2 15 5"/><polyline points="15 19 12 22 9 19"/><polyline points="19 9 22 12 19 15"/><line x1="2" y1="12" x2="22" y2="12"/><line x1="12" y1="2" x2="12" y2="22"/>',
    ];
}

function icon(string $name, int $size = 16): string
{
    $paths = icon_paths();
    $body = $paths[$name] ?? $paths['dots'];
    return '<svg class="icon" width="' . $size . '" height="' . $size . '" viewBox="0 0 24 24" '
        . 'fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" '
        . 'stroke-linejoin="round" aria-hidden="true" focusable="false">' . $body . '</svg>';
}

/**
 * The <head> icon links, shared by both page shells in index.php so the two
 * can't drift apart. The box mark lives in assets/favicon.svg (modern
 * browsers prefer it and scale it to any size); favicon.ico sits at the
 * webroot because browsers request /favicon.ico on their own, and it carries
 * pixel-tuned 16/32/48 bitmaps that stay crisp where the SVG would blur.
 */
function favicon_tags(): string
{
    return '<link rel="icon" href="/favicon.ico" sizes="32x32">' . "\n"
        . '<link rel="icon" href="/assets/favicon.svg" type="image/svg+xml">' . "\n"
        . '<link rel="apple-touch-icon" href="/assets/apple-touch-icon.png">' . "\n"
        . '<link rel="manifest" href="/site.webmanifest">' . "\n"
        . '<meta name="theme-color" content="#b5652b">' . "\n";
}

/** Absolute base URL of the running app, e.g. "http://localhost:8000". */
function base_url(): string
{
    $https = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
        || (($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '') === 'https');
    $scheme = $https ? 'https' : 'http';
    $host = $_SERVER['HTTP_HOST'] ?? 'localhost';
    return $scheme . '://' . $host;
}

function current_path(): string
{
    $path = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH);
    $path = rawurldecode($path);
    if ($path === '' || $path === false) {
        $path = '/';
    }
    // Normalize: strip trailing slash (except root).
    if ($path !== '/' && substr($path, -1) === '/') {
        $path = substr($path, 0, -1);
    }
    return $path;
}

function path_segments(string $path): array
{
    if ($path === '/') {
        return [];
    }
    return explode('/', trim($path, '/'));
}

function redirect(string $to): void
{
    header('Location: ' . $to, true, 303);
    exit;
}

function flash_set(string $message, string $type = 'info'): void
{
    if (session_status() !== PHP_SESSION_ACTIVE) {
        session_start();
    }
    $_SESSION['flash'] = ['message' => $message, 'type' => $type];
}

function flash_take(): ?array
{
    if (session_status() !== PHP_SESSION_ACTIVE) {
        session_start();
    }
    if (!empty($_SESSION['flash'])) {
        $flash = $_SESSION['flash'];
        unset($_SESSION['flash']);
        return $flash;
    }
    return null;
}

/**
 * Carries a scanned-but-unrecognized barcode across the redirect after a
 * failed "add item" submission, so the add-item form can be re-shown open
 * with the barcode still filled in instead of making the person rescan it.
 */
function pending_barcode_set(string $barcode): void
{
    if (session_status() !== PHP_SESSION_ACTIVE) {
        session_start();
    }
    $_SESSION['pending_barcode'] = $barcode;
}

function pending_barcode_take(): string
{
    if (session_status() !== PHP_SESSION_ACTIVE) {
        session_start();
    }
    if (!empty($_SESSION['pending_barcode'])) {
        $barcode = $_SESSION['pending_barcode'];
        unset($_SESSION['pending_barcode']);
        return $barcode;
    }
    return '';
}

/** Same idea as pending_barcode_*, for a name suggested by the external lookup below. */
function pending_name_set(string $name): void
{
    if (session_status() !== PHP_SESSION_ACTIVE) {
        session_start();
    }
    $_SESSION['pending_name'] = $name;
}

function pending_name_take(): string
{
    if (session_status() !== PHP_SESSION_ACTIVE) {
        session_start();
    }
    if (!empty($_SESSION['pending_name'])) {
        $name = $_SESSION['pending_name'];
        unset($_SESSION['pending_name']);
        return $name;
    }
    return '';
}

/**
 * Set to false to disable external barcode lookups entirely — thingsFinder
 * will then only ever know a barcode if you or a scan taught it one
 * directly, and will never make an outbound network call.
 */
const EXTERNAL_BARCODE_LOOKUP_ENABLED = true;

/**
 * Minimal HTTP GET returning decoded JSON, or null on absolutely any
 * failure (network error, timeout, non-2xx, bad JSON). Tries curl first
 * (most PHP installs have it) and falls back to a stream-context
 * file_get_contents so this still works without the curl extension. Never
 * throws — callers treat null as "couldn't find out", not an error.
 */
function http_get_json(string $url, array $headers = [], float $timeout = 3.0): ?array
{
    $headers[] = 'Accept: application/json';
    $body = false;

    if (function_exists('curl_init')) {
        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT => $timeout,
            CURLOPT_CONNECTTIMEOUT => $timeout,
            CURLOPT_HTTPHEADER => $headers,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_MAXREDIRS => 3,
        ]);
        $body = curl_exec($ch);
        $status = curl_getinfo($ch, CURLINFO_HTTP_CODE);
        curl_close($ch);
        if ($body === false || $status < 200 || $status >= 300) {
            return null;
        }
    } elseif (ini_get('allow_url_fopen')) {
        $context = stream_context_create([
            'http' => [
                'method' => 'GET',
                'header' => implode("\r\n", $headers),
                'timeout' => $timeout,
                'ignore_errors' => true,
            ],
        ]);
        $body = @file_get_contents($url, false, $context);
        if ($body === false) {
            return null;
        }
        $statusLine = $http_response_header[0] ?? '';
        if (!preg_match('/\s2\d\d\s/', $statusLine)) {
            return null;
        }
    } else {
        return null;
    }

    $data = json_decode($body, true);
    return is_array($data) ? $data : null;
}

/**
 * Looks a barcode up against free, keyless public product databases when
 * it's not in our own register yet, so we can suggest a name instead of
 * leaving the field blank. Best-effort only — returns null (never throws)
 * if nothing is found, the lookup is disabled, or the network call fails;
 * callers always fall back to letting the person type the name manually.
 */
function external_barcode_lookup(string $barcode): ?array
{
    if (!EXTERNAL_BARCODE_LOOKUP_ENABLED) {
        return null;
    }
    $ua = ['User-Agent: thingsFinder/1.0 (self-hosted inventory app)'];

    // Open Food Facts — free, keyless, best for grocery/food barcodes.
    $data = http_get_json(
        'https://world.openfoodfacts.org/api/v2/product/' . rawurlencode($barcode) . '.json?fields=product_name,brands',
        $ua
    );
    if ($data && (int)($data['status'] ?? 0) === 1 && !empty($data['product']['product_name'])) {
        $name = trim($data['product']['product_name']);
        $brand = trim(explode(',', $data['product']['brands'] ?? '')[0]);
        if ($brand !== '' && stripos($name, $brand) === false) {
            $name = $brand . ' ' . $name;
        }
        return ['name' => $name, 'source' => 'Open Food Facts'];
    }

    // UPCitemdb's keyless trial lookup — broader general-retail coverage
    // (tools, electronics, household goods), but rate-limited (~100/day/IP)
    // and US-catalog-biased, so it's the fallback rather than the first try.
    $data = http_get_json(
        'https://api.upcitemdb.com/prod/trial/lookup?upc=' . rawurlencode($barcode),
        $ua
    );
    if ($data && ($data['code'] ?? '') === 'OK' && !empty($data['items'][0]['title'])) {
        return ['name' => trim($data['items'][0]['title']), 'source' => 'UPCitemdb'];
    }

    return null;
}

// -------------------------------------------------------------------------
// JSON item import: the schema, the LLM prompt, and the parser.
//
// These three live together on purpose. The schema below is the single
// source of truth: the prompt shown in the UI quotes it, /import-schema.json
// serves it verbatim, and import_parse_items() is what actually enforces it.
// Change the shape in one place and all three stay in step.
// -------------------------------------------------------------------------

/**
 * JSON Schema (draft 2020-12) for an import file, as a PHP array.
 * Served as-is by GET /import-schema.json.
 */
function import_item_schema(): array
{
    return [
        '$schema'     => 'https://json-schema.org/draft/2020-12/schema',
        '$id'         => 'https://thingsfinder.local/import-schema.json',
        'title'       => 'thingsFinder item import',
        'description' => 'A list of items to add to one box or place. Either a bare array of items, or an object with an "items" array.',
        'oneOf'       => [
            ['$ref' => '#/$defs/itemArray'],
            [
                'type'                 => 'object',
                'required'             => ['items'],
                'additionalProperties' => false,
                'properties'           => [
                    'items' => ['$ref' => '#/$defs/itemArray'],
                ],
            ],
        ],
        '$defs' => [
            'itemArray' => [
                'type'     => 'array',
                'minItems' => 0,
                'maxItems' => IMPORT_MAX_ITEMS,
                'items'    => ['$ref' => '#/$defs/item'],
            ],
            'item' => [
                'type'                 => 'object',
                'required'             => ['name'],
                'additionalProperties' => false,
                'properties'           => [
                    'name' => [
                        'type'        => 'string',
                        'minLength'   => 1,
                        'maxLength'   => IMPORT_MAX_NAME_LENGTH,
                        'description' => 'Short human-readable label for the item, e.g. "Hammer".',
                    ],
                    'quantity' => [
                        'type'        => 'integer',
                        'minimum'     => 1,
                        'maximum'     => IMPORT_MAX_QUANTITY,
                        'default'     => 1,
                        'description' => 'How many of this item. Optional; defaults to 1.',
                    ],
                ],
            ],
        ],
        'examples' => [import_example_items()],
    ];
}

/** The example payload used in the docs, the prompt, and example-import.json. */
function import_example_items(): array
{
    return [
        ['name' => 'Hammer', 'quantity' => 1],
        ['name' => 'Screwdriver set', 'quantity' => 1],
        ['name' => 'Duct tape', 'quantity' => 3],
        ['name' => '9V battery', 'quantity' => 4],
        ['name' => 'Zip ties'],
    ];
}

/** Pretty-printed JSON, the way both the schema view and the example view want it. */
function import_json_pretty($value): string
{
    return (string)json_encode($value, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
}

/**
 * The prompt a person copies into an LLM alongside a photo of their stuff.
 * Served as-is by GET /import-prompt.txt and shown on box/place pages.
 *
 * $where is an optional "Kitchen / Drawer 2" style hint so the model knows
 * the context of the photo; it's cosmetic and safe to leave empty.
 */
function import_prompt(string $where = ''): string
{
    $context = $where !== ''
        ? "\n\nContext: these things are being catalogued into \"" . $where . "\"."
        : '';

    return <<<PROMPT
Look at the attached photo and list every distinct physical item you can see.

Reply with JSON only — no prose, no explanation, no markdown code fences. The
reply must be a single JSON array where each element is an object with:

  "name"     (required)  short human-readable label, e.g. "Hammer", "9V battery".
                         Capitalised like a normal label, max 200 characters.
  "quantity" (optional)  integer >= 1, how many of that item are visible.
                         Omit it or use 1 if you can't tell.

Rules:
- Group identical items into one entry with a combined quantity instead of
  repeating the same name.
- Name what the thing is, not what it looks like. Include a brand only when
  it's clearly readable and useful ("Bosch drill", "AA batteries").
- Ignore backgrounds, furniture, surfaces, hands, shadows and packaging that
  isn't itself the item.
- Don't guess at things you can't actually make out, and don't invent items.
- If nothing recognisable is visible, reply with exactly: []

Example of a valid reply:
[
  { "name": "Hammer", "quantity": 1 },
  { "name": "9V battery", "quantity": 4 },
  { "name": "Zip ties" }
]{$context}
PROMPT;
}

/**
 * Validates decoded import JSON and returns [items, errors].
 *
 * Accepts either a bare array of items or {"items": [...]}. Each returned
 * item is ['name' => string, 'quantity' => int] and already clamped to the
 * limits the schema advertises. Bad rows are skipped with a note rather
 * than failing the whole file — an LLM getting one line wrong shouldn't
 * cost you the other forty.
 */
function import_parse_items($decoded): array
{
    $errors = [];

    if (is_array($decoded) && isset($decoded['items']) && is_array($decoded['items'])) {
        $decoded = $decoded['items'];
    }
    if (!is_array($decoded) || (!empty($decoded) && array_keys($decoded) !== range(0, count($decoded) - 1))) {
        return [[], ['Expected a JSON array of items, or an object with an "items" array.']];
    }
    if (count($decoded) > IMPORT_MAX_ITEMS) {
        return [[], [count($decoded) . ' entries is more than the ' . IMPORT_MAX_ITEMS . '-item limit for a single import.']];
    }

    $items = [];
    foreach ($decoded as $i => $row) {
        $line = 'Entry ' . ($i + 1);
        if (!is_array($row) || array_is_list($row)) {
            $errors[] = $line . ': not an object with a "name".';
            continue;
        }
        $name = is_string($row['name'] ?? null) ? trim($row['name']) : '';
        if ($name === '') {
            $errors[] = $line . ': missing a non-empty "name".';
            continue;
        }
        if (mb_strlen($name) > IMPORT_MAX_NAME_LENGTH) {
            $name = mb_substr($name, 0, IMPORT_MAX_NAME_LENGTH);
        }
        $rawQty = $row['quantity'] ?? 1;
        if (!is_int($rawQty) && !(is_string($rawQty) && ctype_digit($rawQty)) && !is_float($rawQty)) {
            $rawQty = 1;
        }
        $quantity = min(IMPORT_MAX_QUANTITY, max(1, (int)$rawQty));
        $items[] = ['name' => $name, 'quantity' => $quantity];
    }

    return [$items, $errors];
}
