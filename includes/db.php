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
    $pdo->exec("CREATE TABLE IF NOT EXISTS places (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        slug TEXT NOT NULL UNIQUE,
        created_at TEXT NOT NULL DEFAULT (datetime('now'))
    )");

    $pdo->exec("CREATE TABLE IF NOT EXISTS boxes (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        place_id INTEGER NOT NULL REFERENCES places(id) ON DELETE CASCADE,
        name TEXT NOT NULL,
        slug TEXT NOT NULL,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        UNIQUE(place_id, slug)
    )");

    $pdo->exec("CREATE TABLE IF NOT EXISTS items (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        box_id INTEGER NOT NULL REFERENCES boxes(id) ON DELETE CASCADE,
        name TEXT NOT NULL,
        created_at TEXT NOT NULL DEFAULT (datetime('now'))
    )");

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
    $pdo->exec("CREATE INDEX IF NOT EXISTS idx_items_name ON items(name)");
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

/** Generate a slug for a place that is unique across all places. */
function unique_place_slug(PDO $pdo, string $name, ?int $excludeId = null): string
{
    $base = slugify($name);
    $slug = $base;
    $n = 2;
    while (true) {
        $sql = 'SELECT COUNT(*) FROM places WHERE slug = ?';
        $params = [$slug];
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

function find_place_by_slug(PDO $pdo, string $slug): ?array
{
    $stmt = $pdo->prepare('SELECT * FROM places WHERE slug = ?');
    $stmt->execute([$slug]);
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
