<?php
/**
 * Database bootstrap: opens (and lazily creates) the SQLite database
 * and makes sure the schema exists.
 */

function get_db(): PDO
{
    static $pdo = null;
    if ($pdo !== null) {
        return $pdo;
    }

    $dbDir = __DIR__ . '/../data';
    if (!is_dir($dbDir)) {
        mkdir($dbDir, 0775, true);
    }
    $dbFile = $dbDir . '/database.sqlite';

    $pdo = new PDO('sqlite:' . $dbFile);
    $pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    $pdo->setAttribute(PDO::ATTR_DEFAULT_FETCH_MODE, PDO::FETCH_ASSOC);
    $pdo->exec('PRAGMA foreign_keys = ON');

    init_schema($pdo);

    return $pdo;
}

function init_schema(PDO $pdo): void
{
    // One row per person who can log in. Every place belongs to exactly one
    // user (its owner); other users can be granted access to *all* of an
    // owner's places/boxes/items via the shares table below.
    $pdo->exec("CREATE TABLE IF NOT EXISTS users (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        username TEXT NOT NULL UNIQUE,
        password_hash TEXT NOT NULL,
        created_at TEXT NOT NULL DEFAULT (datetime('now'))
    )");

    // Grants user_id access to everything owner_id owns, at the given
    // permission. 'edit' can do everything the owner can (add/rename/
    // delete); 'view' can only look. There's no row for an owner viewing
    // their own stuff — that's always full access, handled in code.
    $pdo->exec("CREATE TABLE IF NOT EXISTS shares (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        permission TEXT NOT NULL CHECK (permission IN ('view', 'edit')),
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        UNIQUE(owner_id, user_id)
    )");
    $pdo->exec('CREATE INDEX IF NOT EXISTS idx_shares_user ON shares(user_id)');
    $pdo->exec('CREATE INDEX IF NOT EXISTS idx_shares_owner ON shares(owner_id)');

    // owner_id is nullable at the schema level only so the migration below
    // can add it to a pre-login database in one ALTER TABLE; every place is
    // given a real owner as soon as one exists (see migrate_places_table_if_needed
    // and the first-run setup flow in includes/auth.php). A place's slug is
    // only unique *within its owner's* places, not globally, so two people
    // can each have their own "Garage".
    $pdo->exec("CREATE TABLE IF NOT EXISTS places (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        owner_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
        name TEXT NOT NULL,
        slug TEXT NOT NULL,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        UNIQUE(owner_id, slug)
    )");
    migrate_places_table_if_needed($pdo);

    // share_token is the box's public, unguessable identifier — the QR
    // code / printable label link to /view/{share_token}, a read-only page
    // that needs no login, instead of to the box's real (sequential, easy
    // to guess) id.
    $pdo->exec("CREATE TABLE IF NOT EXISTS boxes (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        place_id INTEGER NOT NULL REFERENCES places(id) ON DELETE CASCADE,
        name TEXT NOT NULL,
        slug TEXT NOT NULL,
        share_token TEXT,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        UNIQUE(place_id, slug)
    )");
    migrate_boxes_table_if_needed($pdo);
    $pdo->exec('CREATE UNIQUE INDEX IF NOT EXISTS idx_boxes_token ON boxes(share_token)');

    // Items can live directly in a place, or inside a box within a place —
    // exactly one of box_id/place_id is set (enforced by the CHECK below).
    // New databases get this shape immediately; a database created before
    // quantity/place-items existed is upgraded in place by
    // migrate_items_table_if_needed(), since SQLite can't ALTER a column's
    // NOT NULL-ness or add a CHECK constraint after the fact.
    $pdo->exec("CREATE TABLE IF NOT EXISTS items (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        box_id INTEGER REFERENCES boxes(id) ON DELETE CASCADE,
        place_id INTEGER REFERENCES places(id) ON DELETE CASCADE,
        name TEXT NOT NULL,
        quantity INTEGER NOT NULL DEFAULT 1,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        CHECK ((box_id IS NULL) <> (place_id IS NULL))
    )");
    migrate_items_table_if_needed($pdo);

    // Must run after boxes/items above have their final columns
    // (share_token, quantity) so a rebuild here can copy every column across.
    repair_dangling_fk_references($pdo);

    // The barcode → item register: scanning a barcode while adding an item
    // either resolves to a name already known here, or (once the user types
    // a name) creates a new association so the same barcode is recognized
    // next time.
    $pdo->exec("CREATE TABLE IF NOT EXISTS barcode_items (
        barcode TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        created_at TEXT NOT NULL DEFAULT (datetime('now'))
    )");

    $pdo->exec("CREATE INDEX IF NOT EXISTS idx_boxes_place ON boxes(place_id)");
    $pdo->exec("CREATE INDEX IF NOT EXISTS idx_items_box ON items(box_id)");
    $pdo->exec("CREATE INDEX IF NOT EXISTS idx_items_place ON items(place_id)");
    $pdo->exec("CREATE INDEX IF NOT EXISTS idx_items_name ON items(name)");
}

