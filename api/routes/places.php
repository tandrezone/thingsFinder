<?php
/**
 * /api/places[/...] — places, the boxes in a place, and items loose in a place.
 *
 * Included by api/index.php, which has already set $pdo, $method and
 * $segments, and — for anything but register/login — $userId and $groupId. Every branch ends the request (json_response/json_error exit).
 */

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
