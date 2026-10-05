<?php
/**
 * /api/items/{id} — a single item.
 *
 * Included by api/index.php, which has already set $pdo, $method and
 * $segments, and — for anything but register/login — $userId and $groupId. Every branch ends the request (json_response/json_error exit).
 */

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
