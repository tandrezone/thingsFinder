<?php
/** Small shared helpers used by both the UI (index.php) and the API (api.php). */

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
 * or build step.
 */
function icon(string $name, int $size = 16): string
{
    $paths = [
        'search'   => '<circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>',
        'plus'     => '<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>',
        'trash'    => '<polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6"/><path d="M14 11v6"/><path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>',
        'edit'     => '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/>',
        'external' => '<path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/>',
        'download' => '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>',
        'dots'     => '<circle cx="12" cy="5" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="12" cy="19" r="1.6"/>',
        'chevron'  => '<polyline points="9 6 15 12 9 18"/>',
        'check'    => '<polyline points="20 6 9 17 4 12"/>',
        'alert'    => '<circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>',
        'inbox'    => '<polyline points="22 12 16 12 14 15 10 15 8 12 2 12"/><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/>',
        'box'      => '<path d="M21 8v13H3V8"/><path d="M1 3h22v5H1z"/><line x1="10" y1="12" x2="14" y2="12"/>',
        'qr'       => '<rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/><line x1="14" y1="14" x2="14" y2="21"/><line x1="21" y1="14" x2="21" y2="14.01"/><line x1="14" y1="17.5" x2="17.5" y2="17.5"/><line x1="21" y1="21" x2="17.5" y2="21"/><line x1="17.5" y1="17.5" x2="17.5" y2="21"/>',
        'barcode'  => '<line x1="3" y1="5" x2="3" y2="19"/><line x1="7" y1="5" x2="7" y2="19"/><line x1="10" y1="5" x2="10" y2="19"/><line x1="14" y1="5" x2="14" y2="19"/><line x1="18" y1="5" x2="18" y2="19"/><line x1="21" y1="5" x2="21" y2="19"/>',
        'camera'   => '<path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/>',
    ];
    $body = $paths[$name] ?? $paths['dots'];
    return '<svg class="icon" width="' . $size . '" height="' . $size . '" viewBox="0 0 24 24" '
        . 'fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" '
        . 'stroke-linejoin="round" aria-hidden="true" focusable="false">' . $body . '</svg>';
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