/**
 * Upgrades an existing `items` table created before place-level items and
 * quantity existed (box_id NOT NULL, no place_id/quantity columns) to the
 * current shape, preserving every row. No-op on a database that already has
 * the current shape (the common case — this runs on every request).
 */
function migrate_items_table_if_needed(PDO $pdo): void
{
    $cols = array_column($pdo->query('PRAGMA table_info(items)')->fetchAll(), 'name');

    if (!in_array('place_id', $cols, true)) {
        // legacy_alter_table=ON is what actually matters here: by default,
        // SQLite's ALTER TABLE RENAME silently rewrites *other* tables' FK
        // definitions to follow the rename (e.g. a hypothetical
        // `x.item_id REFERENCES items(id)` would become
        // `REFERENCES items_old_migration(id)`), and once the renamed table
        // is dropped a few lines later that reference is left dangling —
        // see repair_dangling_fk_references() for the fallout when this
        // happened for the `places` table below. legacy_alter_table turns
        // that rewrite off, so the rename is "dumb" and only touches this
        // table. Nothing references `items` today, but this keeps the
        // pattern symmetric with migrate_places_table_if_needed() so the
        // same bug doesn't resurface the next time a table gains a FK to
        // items. foreign_keys is also disabled per SQLite's own recommended
        // procedure for this kind of rebuild. Neither pragma can be changed
        // inside a transaction that's already begun.
        $pdo->exec('PRAGMA foreign_keys = OFF');
        $pdo->exec('PRAGMA legacy_alter_table = ON');
        $pdo->beginTransaction();
        try {
            $pdo->exec('ALTER TABLE items RENAME TO items_old_migration');
            $pdo->exec("CREATE TABLE items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                box_id INTEGER REFERENCES boxes(id) ON DELETE CASCADE,
                place_id INTEGER REFERENCES places(id) ON DELETE CASCADE,
                name TEXT NOT NULL,
                quantity INTEGER NOT NULL DEFAULT 1,
                created_at TEXT NOT NULL DEFAULT (datetime('now')),
                CHECK ((box_id IS NULL) <> (place_id IS NULL))
            )");
            $oldCols = array_column($pdo->query('PRAGMA table_info(items_old_migration)')->fetchAll(), 'name');
            $qtySelect = in_array('quantity', $oldCols, true) ? 'quantity' : '1';
            $pdo->exec("INSERT INTO items (id, box_id, place_id, name, quantity, created_at)
                        SELECT id, box_id, NULL, name, $qtySelect, created_at FROM items_old_migration");
            $pdo->exec('DROP TABLE items_old_migration');
            $pdo->commit();
        } catch (Throwable $e) {
            $pdo->rollBack();
            throw $e;
        } finally {
            $pdo->exec('PRAGMA legacy_alter_table = OFF');
            $pdo->exec('PRAGMA foreign_keys = ON');
        }
    } elseif (!in_array('quantity', $cols, true)) {
        $pdo->exec('ALTER TABLE items ADD COLUMN quantity INTEGER NOT NULL DEFAULT 1');
    }
}

/**
 * Upgrades an existing `places` table created before logins existed (no
 * owner_id column, slug globally unique) to the current shape. Every
 * existing place ends up with owner_id NULL until the first-run setup flow
 * assigns them all to the first account created (see ensure_first_user()).
 */
function migrate_places_table_if_needed(PDO $pdo): void
{
    $cols = array_column($pdo->query('PRAGMA table_info(places)')->fetchAll(), 'name');
    if (in_array('owner_id', $cols, true)) {
        return;
    }
    // `places` is a foreign key target for both `boxes.place_id` and
    // `items.place_id`. By default, SQLite's ALTER TABLE RENAME
    // automatically rewrites those *other* tables' FK definitions to track
    // the rename below (e.g. `boxes.place_id REFERENCES places(id)`
    // silently becomes `REFERENCES "places_old_migration"(id)` in boxes'
    // own stored schema) — disabling foreign_keys does NOT stop this, only
    // `PRAGMA legacy_alter_table = ON` does. Once places_old_migration is
    // dropped a few lines later, that rewritten reference is left dangling,
    // and every subsequent INSERT/UPDATE/DELETE against boxes or items
    // fails with "no such table: places_old_migration" the moment SQLite
    // tries to validate the (now nonexistent) FK target — see
    // repair_dangling_fk_references() below for repairing a database that
    // already got left in that state by this bug. foreign_keys is also
    // disabled here per SQLite's own recommended procedure for this kind of
    // rebuild. Neither pragma can be changed inside a transaction that's
    // already begun.
    $pdo->exec('PRAGMA foreign_keys = OFF');
    $pdo->exec('PRAGMA legacy_alter_table = ON');
    $pdo->beginTransaction();
    try {
        $pdo->exec('ALTER TABLE places RENAME TO places_old_migration');
        $pdo->exec("CREATE TABLE places (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            owner_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
            name TEXT NOT NULL,
            slug TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            UNIQUE(owner_id, slug)
        )");
        $pdo->exec('INSERT INTO places (id, owner_id, name, slug, created_at)
                    SELECT id, NULL, name, slug, created_at FROM places_old_migration');
        $pdo->exec('DROP TABLE places_old_migration');
        $pdo->commit();
    } catch (Throwable $e) {
        $pdo->rollBack();
        throw $e;
    } finally {
        $pdo->exec('PRAGMA legacy_alter_table = OFF');
        $pdo->exec('PRAGMA foreign_keys = ON');
    }
}

/**
 * Repairs the fallout of the bug fixed above: on a database where
 * migrate_places_table_if_needed() already ran before legacy_alter_table
 * was used, `boxes.place_id` and/or `items.place_id` are left referencing
 * the transient `places_old_migration` table instead of `places`. That
 * table no longer exists (it was dropped at the end of the migration), so
 * any write to boxes or items that triggers FK validation fails with
 * "SQLSTATE[HY000]: General error: 1 no such table: main.places_old_migration".
 *
 * This detects that dangling reference from each table's stored schema and
 * rebuilds the table (same rename-recreate-copy-drop shape as the
 * migrations above, with legacy_alter_table/foreign_keys handled the same
 * way) so it points at `places` again. No-op once repaired, which is the
 * case on every request after the first.
 */
function repair_dangling_fk_references(PDO $pdo): void
{
    $tables = [
        'boxes' => "CREATE TABLE boxes (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            place_id INTEGER NOT NULL REFERENCES places(id) ON DELETE CASCADE,
            name TEXT NOT NULL,
            slug TEXT NOT NULL,
            share_token TEXT,
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            UNIQUE(place_id, slug)
        )",
        'items' => "CREATE TABLE items (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            box_id INTEGER REFERENCES boxes(id) ON DELETE CASCADE,
            place_id INTEGER REFERENCES places(id) ON DELETE CASCADE,
            name TEXT NOT NULL,
            quantity INTEGER NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            CHECK ((box_id IS NULL) <> (place_id IS NULL))
        )",
    ];

    foreach ($tables as $table => $createSql) {
        $stmt = $pdo->prepare("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?");
        $stmt->execute([$table]);
        $sql = $stmt->fetchColumn();
        $stmt->closeCursor(); // release sqlite_master before the DDL below touches it
        if ($sql === false || strpos($sql, '_old_migration') === false) {
            continue;
        }

        $tmp = $table . '_fk_repair';
        $pdo->exec('PRAGMA foreign_keys = OFF');
        $pdo->exec('PRAGMA legacy_alter_table = ON');
        $pdo->beginTransaction();
        try {
            $pdo->exec("ALTER TABLE $table RENAME TO $tmp");
            $pdo->exec($createSql);
            $colStmt = $pdo->query("PRAGMA table_info($tmp)");
            $cols = implode(', ', array_column($colStmt->fetchAll(), 'name'));
            $colStmt->closeCursor();
            $pdo->exec("INSERT INTO $table ($cols) SELECT $cols FROM $tmp");
            $pdo->exec("DROP TABLE $tmp");
            $pdo->commit();
        } catch (Throwable $e) {
            $pdo->rollBack();
            throw $e;
        } finally {
            $pdo->exec('PRAGMA legacy_alter_table = OFF');
            $pdo->exec('PRAGMA foreign_keys = ON');
        }
    }
}

