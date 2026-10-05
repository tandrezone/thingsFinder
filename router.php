<?php
/**
 * Router for PHP's built-in server (development).
 * Run with:  php -S localhost:8000 -t web router.php     (or: composer serve)
 *
 * web/ is the document root: static files there (css/js) are served as-is;
 * everything else is dispatched to the JSON API (/api/... -> web/api.php) or
 * the HTML UI (web/index.php). /docs/* is served from docs/ so the Swagger UI
 * works locally — production (Apache, see setup.sh) doesn't expose docs/.
 * The API can also run on its own: composer serve:api (see api/index.php).
 */

$uri = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
$web = __DIR__ . '/web';

// Same as web/.htaccess: no dotfiles (except /.well-known/).
if (preg_match('#(^|/)\.(?!well-known(/|$))#', $uri)) {
    http_response_code(403);
    exit('Forbidden');
}

if ($uri === '/api' || strpos($uri, '/api/') === 0) {
    require $web . '/api.php';
    return;
}

if (preg_match('#^/docs/([A-Za-z0-9._-]+)$#', $uri, $m) && is_file(__DIR__ . '/docs/' . $m[1])) {
    $types = ['html' => 'text/html', 'yaml' => 'application/yaml', 'md' => 'text/markdown'];
    $ext = pathinfo($m[1], PATHINFO_EXTENSION);
    header('Content-Type: ' . ($types[$ext] ?? 'text/plain') . '; charset=utf-8');
    readfile(__DIR__ . '/docs/' . $m[1]);
    return;
}

// Serve real files (assets) directly.
if ($uri !== '/' && is_file($web . $uri)) {
    return false;
}

require $web . '/index.php';
