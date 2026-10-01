<?php
/**
 * Phone ↔ server sync for the Android app.
 *
 * Adds three things on top of the existing schema, all created lazily and
 * idempotently from init_schema():
 *
 *   - api_tokens: bearer tokens for the app (only a SHA-256 hash is stored).
 *   - uuid + updated_at columns on places/boxes/items. The phone identifies
 *     rows by uuid (its own ids are local), and updated_at (epoch ms) drives
 *     last-write-wins. SQLite triggers keep both columns filled for rows the
 *     web UI creates or edits, so the web app's own queries don't change.
 *   - sync_tombstones: a trigger records every deleted row's uuid, so a
 *     delete made on the web also disappears from the phone.
 *
 * The one endpoint, POST /api/sync, takes the phone's changes since its
 * last sync, applies them (last write wins, per row), and answers with
 * everything that changed on the server since the phone's cursor. A phone
 * syncs one group at a time (group_id in the request, or the user's
 * default group).
 */

const API_TOKEN_TTL_DAYS = 365;
const SYNC_MAX_ROWS = 20000;
const SYNC_NOW_MS_SQL = "CAST(ROUND((julianday('now') - 2440587.5) * 86400000) AS INTEGER)";

function now_ms(): int
{
    return (int)round(microtime(true) * 1000);
}