/** Adds share_token to a pre-existing `boxes` table and backfills a random token for every row that lacks one. */
function migrate_boxes_table_if_needed(PDO $pdo): void
{
    $cols = array_column($pdo->query('PRAGMA table_info(boxes)')->fetchAll(), 'name');
    if (!in_array('share_token', $cols, true)) {
        $pdo->exec('ALTER TABLE boxes ADD COLUMN share_token TEXT');
    }
    $stmt = $pdo->query('SELECT id FROM boxes WHERE share_token IS NULL');
    $ids = $stmt->fetchAll(PDO::FETCH_COLUMN);
    foreach ($ids as $id) {
        $pdo->prepare('UPDATE boxes SET share_token = ? WHERE id = ?')->execute([new_share_token(), $id]);
    }
}

/** A random, unguessable token for a box's public read-only link. */
function new_share_token(): string
{
    return bin2hex(random_bytes(16));
}

/**
 * Runs once per boot: if there are no users yet, the app is either brand
 * new or being upgraded from a version with no login. Either way there's
 * nothing more to do here — the /setup route (includes/auth.php) handles
 * creating the first account and, if there are pre-existing owner-less
 * places (an upgrade), assigns them all to that first account.
 */
function has_any_users(PDO $pdo): bool
{
    return (int)$pdo->query('SELECT COUNT(*) FROM users')->fetchColumn() > 0;
}

