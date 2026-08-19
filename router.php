<?php
/**
 * Router for PHP's built-in server.
 * Run with:  php -S localhost:8000 router.php
 *
 * Static files (css/js) are served as-is; everything else is dispatched
 * to the JSON API (/api/...) or the HTML UI (index.php).
 */

$uri = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
$file = __DIR__ . $uri;

// Serve real files (assets) directly.
if ($uri !== '/' && file_exists($file) && !is_dir($file)) {
    return false;
}

if (strpos($uri, '/api') === 0) {
    require __DIR__ . '/api.php';
} else {
    require __DIR__ . '/index.php';
}
