<?php
/**
 * JSON REST API.
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
 * Items:
 *   GET    /api/boxes/{boxId}/items
 *   POST   /api/boxes/{boxId}/items        { name }
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

require_once __DIR__ . '/includes/db.php';
require_once __DIR__ . '/includes/helpers.php';
require_once __DIR__ . '/includes/auth.php';

$pdo = get_db();
$method = $_SERVER['REQUEST_METHOD'];
$path = current_path();
$segments = path_segments($path); // e.g. ['api','places','3','boxes']

array_shift($segments); // drop 'api'

/** A group as the app sees it; the invite is only shown to members who can edit. */
function group_out(array $g): array
{
    return [
        'id' => (int)$g['id'],
        'name' => $g['name'],
        'role' => $g['role'],
        'permission' => $g['permission'],
        'member_count' => (int)$g['member_count'],
        'invite' => $g['permission'] === 'edit' ? [
            'url' => group_invite_url($g),
            'token' => $g['invite_token'],
            'key' => format_join_key($g['join_key']),
        ] : null,
    ];
}

/** /api/groups/... — $rest is the path after "groups". Always ends the request. */
function api_groups(PDO $pdo, string $method, array $rest, int $userId): void
{
    // /api/groups
    if ($rest === []) {
        if ($method === 'GET') {
            json_response([
                'groups' => array_map('group_out', list_user_groups($pdo, $userId)),
                'default_group_id' => default_group_id($pdo, $userId),
            ]);
        }
        if ($method === 'POST') {
            $name = clean_group_name(read_body()['name'] ?? null);
            if ($name === '') {
                json_error('name is required');
            }
            $id = create_group($pdo, $userId, $name);
            json_response(['group' => group_out(find_membership($pdo, $id, $userId))], 201);
        }
        json_error('Method not allowed', 405);
    }

    // /api/groups/join — by invite token (link / QR code), or by name + key
    if ($rest === ['join']) {
        if ($method !== 'POST') {
            json_error('Method not allowed', 405);
        }
        $body = read_body();
        $token = is_string($body['token'] ?? null) ? trim($body['token']) : '';
        $group = $token !== ''
            ? find_group_by_invite_token($pdo, $token)
            : find_group_by_name_and_key($pdo, (string)($body['name'] ?? ''), (string)($body['key'] ?? ''));
        if (!$group) {
            usleep(300000); // keys are short — slow down guessing a little
            json_error('Invite not found — check the link, or the group name and key', 404);
        }
        add_group_member($pdo, (int)$group['id'], $userId);
        json_response(['group' => group_out(find_membership($pdo, (int)$group['id'], $userId))]);
    }

    $groupId = (int)$rest[0];
    $membership = find_membership($pdo, $groupId, $userId);
    if (!$membership) {
        json_error('Group not found', 404);
    }
    $isOwner = $membership['role'] === 'owner';
    $requireOwner = function () use ($isOwner) {
        if (!$isOwner) {
            json_error('Only the group\'s owner can do that', 403);
        }
    };

    // /api/groups/{id}
    if (count($rest) === 1) {
        if ($method === 'GET') {
            $members = array_map(fn($m) => [
                'id' => (int)$m['id'], 'username' => $m['username'], 'role' => $m['role'], 'permission' => $m['permission'],
            ], list_group_members($pdo, $groupId));
            json_response(['group' => group_out($membership), 'members' => $members]);
        }
        if ($method === 'PUT' || $method === 'PATCH') {
            $requireOwner();
            $name = clean_group_name(read_body()['name'] ?? null);
            if ($name === '') {
                json_error('name is required');
            }
            rename_group($pdo, $groupId, $name);
            json_response(['group' => group_out(find_membership($pdo, $groupId, $userId))]);
        }
        if ($method === 'DELETE') {
            $requireOwner();
            delete_group($pdo, $groupId);
            json_response(['deleted' => true]);
        }
        json_error('Method not allowed', 405);
    }

    // /api/groups/{id}/invite/reset
    if (array_slice($rest, 1) === ['invite', 'reset'] && $method === 'POST') {
        $requireOwner();
        reset_group_invite($pdo, $groupId);
        json_response(['group' => group_out(find_membership($pdo, $groupId, $userId))]);
    }

    // /api/groups/{id}/leave
    if (array_slice($rest, 1) === ['leave'] && $method === 'POST') {
        if ($isOwner) {
            json_error('You own this group — delete it instead of leaving', 400);
        }
        remove_group_member($pdo, $groupId, $userId);
        json_response(['left' => true]);
    }

    // /api/groups/{id}/members/{userId}
    if (count($rest) === 3 && $rest[1] === 'members') {
        $requireOwner();
        $memberId = (int)$rest[2];
        if ($memberId === $userId) {
            json_error('That\'s you — the owner stays in the group', 400);
        }
        if ($method === 'DELETE') {
            remove_group_member($pdo, $groupId, $memberId);
            json_response(['removed' => true]);
        }
        if ($method === 'PUT' || $method === 'PATCH') {
            set_member_permission($pdo, $groupId, $memberId, (string)(read_body()['permission'] ?? 'edit'));
            json_response(['updated' => true]);
        }
        json_error('Method not allowed', 405);
    }

    json_error('Not found', 404);
}