function create_user(PDO $pdo, string $username, string $password): int
{
    $pdo->prepare('INSERT INTO users (username, password_hash) VALUES (?, ?)')
        ->execute([$username, password_hash($password, PASSWORD_DEFAULT)]);
    return (int)$pdo->lastInsertId();
}

function find_user_by_username(PDO $pdo, string $username): ?array
{
    $stmt = $pdo->prepare('SELECT * FROM users WHERE username = ?');
    $stmt->execute([$username]);
    return $stmt->fetch() ?: null;
}

function find_user_by_id(PDO $pdo, int $id): ?array
{
    $stmt = $pdo->prepare('SELECT * FROM users WHERE id = ?');
    $stmt->execute([$id]);
    return $stmt->fetch() ?: null;
}

/** Every owner-less place (from an upgrade) is handed to this user — called once, right after the first account is created. */
function adopt_orphan_places(PDO $pdo, int $userId): void
{
    $pdo->prepare('UPDATE places SET owner_id = ? WHERE owner_id IS NULL')->execute([$userId]);
}

/** Creates a share (or updates the permission of an existing one). */
function upsert_share(PDO $pdo, int $ownerId, int $userId, string $permission): void
{
    $pdo->prepare(
        'INSERT INTO shares (owner_id, user_id, permission) VALUES (?, ?, ?)
         ON CONFLICT(owner_id, user_id) DO UPDATE SET permission = excluded.permission'
    )->execute([$ownerId, $userId, $permission]);
}

function delete_share(PDO $pdo, int $ownerId, int $userId): void
{
    $pdo->prepare('DELETE FROM shares WHERE owner_id = ? AND user_id = ?')->execute([$ownerId, $userId]);
}

/** The permission $userId has on $ownerId's data, or null if none was granted (and they're not the owner). */
function find_share(PDO $pdo, int $ownerId, int $userId): ?array
{
    $stmt = $pdo->prepare('SELECT * FROM shares WHERE owner_id = ? AND user_id = ?');
    $stmt->execute([$ownerId, $userId]);
    return $stmt->fetch() ?: null;
}

/** People you've granted access to your own stuff — for the "People" management page. */
function list_shares_granted_by(PDO $pdo, int $ownerId): array
{
    $stmt = $pdo->prepare(
        'SELECT shares.*, users.username FROM shares
         JOIN users ON users.id = shares.user_id
         WHERE shares.owner_id = ? ORDER BY users.username COLLATE NOCASE'
    );
    $stmt->execute([$ownerId]);
    return $stmt->fetchAll();
}

/** Other accounts that have granted *you* access — for the context switcher. */
function list_shares_received_by(PDO $pdo, int $userId): array
{
    $stmt = $pdo->prepare(
        'SELECT shares.*, users.username AS owner_username FROM shares
         JOIN users ON users.id = shares.owner_id
         WHERE shares.user_id = ? ORDER BY users.username COLLATE NOCASE'
    );
    $stmt->execute([$userId]);
    return $stmt->fetchAll();
}

