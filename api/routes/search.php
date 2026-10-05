<?php
/**
 * GET /api/search?q= — item names in the active group.
 *
 * Included by api/index.php, which has already set $pdo, $method and
 * $segments, and — for anything but register/login — $userId and $groupId. Every branch ends the request (json_response/json_error exit).
 */

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
