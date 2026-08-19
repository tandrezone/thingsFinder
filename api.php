<?php
/**
 * JSON REST API.
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
 *   PUT    /api/boxes/{id}                 { name }
 *   DELETE /api/boxes/{id}
 *
 * Items:
 *   GET    /api/boxes/{boxId}/items
 *   POST   /api/boxes/{boxId}/items        { name }
 *   GET    /api/items/{id}
 *   PUT    /api/items/{id}                 { name }
 *   DELETE /api/items/{id}
 *
 * Box contents (what the box's QR code points to):
 *   GET    /api/boxes/{boxId}/contents
 *
 * Barcode register (barcode -> item name, used when scanning a barcode
 * while adding an item):
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
 * Search:
 *   GET    /api/search?q=glue
 */

require_once __DIR__ . '/includes/db.php';
require_once __DIR__ . '/includes/helpers.php';

$pdo = get_db();
$method = $_SERVER['REQUEST_METHOD'];
$path = current_path();
$segments = path_segments($path); // e.g. ['api','places','3','boxes']

array_shift($segments); // drop 'api'

function place_out(array $p): array
{
    return ['id' => (int)$p['id'], 'name' => $p['name'], 'slug' => $p['slug'], 'url' => '/place/' . $p['slug']];
}

function box_out(array $b, ?array $place = null): array
{
    $out = ['id' => (int)$b['id'], 'place_id' => (int)$b['place_id'], 'name' => $b['name'], 'slug' => $b['slug']];
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

try {
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
             WHERE items.name LIKE ? ESCAPE '\\'
             ORDER BY items.name COLLATE NOCASE"
        );
        $like = '%' . str_replace(['\\', '%', '_'], ['\\\\', '\\%', '\\_'], $q) . '%';
        $stmt->execute([$like]);
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
                $rows = $pdo->query('SELECT * FROM places ORDER BY name COLLATE NOCASE')->fetchAll();
                json_response(['places' => array_map('place_out', $rows)]);
            }
            if ($method === 'POST') {
                $body = read_body();
                $name = trim($body['name'] ?? '');
                if ($name === '') {
                    json_error('name is required');
                }
                $slug = unique_place_slug($pdo, $name);
                $pdo->prepare('INSERT INTO places (name, slug) VALUES (?, ?)')->execute([$name, $slug]);
                $id = (int)$pdo->lastInsertId();
                $row = $pdo->query("SELECT * FROM places WHERE id = $id")->fetch();
                json_response(['place' => place_out($row)], 201);
            }
            json_error('Method not allowed', 405);
        }

        // /api/places/{id}
        $placeId = (int)$segments[1];
        $stmt = $pdo->prepare('SELECT * FROM places WHERE id = ?');
        $stmt->execute([$placeId]);
        $place = $stmt->fetch();

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
                $body = read_body();
                $name = trim($body['name'] ?? '');
                if ($name === '') {
                    json_error('name is required');
                }
                $slug = unique_box_slug($pdo, $placeId, $name);
                $pdo->prepare('INSERT INTO boxes (place_id, name, slug) VALUES (?, ?, ?)')->execute([$placeId, $name, $slug]);
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
                $body = read_body();
                $name = trim($body['name'] ?? '');
                if ($name === '') {
                    json_error('name is required');
                }
                $slug = unique_place_slug($pdo, $name, $placeId);
                $pdo->prepare('UPDATE places SET name = ?, slug = ? WHERE id = ?')->execute([$name, $slug, $placeId]);
                $row = $pdo->query("SELECT * FROM places WHERE id = $placeId")->fetch();
                json_response(['place' => place_out($row)]);
            }
            if ($method === 'DELETE') {
                $pdo->prepare('DELETE FROM places WHERE id = ?')->execute([$placeId]);
                json_response(['deleted' => true]);
            }
            json_error('Method not allowed', 405);
        }
    }

    // ---- /api/boxes[/...] ----------------------------------------------
    if (($segments[0] ?? null) === 'boxes') {
        $boxId = (int)($segments[1] ?? 0);
        $stmt = $pdo->prepare('SELECT * FROM boxes WHERE id = ?');
        $stmt->execute([$boxId]);
        $box = $stmt->fetch();

        // /api/boxes/{id}/contents — the full contents of a box: itself, its
        // place, and all its items. This is what the box's QR code links to.
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
                $body = read_body();
                $name = trim($body['name'] ?? '');
                if ($name === '') {
                    json_error('name is required');
                }
                $slug = unique_box_slug($pdo, (int)$box['place_id'], $name, $boxId);
                $pdo->prepare('UPDATE boxes SET name = ?, slug = ? WHERE id = ?')->execute([$name, $slug, $boxId]);
                $row = $pdo->query("SELECT * FROM boxes WHERE id = $boxId")->fetch();
                json_response(['box' => box_out($row)]);
            }
            if ($method === 'DELETE') {
                $pdo->prepare('DELETE FROM boxes WHERE id = ?')->execute([$boxId]);
                json_response(['deleted' => true]);
            }
            json_error('Method not allowed', 405);
        }
    }

    // ---- /api/items/{id} -----------------------------------------------
    if (($segments[0] ?? null) === 'items' && count($segments) === 2) {
        $itemId = (int)$segments[1];
        $stmt = $pdo->prepare('SELECT * FROM items WHERE id = ?');
        $stmt->execute([$itemId]);
        $item = $stmt->fetch();
        if (!$item) {
            json_error('Item not found', 404);
        }
        if ($method === 'GET') {
            json_response(['item' => item_out($item)]);
        }
        if ($method === 'PUT' || $method === 'PATCH') {
            $body = read_body();
            $name = trim($body['name'] ?? '');
            if ($name === '') {
                json_error('name is required');
            }
            $quantity = isset($body['quantity']) ? max(1, (int)$body['quantity']) : (int)$item['quantity'];
            $pdo->prepare('UPDATE items SET name = ?, quantity = ? WHERE id = ?')->execute([$name, $quantity, $itemId]);
            $row = $pdo->query("SELECT * FROM items WHERE id = $itemId")->fetch();
            json_response(['item' => item_out($row)]);
        }
        if ($method === 'DELETE') {
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
    if (($segments[0] ?? null) === 'barcodes') {
        // /api/barcodes
        if (count($segments) === 1) {
            if ($method === 'GET') {
                $rows = $pdo->query('SELECT * FROM barcode_items ORDER BY name COLLATE NOCASE')->fetchAll();
                json_response(['barcodes' => array_map('barcode_out', $rows)]);
            }
            if ($method === 'POST') {
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
            $pdo->prepare('DELETE FROM barcode_items WHERE barcode = ?')->execute([$code]);
            json_response(['deleted' => true]);
        }
        json_error('Method not allowed', 405);
    }

    json_error('Not found', 404);
} catch (Throwable $e) {
    json_error('Server error: ' . $e->getMessage(), 500);
}