/** Looks up a registered barcode → item association, if any. */
function find_barcode(PDO $pdo, string $barcode): ?array
{
    $stmt = $pdo->prepare('SELECT * FROM barcode_items WHERE barcode = ?');
    $stmt->execute([$barcode]);
    $row = $stmt->fetch();
    return $row ?: null;
}

/** Registers (or re-labels) a barcode → item name association. */
function remember_barcode(PDO $pdo, string $barcode, string $name): void
{
    $pdo->prepare(
        'INSERT INTO barcode_items (barcode, name) VALUES (?, ?)
         ON CONFLICT(barcode) DO UPDATE SET name = excluded.name'
    )->execute([$barcode, $name]);
}

/** Turn arbitrary text into a URL-safe slug. */
function slugify(string $text): string
{
    $text = trim($text);
    $text = mb_strtolower($text, 'UTF-8');
    // Replace anything that isn't a letter/number with a hyphen.
    $text = preg_replace('/[^a-z0-9]+/u', '-', $text);
    $text = trim($text, '-');
    if ($text === '') {
        $text = 'x';
    }
    return $text;
}

/** Generate a slug for a place that is unique among one owner's places (two different people can each have a "garage"). */
function unique_place_slug(PDO $pdo, int $ownerId, string $name, ?int $excludeId = null): string
{
    $base = slugify($name);
    $slug = $base;
    $n = 2;
    while (true) {
        $sql = 'SELECT COUNT(*) FROM places WHERE owner_id = ? AND slug = ?';
        $params = [$ownerId, $slug];
        if ($excludeId !== null) {
            $sql .= ' AND id != ?';
            $params[] = $excludeId;
        }
        $stmt = $pdo->prepare($sql);
        $stmt->execute($params);
        if ((int)$stmt->fetchColumn() === 0) {
            return $slug;
        }
        $slug = $base . '-' . $n;
        $n++;
    }
}

/** Generate a slug for a box that is unique within its place. */
function unique_box_slug(PDO $pdo, int $placeId, string $name, ?int $excludeId = null): string
{
    $base = slugify($name);
    $slug = $base;
    $n = 2;
    while (true) {
        $sql = 'SELECT COUNT(*) FROM boxes WHERE place_id = ? AND slug = ?';
        $params = [$placeId, $slug];
        if ($excludeId !== null) {
            $sql .= ' AND id != ?';
            $params[] = $excludeId;
        }
        $stmt = $pdo->prepare($sql);
        $stmt->execute($params);
        if ((int)$stmt->fetchColumn() === 0) {
            return $slug;
        }
        $slug = $base . '-' . $n;
        $n++;
    }
}

function find_place_by_slug(PDO $pdo, int $ownerId, string $slug): ?array
{
    $stmt = $pdo->prepare('SELECT * FROM places WHERE owner_id = ? AND slug = ?');
    $stmt->execute([$ownerId, $slug]);
    $row = $stmt->fetch();
    return $row ?: null;
}

function find_box_by_slug(PDO $pdo, int $placeId, string $slug): ?array
{
    $stmt = $pdo->prepare('SELECT * FROM boxes WHERE place_id = ? AND slug = ?');
    $stmt->execute([$placeId, $slug]);
    $row = $stmt->fetch();
    return $row ?: null;
}

/** Looks up a box by its public share token, for the read-only /view/{token} page — no owner check, that's the point. */
function find_box_by_token(PDO $pdo, string $token): ?array
{
    $stmt = $pdo->prepare('SELECT * FROM boxes WHERE share_token = ?');
    $stmt->execute([$token]);
    $row = $stmt->fetch();
    return $row ?: null;
}

/** Every place an owner has, for a destination picker (move item/box, etc). */
function list_places_for_owner(PDO $pdo, int $ownerId): array
{
    $stmt = $pdo->prepare('SELECT * FROM places WHERE owner_id = ? ORDER BY name COLLATE NOCASE');
    $stmt->execute([$ownerId]);
    return $stmt->fetchAll();
}

/** Every box across every one of an owner's places, place name/slug alongside — for the same pickers. */
function list_boxes_for_owner(PDO $pdo, int $ownerId): array
{
    $stmt = $pdo->prepare(
        'SELECT boxes.*, places.name AS place_name, places.slug AS place_slug
         FROM boxes JOIN places ON places.id = boxes.place_id
         WHERE places.owner_id = ?
         ORDER BY places.name COLLATE NOCASE, boxes.name COLLATE NOCASE'
    );
    $stmt->execute([$ownerId]);
    return $stmt->fetchAll();
}