function init_sync_schema(PDO $pdo): void
{
    $pdo->exec("CREATE TABLE IF NOT EXISTS api_tokens (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        token_hash TEXT NOT NULL UNIQUE,
        device_name TEXT,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        last_used_at TEXT,
        expires_at TEXT NOT NULL
    )");
    $pdo->exec('CREATE INDEX IF NOT EXISTS idx_api_tokens_user ON api_tokens(user_id)');

    $pdo->exec("CREATE TABLE IF NOT EXISTS sync_tombstones (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        kind TEXT NOT NULL,
        uuid TEXT NOT NULL,
        deleted_at INTEGER NOT NULL
    )");
    $pdo->exec('CREATE INDEX IF NOT EXISTS idx_tombstones_deleted ON sync_tombstones(deleted_at)');

    foreach (['places', 'boxes', 'items'] as $table) {
        $cols = array_column($pdo->query("PRAGMA table_info($table)")->fetchAll(), 'name');
        if (!in_array('uuid', $cols, true)) {
            $pdo->exec("ALTER TABLE $table ADD COLUMN uuid TEXT");
        }
        if (!in_array('updated_at', $cols, true)) {
            $pdo->exec("ALTER TABLE $table ADD COLUMN updated_at INTEGER");
        }
        if (!in_array('changed_at', $cols, true)) {
            $pdo->exec("ALTER TABLE $table ADD COLUMN changed_at INTEGER");
        }
        // Backfill rows that predate sync (a no-op after the first run).
        $pdo->exec("UPDATE $table SET uuid = lower(hex(randomblob(16))) WHERE uuid IS NULL");
        $pdo->exec("UPDATE $table SET updated_at = " . SYNC_NOW_MS_SQL . " WHERE updated_at IS NULL");
        $pdo->exec("UPDATE $table SET changed_at = updated_at WHERE changed_at IS NULL");
        $pdo->exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_{$table}_uuid ON $table(uuid)");
        $pdo->exec("CREATE INDEX IF NOT EXISTS idx_{$table}_changed ON $table(changed_at)");

        // updated_at: when the thing was last edited (client or server clock) — decides conflicts.
        // changed_at: when *this server* last wrote the row — the sync cursor. Kept apart so a
        // phone with an older timestamp still reaches every other device.
        $pdo->exec("CREATE TRIGGER IF NOT EXISTS tf_{$table}_ai AFTER INSERT ON $table
            BEGIN
                UPDATE $table SET uuid = COALESCE(NEW.uuid, lower(hex(randomblob(16)))),
                                  updated_at = COALESCE(NEW.updated_at, " . SYNC_NOW_MS_SQL . "),
                                  changed_at = " . SYNC_NOW_MS_SQL . "
                WHERE id = NEW.id;
            END");
        // Every update stamps changed_at; one that doesn't set updated_at itself
        // (i.e. every web UI edit) also bumps updated_at.
        $pdo->exec("CREATE TRIGGER IF NOT EXISTS tf_{$table}_au AFTER UPDATE ON $table
            WHEN NEW.changed_at IS OLD.changed_at
            BEGIN
                UPDATE $table SET changed_at = " . SYNC_NOW_MS_SQL . ",
                                  updated_at = CASE WHEN NEW.updated_at IS OLD.updated_at
                                                    THEN " . SYNC_NOW_MS_SQL . " ELSE NEW.updated_at END
                WHERE id = NEW.id;
            END");
        // Deletes (including ON DELETE CASCADE children) leave a tombstone.
        $pdo->exec("CREATE TRIGGER IF NOT EXISTS tf_{$table}_ad AFTER DELETE ON $table
            WHEN OLD.uuid IS NOT NULL
            BEGIN
                INSERT INTO sync_tombstones (kind, uuid, deleted_at) VALUES ('$table', OLD.uuid, " . SYNC_NOW_MS_SQL . ");
            END");
    }
}

// -------------------------------------------------------------------------
// Tokens
// -------------------------------------------------------------------------

/** Creates a token for $userId and returns the plain value (shown once; only its hash is stored). */
function create_api_token(PDO $pdo, int $userId, string $deviceName): string
{
    $token = bin2hex(random_bytes(32));
    $pdo->prepare(
        "INSERT INTO api_tokens (user_id, token_hash, device_name, expires_at)
         VALUES (?, ?, ?, datetime('now', ?))"
    )->execute([$userId, hash('sha256', $token), mb_substr($deviceName, 0, 100), '+' . API_TOKEN_TTL_DAYS . ' days']);
    return $token;
}

/** Returns the user id for a valid, unexpired token, or null. */
function find_api_token_user(PDO $pdo, string $token): ?int
{
    $stmt = $pdo->prepare(
        "SELECT id, user_id, last_used_at FROM api_tokens
         WHERE token_hash = ? AND expires_at > datetime('now')"
    );
    $stmt->execute([hash('sha256', $token)]);
    $row = $stmt->fetch();
    if (!$row) {
        return null;
    }
    // Record use at most once a minute — this runs on every API request.
    if ($row['last_used_at'] === null || strtotime($row['last_used_at'] . ' UTC') < time() - 60) {
        $pdo->prepare("UPDATE api_tokens SET last_used_at = datetime('now') WHERE id = ?")->execute([(int)$row['id']]);
    }
    return (int)$row['user_id'];
}

function delete_api_token(PDO $pdo, string $token): void
{
    $pdo->prepare('DELETE FROM api_tokens WHERE token_hash = ?')->execute([hash('sha256', $token)]);
}

/** The bearer token sent with this request, if any (Apache may hide Authorization — see .htaccess). */
function request_bearer_token(): ?string
{
    $header = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
    if ($header === '' && function_exists('getallheaders')) {
        foreach (getallheaders() as $name => $value) {
            if (strcasecmp($name, 'Authorization') === 0) {
                $header = $value;
            }
        }
    }
    if (preg_match('/^\s*Bearer\s+([A-Za-z0-9]{16,128})\s*$/i', $header, $m)) {
        return $m[1];
    }
    $alt = $_SERVER['HTTP_X_AUTH_TOKEN'] ?? '';
    return preg_match('/^[A-Za-z0-9]{16,128}$/', $alt) ? $alt : null;
}

// -------------------------------------------------------------------------
// Sync
// -------------------------------------------------------------------------

/** Casts a client-supplied timestamp; anything missing or absurd becomes "now" (server clock). */
function sync_ts($value): int
{
    $now = now_ms();
    if (!is_int($value) && !(is_float($value) && is_finite($value)) && !(is_string($value) && ctype_digit($value))) {
        return $now;
    }
    $ts = (int)$value;
    // Refuse timestamps from the far future so a bad phone clock can't win every conflict forever.
    return ($ts <= 0 || $ts > $now + 86400000) ? $now : $ts;
}

function sync_uuid($value): ?string
{
    return (is_string($value) && preg_match('/^[A-Za-z0-9-]{8,64}$/', $value)) ? $value : null;
}

/** A box/place share token as sent by the phone, or null if absent or malformed. */
function sync_token($value): ?string
{
    return (is_string($value) && preg_match('/^[A-Za-z0-9_-]{8,128}$/', $value)) ? $value : null;
}

function sync_name($value): string
{
    $name = is_string($value) ? trim($value) : '';
    return mb_substr($name, 0, IMPORT_MAX_NAME_LENGTH);
}

/** A place (by uuid) only if it's in group $groupId. */
function sync_find_place(PDO $pdo, int $groupId, string $uuid): ?array
{
    $stmt = $pdo->prepare('SELECT * FROM places WHERE uuid = ? AND group_id = ?');
    $stmt->execute([$uuid, $groupId]);
    return $stmt->fetch() ?: null;
}

function sync_find_box(PDO $pdo, int $groupId, string $uuid): ?array
{
    $stmt = $pdo->prepare(
        'SELECT boxes.* FROM boxes JOIN places ON places.id = boxes.place_id
         WHERE boxes.uuid = ? AND places.group_id = ?'
    );
    $stmt->execute([$uuid, $groupId]);
    return $stmt->fetch() ?: null;
}

function sync_find_item(PDO $pdo, int $groupId, string $uuid): ?array
{
    $stmt = $pdo->prepare(
        'SELECT items.* FROM items
         LEFT JOIN boxes ON boxes.id = items.box_id
         JOIN places ON places.id = COALESCE(items.place_id, boxes.place_id)
         WHERE items.uuid = ? AND places.group_id = ?'
    );
    $stmt->execute([$uuid, $groupId]);
    return $stmt->fetch() ?: null;
}

/** True if $uuid is already used in $table anywhere (so another group's row is never overwritten). */
function sync_uuid_taken(PDO $pdo, string $table, string $uuid): bool
{
    $stmt = $pdo->prepare("SELECT 1 FROM $table WHERE uuid = ?");
    $stmt->execute([$uuid]);
    return (bool)$stmt->fetchColumn();
}

/**
 * Applies the phone's changes to group $groupId and returns everything that
 * changed in it on the server after $body['since'] (server-clock ms). A
 * view-only member's changes are all skipped as "read only"; they still
 * receive everything.
 */
function sync_apply(PDO $pdo, int $groupId, array $body, bool $canEdit = true): array
{
    $since = isset($body['since']) && (is_int($body['since']) || ctype_digit((string)$body['since'])) ? (int)$body['since'] : 0;
    $places = is_array($body['places'] ?? null) ? $body['places'] : [];
    $boxes = is_array($body['boxes'] ?? null) ? $body['boxes'] : [];
    $items = is_array($body['items'] ?? null) ? $body['items'] : [];
    $deleted = is_array($body['deleted'] ?? null) ? $body['deleted'] : [];
    $barcodes = is_array($body['barcodes'] ?? null) ? $body['barcodes'] : [];

    if (count($places) + count($boxes) + count($items) + count($deleted) + count($barcodes) > SYNC_MAX_ROWS) {
        json_error('Too many rows in one sync (max ' . SYNC_MAX_ROWS . ')', 413);
    }

    $skipped = [];
    if (!$canEdit) {
        foreach (['places' => $places, 'boxes' => $boxes, 'items' => $items, 'deleted' => $deleted] as $kind => $rows) {
            foreach ($rows as $r) {
                $uuid = is_array($r) ? sync_uuid($r['uuid'] ?? null) : null;
                if ($uuid !== null) {
                    $skipped[] = ['kind' => $kind === 'deleted' ? (string)($r['kind'] ?? '') : $kind, 'uuid' => $uuid, 'reason' => 'read only'];
                }
            }
        }
        $places = $boxes = $items = $deleted = $barcodes = [];
    }

    $pdo->beginTransaction();
    try {
        // 1. Deletes first, so a delete + re-create of the same thing doesn't collide.
        foreach ($deleted as $d) {
            $kind = is_array($d) ? ($d['kind'] ?? '') : '';
            $uuid = is_array($d) ? sync_uuid($d['uuid'] ?? null) : null;
            if ($uuid === null || !in_array($kind, ['places', 'boxes', 'items'], true)) {
                continue;
            }
            $at = sync_ts($d['deleted_at'] ?? null);
            $row = $kind === 'places' ? sync_find_place($pdo, $groupId, $uuid)
                : ($kind === 'boxes' ? sync_find_box($pdo, $groupId, $uuid) : sync_find_item($pdo, $groupId, $uuid));
            // Last write wins: an edit on the server after the phone's delete keeps the row.
            if ($row && (int)$row['updated_at'] <= $at) {
                $pdo->prepare("DELETE FROM $kind WHERE id = ?")->execute([(int)$row['id']]);
            }
        }

        // 2. Places.
        foreach ($places as $p) {
            $uuid = is_array($p) ? sync_uuid($p['uuid'] ?? null) : null;
            $name = is_array($p) ? sync_name($p['name'] ?? null) : '';
            if ($uuid === null || $name === '') {
                continue;
            }
            $ts = sync_ts($p['updated_at'] ?? null);
            $token = sync_token($p['share_token'] ?? null);
            $row = sync_find_place($pdo, $groupId, $uuid);
            if ($row) {
                if ((int)$row['updated_at'] >= $ts) {
                    continue; // server copy is as new or newer
                }
                $slug = unique_place_slug($pdo, $groupId, $name, (int)$row['id']);
                $pdo->prepare('UPDATE places SET name = ?, slug = ?, updated_at = ? WHERE id = ?')
                    ->execute([$name, $slug, $ts, (int)$row['id']]);
            } elseif (sync_uuid_taken($pdo, 'places', $uuid)) {
                $skipped[] = ['kind' => 'places', 'uuid' => $uuid, 'reason' => 'uuid in use'];
            } else {
                if ($token === null || find_place_by_token($pdo, $token) !== null) {
                    $token = new_share_token(); // phone adopts it from the response
                }
                $slug = unique_place_slug($pdo, $groupId, $name);
                $pdo->prepare('INSERT INTO places (group_id, name, slug, share_token, uuid, updated_at) VALUES (?, ?, ?, ?, ?, ?)')
                    ->execute([$groupId, $name, $slug, $token, $uuid, $ts]);
            }
        }

        // 3. Boxes (their place must already exist in this group).
        foreach ($boxes as $b) {
            $uuid = is_array($b) ? sync_uuid($b['uuid'] ?? null) : null;
            $name = is_array($b) ? sync_name($b['name'] ?? null) : '';
            $placeUuid = is_array($b) ? sync_uuid($b['place_uuid'] ?? null) : null;
            if ($uuid === null || $name === '' || $placeUuid === null) {
                continue;
            }
            $place = sync_find_place($pdo, $groupId, $placeUuid);
            if (!$place) {
                $skipped[] = ['kind' => 'boxes', 'uuid' => $uuid, 'reason' => 'place not found'];
                continue;
            }
            $ts = sync_ts($b['updated_at'] ?? null);
            $token = sync_token($b['share_token'] ?? null);
            $row = sync_find_box($pdo, $groupId, $uuid);
            if ($row) {
                if ((int)$row['updated_at'] >= $ts) {
                    continue;
                }
                $slug = unique_box_slug($pdo, (int)$place['id'], $name, (int)$row['id']);
                $pdo->prepare('UPDATE boxes SET place_id = ?, name = ?, slug = ?, updated_at = ? WHERE id = ?')
                    ->execute([(int)$place['id'], $name, $slug, $ts, (int)$row['id']]);
            } elseif (sync_uuid_taken($pdo, 'boxes', $uuid)) {
                $skipped[] = ['kind' => 'boxes', 'uuid' => $uuid, 'reason' => 'uuid in use'];
            } else {
                if ($token === null || find_box_by_token($pdo, $token) !== null) {
                    $token = new_share_token(); // phone adopts it from the response
                }
                $slug = unique_box_slug($pdo, (int)$place['id'], $name);
                $pdo->prepare('INSERT INTO boxes (place_id, name, slug, share_token, uuid, updated_at) VALUES (?, ?, ?, ?, ?, ?)')
                    ->execute([(int)$place['id'], $name, $slug, $token, $uuid, $ts]);
            }
        }

        // 4. Items (in exactly one of: a box, or loose in a place).
        foreach ($items as $i) {
            $uuid = is_array($i) ? sync_uuid($i['uuid'] ?? null) : null;
            $name = is_array($i) ? sync_name($i['name'] ?? null) : '';
            if ($uuid === null || $name === '') {
                continue;
            }
            $boxUuid = sync_uuid($i['box_uuid'] ?? null);
            $placeUuid = sync_uuid($i['place_uuid'] ?? null);
            $boxId = null;
            $placeId = null;
            if ($boxUuid !== null) {
                $box = sync_find_box($pdo, $groupId, $boxUuid);
                $boxId = $box ? (int)$box['id'] : null;
            } elseif ($placeUuid !== null) {
                $place = sync_find_place($pdo, $groupId, $placeUuid);
                $placeId = $place ? (int)$place['id'] : null;
            }
            if ($boxId === null && $placeId === null) {
                $skipped[] = ['kind' => 'items', 'uuid' => $uuid, 'reason' => 'parent not found'];
                continue;
            }
            $rawQty = $i['quantity'] ?? 1;
            $qty = min(IMPORT_MAX_QUANTITY, max(1, is_numeric($rawQty) ? (int)$rawQty : 1));
            $ts = sync_ts($i['updated_at'] ?? null);
            $row = sync_find_item($pdo, $groupId, $uuid);
            if ($row) {
                if ((int)$row['updated_at'] >= $ts) {
                    continue;
                }
                $pdo->prepare('UPDATE items SET box_id = ?, place_id = ?, name = ?, quantity = ?, updated_at = ? WHERE id = ?')
                    ->execute([$boxId, $placeId, $name, $qty, $ts, (int)$row['id']]);
            } elseif (sync_uuid_taken($pdo, 'items', $uuid)) {
                $skipped[] = ['kind' => 'items', 'uuid' => $uuid, 'reason' => 'uuid in use'];
            } else {
                $pdo->prepare('INSERT INTO items (box_id, place_id, name, quantity, uuid, updated_at) VALUES (?, ?, ?, ?, ?, ?)')
                    ->execute([$boxId, $placeId, $name, $qty, $uuid, $ts]);
            }
        }

        // 5. Barcode register (shared across the install, like /barcodes): union, phone's name wins.
        foreach ($barcodes as $bc) {
            $code = is_array($bc) && is_string($bc['barcode'] ?? null) ? trim($bc['barcode']) : '';
            $name = is_array($bc) ? sync_name($bc['name'] ?? null) : '';
            if ($code !== '' && $name !== '' && strlen($code) <= 64) {
                remember_barcode($pdo, $code, $name);
            }
        }

        $pdo->commit();
    } catch (Throwable $e) {
        $pdo->rollBack();
        throw $e;
    }

    // Everything written on the server after the phone's cursor (changed_at,
    // server clock). The new cursor is taken *before* reading, so a write that
    // lands mid-read is simply sent again next time rather than missed.
    $serverTime = now_ms() - 1;
    return [
        'server_time' => $serverTime,
        'changes' => sync_changes_since($pdo, $groupId, $since),
        'skipped' => $skipped,
    ];
}

function sync_changes_since(PDO $pdo, int $groupId, int $since): array
{
    $stmt = $pdo->prepare('SELECT uuid, name, share_token, updated_at FROM places WHERE group_id = ? AND changed_at > ? ORDER BY id');
    $stmt->execute([$groupId, $since]);
    $places = array_map(fn($r) => [
        'uuid' => $r['uuid'], 'name' => $r['name'], 'share_token' => $r['share_token'], 'updated_at' => (int)$r['updated_at'],
    ], $stmt->fetchAll());

    $stmt = $pdo->prepare(
        'SELECT boxes.uuid, boxes.name, boxes.share_token, boxes.updated_at, places.uuid AS place_uuid
         FROM boxes JOIN places ON places.id = boxes.place_id
         WHERE places.group_id = ? AND boxes.changed_at > ? ORDER BY boxes.id'
    );
    $stmt->execute([$groupId, $since]);
    $boxes = array_map(fn($r) => [
        'uuid' => $r['uuid'], 'place_uuid' => $r['place_uuid'], 'name' => $r['name'],
        'share_token' => $r['share_token'], 'updated_at' => (int)$r['updated_at'],
    ], $stmt->fetchAll());

    $stmt = $pdo->prepare(
        'SELECT items.uuid, items.name, items.quantity, items.updated_at,
                b.uuid AS box_uuid, p.uuid AS place_uuid
         FROM items
         LEFT JOIN boxes b ON b.id = items.box_id
         LEFT JOIN places p ON p.id = items.place_id
         JOIN places group_place ON group_place.id = COALESCE(items.place_id, b.place_id)
         WHERE group_place.group_id = ? AND items.changed_at > ? ORDER BY items.id'
    );
    $stmt->execute([$groupId, $since]);
    $items = array_map(fn($r) => [
        'uuid' => $r['uuid'], 'box_uuid' => $r['box_uuid'], 'place_uuid' => $r['box_uuid'] === null ? $r['place_uuid'] : null,
        'name' => $r['name'], 'quantity' => (int)$r['quantity'], 'updated_at' => (int)$r['updated_at'],
    ], $stmt->fetchAll());

    // Tombstones only carry a uuid (never names), so a deleted row can't be
    // traced back to its group; the phone ignores uuids it doesn't have.
    $stmt = $pdo->prepare('SELECT kind, uuid, deleted_at FROM sync_tombstones WHERE deleted_at > ? ORDER BY id');
    $stmt->execute([$since]);
    $deleted = array_map(fn($r) => [
        'kind' => $r['kind'], 'uuid' => $r['uuid'], 'deleted_at' => (int)$r['deleted_at'],
    ], $stmt->fetchAll());

    $barcodes = array_map(
        fn($r) => ['barcode' => $r['barcode'], 'name' => $r['name']],
        $pdo->query('SELECT barcode, name FROM barcode_items ORDER BY barcode')->fetchAll()
    );

    return ['places' => $places, 'boxes' => $boxes, 'items' => $items, 'deleted' => $deleted, 'barcodes' => $barcodes];
}