// ---- /api/auth/register — creates an account (and its personal group) ------
if ($segments === ['auth', 'register']) {
    if ($method !== 'POST') {
        json_error('Method not allowed', 405);
    }
    if (!registration_enabled()) {
        json_error('Registration is turned off on this server — ask its admin for an account', 403);
    }
    $body = read_body();
    $username = is_string($body['username'] ?? null) ? trim($body['username']) : '';
    $password = is_string($body['password'] ?? null) ? $body['password'] : '';
    $problem = validate_new_account($pdo, $username, $password);
    if ($problem !== null) {
        json_error($problem, $problem === USERNAME_TAKEN ? 409 : 400);
    }
    [$newUserId, $newGroupId] = register_user($pdo, $username, $password);
    $device = is_string($body['device_name'] ?? null) ? trim($body['device_name']) : 'Android';
    $token = create_api_token($pdo, $newUserId, $device !== '' ? $device : 'Android');
    json_response([
        'token' => $token,
        'user' => ['id' => $newUserId, 'username' => $username],
        'group' => group_out(find_membership($pdo, $newGroupId, $newUserId)),
    ], 201);
}

// ---- /api/auth/login — needs no credentials either -------------------------
if ($segments === ['auth', 'login']) {
    if ($method !== 'POST') {
        json_error('Method not allowed', 405);
    }
    $body = read_body();
    $username = is_string($body['username'] ?? null) ? trim($body['username']) : '';
    $password = is_string($body['password'] ?? null) ? $body['password'] : '';
    if ($username === '' || $password === '') {
        json_error('username and password are required', 400);
    }
    $user = find_user_by_username($pdo, $username);
    if (!$user || !password_verify($password, $user['password_hash'])) {
        usleep(400000); // slow down password guessing a little
        json_error('Wrong username or password', 401);
    }
    $device = is_string($body['device_name'] ?? null) ? trim($body['device_name']) : 'Android';
    $token = create_api_token($pdo, (int)$user['id'], $device !== '' ? $device : 'Android');
    json_response(['token' => $token, 'user' => ['id' => (int)$user['id'], 'username' => $user['username']]], 201);
}

require_login_api($pdo);
$userId = (int)current_user_id();
$groupId = active_group_id($pdo);

/** Call before any create/update/delete — view-only shares get a 403. */
function require_write(PDO $pdo): void
{
    require_edit_api($pdo);
}

function place_out(array $p): array
{
    return [
        'id' => (int)$p['id'], 'name' => $p['name'], 'slug' => $p['slug'], 'url' => '/place/' . $p['slug'],
        'add_url' => '/add/' . $p['share_token'], 'remove_url' => '/remove/' . $p['share_token'],
    ];
}

function box_out(array $b, ?array $place = null): array
{
    $out = [
        'id' => (int)$b['id'],
        'place_id' => (int)$b['place_id'],
        'name' => $b['name'],
        'slug' => $b['slug'],
        'view_url' => isset($b['share_token']) ? '/view/' . $b['share_token'] : null,
        'add_url' => isset($b['share_token']) ? '/add/' . $b['share_token'] : null,
        'remove_url' => isset($b['share_token']) ? '/remove/' . $b['share_token'] : null,
    ];
    if ($place) {
        $out['url'] = '/place/' . $place['slug'] . '/' . $b['slug'];
        $out['place'] = ['id' => (int)$place['id'], 'name' => $place['name'], 'slug' => $place['slug']];
    }
    return $out;
}

