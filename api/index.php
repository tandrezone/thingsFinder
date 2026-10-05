<?php
/**
 * JSON REST API — front controller.
 *
 * Standalone: this folder only needs the shared core in ../includes and
 * ../vendor, so it can be served on its own — point a virtual host (e.g.
 * api.example.com) at api/, or run `php -S localhost:8001 -t api api/index.php`.
 * Paths then have no /api prefix (api.example.com/places). Mounted inside
 * the web app it answers under /api as before (web/api.php and router.php
 * forward here). Set TF_APP_URL in .env when it runs on its own host, so
 * invite links and QR-code URLs point at the web app.
 *
 * Layout: routes/<resource>.php handles each first path segment (see
 * API_ROUTES below), src/ holds the response shapes and group scoping.
 * Full reference: docs/openapi.yaml.
 *
 * Every request must be logged in (the same PHP session cookie used by the
 * regular site, or an app bearer token) and every place/box/item route is
 * scoped to the active group (see includes/auth.php) — token callers can
 * pick one with ?group_id=, otherwise their default group is used. A
 * view-only member can read everything here but any create/update/delete
 * call is rejected with 403.
 *
 * Places:
 *   GET    /api/places
 *   POST   /api/places                    { name }
 *   GET    /api/places/{id}
 *   PUT    /api/places/{id}                { name }
 *   DELETE /api/places/{id}
 *
 * Boxes:
 *   GET    /api/places/{placeId}/boxes
 *   POST   /api/places/{placeId}/boxes     { name }
 *   GET    /api/boxes/{id}
 *   PUT    /api/boxes/{id}                 { name, place_id? } — place_id moves the box (and its items) there
 *   DELETE /api/boxes/{id}
 *
 * Items (in a box, or loose in a place):
 *   GET    /api/boxes/{boxId}/items
 *   POST   /api/boxes/{boxId}/items        { name, quantity? }
 *   GET    /api/places/{placeId}/items
 *   POST   /api/places/{placeId}/items     { name, quantity? }
 *   GET    /api/items/{id}
 *   PUT    /api/items/{id}                 { name, box_id? | place_id? } — moves the item if given (box_id wins if both are)
 *   DELETE /api/items/{id}
 *
 * Box contents (an authenticated JSON view of a box's contents — the box's
 * QR code itself points to the public /view/{token} page instead, so this
 * one requires login same as everything else here):
 *   GET    /api/boxes/{boxId}/contents
 *
 * Barcode register (barcode -> item name, used when scanning a barcode
 * while adding an item) — this dictionary is shared across every account on
 * the install, same as on the /barcodes page, so it isn't group-scoped:
 *   GET    /api/barcodes
 *   POST   /api/barcodes                   { barcode, name } — create or relabel
 *   GET    /api/barcodes/{code}            404 if not registered
 *   PUT    /api/barcodes/{code}            { name }
 *   DELETE /api/barcodes/{code}
 *
 * External product-name lookup (best-effort suggestion for a barcode we
 * don't have registered ourselves yet — never writes to our own register):
 *   GET    /api/lookup/{code}              { barcode, name, source } — name/source are null if not found
 *
 * Search (within the active group only):
 *   GET    /api/search?q=glue
 *
 * Groups (see includes/groups.php) — owner-only calls answer 403 to others,
 * and a group you're not in is a 404:
 *   GET    /api/groups                     -> { groups, default_group_id }
 *   POST   /api/groups                     { name } -> 201 { group }
 *   POST   /api/groups/join                { token } | { name, key } -> { group }
 *   GET    /api/groups/{id}                -> { group, members }
 *   PUT    /api/groups/{id}                { name } (owner)
 *   DELETE /api/groups/{id}                (owner) — deletes everything in it
 *   POST   /api/groups/{id}/invite/reset   (owner) new invite link and key
 *   POST   /api/groups/{id}/leave          (not the owner)
 *   PUT    /api/groups/{id}/members/{uid}  { permission: edit|view } (owner)
 *   DELETE /api/groups/{id}/members/{uid}  (owner)
 *
 * Android app (bearer-token auth, see includes/sync.php) — every route above
 * also accepts `Authorization: Bearer <token>` instead of the session cookie:
 *   POST   /api/auth/register              { username, password, device_name? } -> 201 { token, user, group }
 *   POST   /api/auth/login                 { username, password, device_name? } -> { token, user }
 *   POST   /api/auth/logout                revokes the token used for the call
 *   GET    /api/me                         -> { user, default_group_id }
 *   POST   /api/sync                       { group_id?, since, places, boxes, items, deleted, barcodes }
 *                                          -> { server_time, group, changes, skipped }
 */

// Never let a PHP warning or notice leak into (and corrupt) a JSON response.
ini_set('display_errors', '0');

require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/db.php';
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/src/presenters.php';
require_once __DIR__ . '/src/scope.php';

$pdo = get_db();
$method = $_SERVER['REQUEST_METHOD'];
$segments = path_segments(current_path()); // e.g. ['api','places','3','boxes'] or, standalone, ['places','3','boxes']
if (($segments[0] ?? null) === 'api') {
    array_shift($segments); // mounted under /api (the default); a standalone host has no prefix
}

// First path segment -> the file in routes/ that handles it.
const API_ROUTES = [
    'auth' => 'auth.php',
    'me' => 'auth.php',
    'sync' => 'sync.php',
    'groups' => 'groups.php',
    'search' => 'search.php',
    'places' => 'places.php',
    'boxes' => 'boxes.php',
    'items' => 'items.php',
    'barcodes' => 'barcodes.php',
    'lookup' => 'barcodes.php',
];

try {
    // Register and login are the only calls that need no credentials.
    if (!in_array($segments, [['auth', 'register'], ['auth', 'login']], true)) {
        require_login_api($pdo);
        $userId = (int)current_user_id();
        $groupId = active_group_id($pdo);
    }

    $routeFile = API_ROUTES[$segments[0] ?? ''] ?? null;
    if ($routeFile !== null) {
        require __DIR__ . '/routes/' . $routeFile;
    }
    json_error('Not found', 404);
} catch (Throwable $e) {
    // Log the detail for the admin; never send SQL or file paths to the client.
    error_log('thingsFinder API error: ' . $e);
    json_error('Server error', 500);
}
