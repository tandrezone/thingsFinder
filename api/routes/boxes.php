<?php
/**
 * /api/boxes[/...] — a box, its items and its contents.
 *
 * Included by api/index.php, which has already set $pdo, $method and
 * $segments, and — for anything but register/login — $userId and $groupId. Every branch ends the request (json_response/json_error exit).
 */

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