/**
 * Every place the owner has, each with its own boxes nested under a
 * `boxes` key — the shape the move-item and move-box destination pickers
 * render as a single grouped <select> (one <optgroup> per place).
 */
function list_move_destinations(PDO $pdo, int $ownerId): array
{
    $places = list_places_for_owner($pdo, $ownerId);
    $byPlace = [];
    foreach (list_boxes_for_owner($pdo, $ownerId) as $box) {
        $byPlace[(int)$box['place_id']][] = $box;
    }
    foreach ($places as &$place) {
        $place['boxes'] = $byPlace[(int)$place['id']] ?? [];
    }
    unset($place);
    return $places;
}

/**
 * Moves an item to a different box, or directly into a place (loose, no
 * box) — $destination is "box:{id}" or "place:{id}", as produced by the
 * move-item picker. Returns false without changing anything if it doesn't
 * parse, if $itemId doesn't currently belong to $ownerId, or if the target
 * box/place doesn't belong to $ownerId either. Both ownership checks
 * matter: without the destination check, a tampered request could move an
 * item into someone else's account; without the source check, it could
 * just as easily pull an item *out* of someone else's account by guessing
 * its id — items don't carry an owner_id of their own, so this is the only
 * thing enforcing that boundary.
 */
function move_item_to(PDO $pdo, int $itemId, int $ownerId, string $destination): bool
{
    [$type, $rawId] = array_pad(explode(':', $destination, 2), 2, '');
    $destId = (int)$rawId;
    if ($destId <= 0 || !in_array($type, ['box', 'place'], true)) {
        return false;
    }

    $stmt = $pdo->prepare(
        'SELECT items.id FROM items
         LEFT JOIN boxes ON boxes.id = items.box_id
         JOIN places ON places.id = COALESCE(items.place_id, boxes.place_id)
         WHERE items.id = ? AND places.owner_id = ?'
    );
    $stmt->execute([$itemId, $ownerId]);
    if (!$stmt->fetchColumn()) {
        return false;
    }

    if ($type === 'box') {
        $stmt = $pdo->prepare(
            'SELECT boxes.id FROM boxes JOIN places ON places.id = boxes.place_id
             WHERE boxes.id = ? AND places.owner_id = ?'
        );
        $stmt->execute([$destId, $ownerId]);
        if (!$stmt->fetchColumn()) {
            return false;
        }
        $pdo->prepare('UPDATE items SET box_id = ?, place_id = NULL WHERE id = ?')->execute([$destId, $itemId]);
        return true;
    }

    $stmt = $pdo->prepare('SELECT id FROM places WHERE id = ? AND owner_id = ?');
    $stmt->execute([$destId, $ownerId]);
    if (!$stmt->fetchColumn()) {
        return false;
    }
    $pdo->prepare('UPDATE items SET box_id = NULL, place_id = ? WHERE id = ?')->execute([$destId, $itemId]);
    return true;
}

/**
 * Moves a box — and everything inside it — to a different place owned by
 * $ownerId. Slugs are only unique *within* a place, so a box named "Tools"
 * moving into a place that already has a "Tools" box needs a fresh slug;
 * unique_box_slug() handles that. Returns the box's (possibly new) slug on
 * success, or null if $boxId doesn't currently belong to $ownerId, or
 * $newPlaceId isn't one of $ownerId's places. Both checks matter for the
 * same reason as in move_item_to(): without the source check, a tampered
 * request could pull a box (and everything in it) out of someone else's
 * account just by guessing its id.
 */
function move_box_to(PDO $pdo, int $boxId, int $ownerId, int $newPlaceId): ?string
{
    $stmt = $pdo->prepare(
        'SELECT boxes.name FROM boxes JOIN places ON places.id = boxes.place_id
         WHERE boxes.id = ? AND places.owner_id = ?'
    );
    $stmt->execute([$boxId, $ownerId]);
    $name = $stmt->fetchColumn();
    if ($name === false) {
        return null;
    }

    $stmt = $pdo->prepare('SELECT id FROM places WHERE id = ? AND owner_id = ?');
    $stmt->execute([$newPlaceId, $ownerId]);
    if (!$stmt->fetchColumn()) {
        return null;
    }

    $slug = unique_box_slug($pdo, $newPlaceId, $name, $boxId);
    $pdo->prepare('UPDATE boxes SET place_id = ?, slug = ? WHERE id = ?')->execute([$newPlaceId, $slug, $boxId]);
    return $slug;
}