function item_out(array $i): array
{
    return [
        'id' => (int)$i['id'],
        'box_id' => $i['box_id'] !== null ? (int)$i['box_id'] : null,
        'place_id' => $i['place_id'] !== null ? (int)$i['place_id'] : null,
        'name' => $i['name'],
        'quantity' => (int)($i['quantity'] ?? 1),
    ];
}

function barcode_out(array $b): array
{
    return ['barcode' => $b['barcode'], 'name' => $b['name']];
}

/** Looks up a place, scoped to the given group — returns null if it's in another group (or doesn't exist). */
function api_find_place(PDO $pdo, int $placeId, int $groupId): ?array
{
    $stmt = $pdo->prepare('SELECT * FROM places WHERE id = ? AND group_id = ?');
    $stmt->execute([$placeId, $groupId]);
    $row = $stmt->fetch();
    return $row ?: null;
}

/** Looks up a box, scoped to the given group via its place — returns null if out of scope or missing. */
function api_find_box(PDO $pdo, int $boxId, int $groupId): ?array
{
    $stmt = $pdo->prepare(
        'SELECT boxes.* FROM boxes JOIN places ON places.id = boxes.place_id
         WHERE boxes.id = ? AND places.group_id = ?'
    );
    $stmt->execute([$boxId, $groupId]);
    $row = $stmt->fetch();
    return $row ?: null;
}

/** Looks up an item, scoped to the given group via its box's place or its own place — returns null if out of scope or missing. */
function api_find_item(PDO $pdo, int $itemId, int $groupId): ?array
{
    $stmt = $pdo->prepare(
        'SELECT items.* FROM items
         LEFT JOIN boxes ON boxes.id = items.box_id
         JOIN places ON places.id = COALESCE(items.place_id, boxes.place_id)
         WHERE items.id = ? AND places.group_id = ?'
    );
    $stmt->execute([$itemId, $groupId]);
    $row = $stmt->fetch();
    return $row ?: null;
}

