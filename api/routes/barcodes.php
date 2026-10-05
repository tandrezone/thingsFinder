<?php
/**
 * /api/barcodes[/...] and /api/lookup/{code} — the shared barcode register and the external name lookup.
 *
 * Included by api/index.php, which has already set $pdo, $method and
 * $segments, and — for anything but register/login — $userId and $groupId. Every branch ends the request (json_response/json_error exit).
 */

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