try {
    // ---- /api/auth/logout, /api/me, /api/sync (Android app) -------------
    if ($segments === ['auth', 'logout']) {
        if ($method !== 'POST') {
            json_error('Method not allowed', 405);
        }
        $token = request_bearer_token();
        if ($token !== null) {
            delete_api_token($pdo, $token);
        }
        json_response(['logged_out' => true]);
    }
    if ($segments === ['me']) {
        $me = find_user_by_id($pdo, $userId);
        json_response([
            'user' => ['id' => (int)$me['id'], 'username' => $me['username']],
            'default_group_id' => default_group_id($pdo, $userId),
        ]);
    }
    if ($segments === ['sync']) {
        if ($method !== 'POST') {
            json_error('Method not allowed', 405);
        }
        // One group per sync: the one the phone names, or the user's default.
        $body = read_body();
        $syncGroupId = isset($body['group_id']) && is_numeric($body['group_id']) ? (int)$body['group_id'] : default_group_id($pdo, $userId);
        $membership = find_membership($pdo, $syncGroupId, $userId);
        if (!$membership) {
            json_error('You are not a member of that group', 403);
        }
        $result = sync_apply($pdo, $syncGroupId, $body, $membership['permission'] === 'edit');
        $result['group'] = [
            'id' => (int)$membership['id'], 'name' => $membership['name'],
            'permission' => $membership['permission'], 'role' => $membership['role'],
        ];
        json_response($result);
    }

    // ---- /api/groups[/...] ----------------------------------------------
    if (($segments[0] ?? null) === 'groups') {
        api_groups($pdo, $method, array_slice($segments, 1), $userId);
    }

    // ---- /api/search --------------------------------------------------
    if ($segments === ['search']) {
        if ($method !== 'GET') {
            json_error('Method not allowed', 405);
        }
        $q = trim($_GET['q'] ?? '');
        if ($q === '') {
            json_response(['query' => $q, 'results' => []]);
        }
        $stmt = $pdo->prepare(
            "SELECT items.id, items.name AS item_name, items.quantity,
                    boxes.id AS box_id, boxes.name AS box_name, boxes.slug AS box_slug,
                    places.id AS place_id, places.name AS place_name, places.slug AS place_slug
             FROM items
             LEFT JOIN boxes ON boxes.id = items.box_id
             JOIN places ON places.id = COALESCE(items.place_id, boxes.place_id)
             WHERE places.group_id = ? AND items.name LIKE ? ESCAPE '\\'
             ORDER BY items.name COLLATE NOCASE"
        );
        $like = '%' . str_replace(['\\', '%', '_'], ['\\\\', '\\%', '\\_'], $q) . '%';
        $stmt->execute([$groupId, $like]);
        $results = [];
        foreach ($stmt->fetchAll() as $row) {
            $box = $row['box_id'] !== null
                ? ['id' => (int)$row['box_id'], 'name' => $row['box_name'], 'slug' => $row['box_slug']]
                : null;
            $results[] = [
                'item' => ['id' => (int)$row['id'], 'name' => $row['item_name'], 'quantity' => (int)$row['quantity']],
                'box' => $box,
                'place' => ['id' => (int)$row['place_id'], 'name' => $row['place_name'], 'slug' => $row['place_slug']],
                'url' => '/place/' . $row['place_slug'] . ($box ? '/' . $row['box_slug'] : ''),
            ];
        }
        json_response(['query' => $q, 'results' => $results]);
    }

    // ---- /api/places[/...] --------------------------------------------
    if (($segments[0] ?? null) === 'places') {
        // /api/places
        if (count($segments) === 1) {
            if ($method === 'GET') {
                $stmt = $pdo->prepare('SELECT * FROM places WHERE group_id = ? ORDER BY name COLLATE NOCASE');
                $stmt->execute([$groupId]);
                json_response(['places' => array_map('place_out', $stmt->fetchAll())]);
            }
            if ($method === 'POST') {
                require_write($pdo);
                $body = read_body();
                $name = trim($body['name'] ?? '');
                if ($name === '') {
                    json_error('name is required');
                }
                $id = create_place($pdo, $groupId, $name);
                $row = $pdo->query("SELECT * FROM places WHERE id = $id")->fetch();
                json_response(['place' => place_out($row)], 201);
            }
            json_error('Method not allowed', 405);
        }

        // /api/places/{id}
        $placeId = (int)$segments[1];
        $place = api_find_place($pdo, $placeId, $groupId);

        // /api/places/{id}/boxes
        if (count($segments) === 3 && $segments[2] === 'boxes') {
            if (!$place) {
                json_error('Place not found', 404);
            }
            if ($method === 'GET') {
                $stmt = $pdo->prepare('SELECT * FROM boxes WHERE place_id = ? ORDER BY name COLLATE NOCASE');
                $stmt->execute([$placeId]);
                json_response(['boxes' => array_map(fn($b) => box_out($b, $place), $stmt->fetchAll())]);
            }
            if ($method === 'POST') {
                require_write($pdo);
                $body = read_body();
                $name = trim($body['name'] ?? '');
                if ($name === '') {
                    json_error('name is required');
                }
                $slug = unique_box_slug($pdo, $placeId, $name);
                $pdo->prepare('INSERT INTO boxes (place_id, name, slug, share_token) VALUES (?, ?, ?, ?)')
                    ->execute([$placeId, $name, $slug, new_share_token()]);
                $id = (int)$pdo->lastInsertId();
                $row = $pdo->query("SELECT * FROM boxes WHERE id = $id")->fetch();
                json_response(['box' => box_out($row, $place)], 201);
            }
            json_error('Method not allowed', 405);
        }

        // /api/places/{id}/items — items directly in a place (not in a box).
        if (count($segments) === 3 && $segments[2] === 'items') {
            if (!$place) {
                json_error('Place not found', 404);
            }
            if ($method === 'GET') {
                $stmt = $pdo->prepare('SELECT * FROM items WHERE place_id = ? ORDER BY name COLLATE NOCASE');
                $stmt->execute([$placeId]);
                json_response(['items' => array_map('item_out', $stmt->fetchAll())]);
            }
            if ($method === 'POST') {
                require_write($pdo);
                $body = read_body();
                $name = trim($body['name'] ?? '');
                if ($name === '') {
                    json_error('name is required');
                }
                $quantity = max(1, (int)($body['quantity'] ?? 1));
                $pdo->prepare('INSERT INTO items (place_id, name, quantity) VALUES (?, ?, ?)')->execute([$placeId, $name, $quantity]);
                $id = (int)$pdo->lastInsertId();
                $row = $pdo->query("SELECT * FROM items WHERE id = $id")->fetch();
                json_response(['item' => item_out($row)], 201);
            }
            json_error('Method not allowed', 405);
        }

        // /api/places/{id}
        if (count($segments) === 2) {
            if (!$place) {
                json_error('Place not found', 404);
            }
            if ($method === 'GET') {
                json_response(['place' => place_out($place)]);
            }
            if ($method === 'PUT' || $method === 'PATCH') {
                require_write($pdo);
                $body = read_body();
                $name = trim($body['name'] ?? '');
                if ($name === '') {
                    json_error('name is required');
                }
                $slug = unique_place_slug($pdo, $groupId, $name, $placeId);
                $pdo->prepare('UPDATE places SET name = ?, slug = ? WHERE id = ?')->execute([$name, $slug, $placeId]);
                $row = $pdo->query("SELECT * FROM places WHERE id = $placeId")->fetch();
                json_response(['place' => place_out($row)]);
            }
            if ($method === 'DELETE') {
                require_write($pdo);
                $pdo->prepare('DELETE FROM places WHERE id = ?')->execute([$placeId]);
                json_response(['deleted' => true]);
            }
            json_error('Method not allowed', 405);
        }
    }

    // ---- /api/boxes[/...] ----------------------------------------------
    if (($segments[0] ?? null) === 'boxes') {
        $boxId = (int)($segments[1] ?? 0);
        $box = api_find_box($pdo, $boxId, $groupId);

        // /api/boxes/{id}/contents — the full contents of a box: itself, its
        // place, and all its items. (The box's own QR code points to the
        // public /view/{token} page instead — this JSON endpoint is for a
        // logged-in look at the same data.)
        if (count($segments) === 3 && $segments[2] === 'contents') {
            if (!$box) {
                json_error('Box not found', 404);
            }
            if ($method !== 'GET') {
                json_error('Method not allowed', 405);
            }
            $placeStmt = $pdo->prepare('SELECT * FROM places WHERE id = ?');
            $placeStmt->execute([(int)$box['place_id']]);
            $place = $placeStmt->fetch();

            $itemsStmt = $pdo->prepare('SELECT * FROM items WHERE box_id = ? ORDER BY name COLLATE NOCASE');
            $itemsStmt->execute([$boxId]);

            json_response([
                'box' => box_out($box, $place),
                'items' => array_map('item_out', $itemsStmt->fetchAll()),
            ]);
        }

        // /api/boxes/{id}/items
        if (count($segments) === 3 && $segments[2] === 'items') {
            if (!$box) {
                json_error('Box not found', 404);
            }
            if ($method === 'GET') {
                $stmt = $pdo->prepare('SELECT * FROM items WHERE box_id = ? ORDER BY name COLLATE NOCASE');
                $stmt->execute([$boxId]);
                json_response(['items' => array_map('item_out', $stmt->fetchAll())]);
            }
            if ($method === 'POST') {
                require_write($pdo);
                $body = read_body();
                $name = trim($body['name'] ?? '');
                if ($name === '') {
                    json_error('name is required');
                }
                $quantity = max(1, (int)($body['quantity'] ?? 1));
                $pdo->prepare('INSERT INTO items (box_id, name, quantity) VALUES (?, ?, ?)')->execute([$boxId, $name, $quantity]);
                $id = (int)$pdo->lastInsertId();
                $row = $pdo->query("SELECT * FROM items WHERE id = $id")->fetch();
                json_response(['item' => item_out($row)], 201);
            }
            json_error('Method not allowed', 405);
        }

        // /api/boxes/{id}
        if (count($segments) === 2) {
            if (!$box) {
                json_error('Box not found', 404);
            }
            if ($method === 'GET') {
                json_response(['box' => box_out($box)]);
            }
            if ($method === 'PUT' || $method === 'PATCH') {
                require_write($pdo);
                $body = read_body();
                $name = trim($body['name'] ?? '');
                if ($name === '') {
                    json_error('name is required');
                }
                $slug = unique_box_slug($pdo, (int)$box['place_id'], $name, $boxId);
                $pdo->prepare('UPDATE boxes SET name = ?, slug = ? WHERE id = ?')->execute([$name, $slug, $boxId]);
                if (isset($body['place_id']) && (int)$body['place_id'] !== (int)$box['place_id']) {
                    if (move_box_to($pdo, $boxId, $groupId, (int)$body['place_id']) === null) {
                        json_error('place_id must be a place in this group', 422);
                    }
                }
                $row = $pdo->query("SELECT * FROM boxes WHERE id = $boxId")->fetch();
                json_response(['box' => box_out($row)]);
            }
            if ($method === 'DELETE') {
                require_write($pdo);
                $pdo->prepare('DELETE FROM boxes WHERE id = ?')->execute([$boxId]);
                json_response(['deleted' => true]);
            }
            json_error('Method not allowed', 405);
        }
    }

    // ---- /api/items/{id} -----------------------------------------------
    if (($segments[0] ?? null) === 'items' && count($segments) === 2) {
        $itemId = (int)$segments[1];
        $item = api_find_item($pdo, $itemId, $groupId);
        if (!$item) {
            json_error('Item not found', 404);
        }
        if ($method === 'GET') {
            json_response(['item' => item_out($item)]);
        }
        if ($method === 'PUT' || $method === 'PATCH') {
            require_write($pdo);
            $body = read_body();
            $name = trim($body['name'] ?? '');
            if ($name === '') {
                json_error('name is required');
            }
            $quantity = isset($body['quantity']) ? max(1, (int)$body['quantity']) : (int)$item['quantity'];
            $pdo->prepare('UPDATE items SET name = ?, quantity = ? WHERE id = ?')->execute([$name, $quantity, $itemId]);
            if (!empty($body['box_id'])) {
                if (!move_item_to($pdo, $itemId, $groupId, 'box:' . (int)$body['box_id'])) {
                    json_error('box_id must be a box in this group', 422);
                }
            } elseif (!empty($body['place_id'])) {
                if (!move_item_to($pdo, $itemId, $groupId, 'place:' . (int)$body['place_id'])) {
                    json_error('place_id must be a place in this group', 422);
                }
            }
            $row = $pdo->query("SELECT * FROM items WHERE id = $itemId")->fetch();
            json_response(['item' => item_out($row)]);
        }
        if ($method === 'DELETE') {
            require_write($pdo);
            $pdo->prepare('DELETE FROM items WHERE id = ?')->execute([$itemId]);
            json_response(['deleted' => true]);
        }
        json_error('Method not allowed', 405);
    }

    // ---- /api/lookup/{code} --------------------------------------------
    if (($segments[0] ?? null) === 'lookup' && count($segments) === 2) {
        if ($method !== 'GET') {
            json_error('Method not allowed', 405);
        }
        $code = (string)$segments[1];
        $found = external_barcode_lookup($code);
        json_response([
            'barcode' => $code,
            'name' => $found['name'] ?? null,
            'source' => $found['source'] ?? null,
        ]);
    }

    // ---- /api/barcodes[/...] -------------------------------------------
    // This register is shared by every account on the install (same as the
    // /barcodes page) — it isn't scoped to $groupId, just to being logged in.
    if (($segments[0] ?? null) === 'barcodes') {
        // /api/barcodes
        if (count($segments) === 1) {
            if ($method === 'GET') {
                $rows = $pdo->query('SELECT * FROM barcode_items ORDER BY name COLLATE NOCASE')->fetchAll();
                json_response(['barcodes' => array_map('barcode_out', $rows)]);
            }
            if ($method === 'POST') {
                require_write($pdo);
                $body = read_body();
                $code = trim($body['barcode'] ?? '');
                $name = trim($body['name'] ?? '');
                if ($code === '' || $name === '') {
                    json_error('barcode and name are required');
                }
                remember_barcode($pdo, $code, $name);
                $row = find_barcode($pdo, $code);
                json_response(['barcode' => barcode_out($row)], 201);
            }
            json_error('Method not allowed', 405);
        }

        // /api/barcodes/{code}
        $code = (string)$segments[1];
        $row = find_barcode($pdo, $code);

        if ($method === 'GET') {
            if (!$row) {
                json_error('Barcode not registered', 404);
            }
            json_response(['barcode' => barcode_out($row)]);
        }
        if ($method === 'PUT' || $method === 'PATCH') {
            require_write($pdo);
            if (!$row) {
                json_error('Barcode not registered', 404);
            }
            $body = read_body();
            $name = trim($body['name'] ?? '');
            if ($name === '') {
                json_error('name is required');
            }
            $pdo->prepare('UPDATE barcode_items SET name = ? WHERE barcode = ?')->execute([$name, $code]);
            json_response(['barcode' => barcode_out(find_barcode($pdo, $code))]);
        }
        if ($method === 'DELETE') {
            require_write($pdo);
            $pdo->prepare('DELETE FROM barcode_items WHERE barcode = ?')->execute([$code]);
            json_response(['deleted' => true]);
        }
        json_error('Method not allowed', 405);
    }

    json_error('Not found', 404);
} catch (Throwable $e) {
    // Log the detail for the admin; never send SQL or file paths to the client.
    error_log('thingsFinder API error: ' . $e);
    json_error('Server error', 500);
}
