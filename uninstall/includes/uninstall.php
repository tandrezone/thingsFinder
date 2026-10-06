<?php
/**
 * thingsFinder — uninstall (the inverse of /setup).
 *
 * /setup:      no users yet  -> create schema + first account -> log in.
 * /uninstall:  first account  -> confirm (password + phrase)   -> delete the
 *              SQLite database (+ -wal/-shm/-journal) -> log out -> the next
 *              request finds no database, so the app is back at /setup.
 *
 * Integration assumptions (adjust the three constants below if yours differ):
 *   - includes/db.php exposes db(): PDO   (get_db() is also accepted)
 *   - the logged-in user's id is in $_SESSION['user_id']
 *   - setup.php creates the first account, so the owner is MIN(users.id)
 */

declare(strict_types=1);

const TF_UNINSTALL_SESSION_KEY = 'user_id';
const TF_UNINSTALL_PHRASE      = 'DELETE EVERYTHING';
// Set to false (e.g. in config) to hide /uninstall completely.
if (!defined('TF_UNINSTALL_ENABLED')) {
    define('TF_UNINSTALL_ENABLED', true);
}

function tf_uninstall_pdo(): PDO
{
    if (function_exists('db'))     { return db(); }
    if (function_exists('get_db')) { return get_db(); }
    throw new RuntimeException('No db() / get_db() function found (include includes/db.php first).');
}

/** Absolute path of the main SQLite file, read from SQLite itself. */
function tf_uninstall_db_path(PDO $pdo): string
{
    foreach ($pdo->query('PRAGMA database_list')->fetchAll(PDO::FETCH_ASSOC) as $row) {
        if ($row['name'] === 'main' && $row['file'] !== '') {
            return $row['file'];
        }
    }
    throw new RuntimeException('Database is in-memory or has no file path.');
}

function tf_uninstall_current_user(PDO $pdo): ?array
{
    $id = $_SESSION[TF_UNINSTALL_SESSION_KEY] ?? null;
    if ($id === null) { return null; }
    $st = $pdo->prepare('SELECT id, username, password_hash FROM users WHERE id = ?');
    $st->execute([(int)$id]);
    return $st->fetch(PDO::FETCH_ASSOC) ?: null;
}

/** The account created by /setup — the only one allowed to uninstall. */
function tf_uninstall_is_owner(PDO $pdo, array $user): bool
{
    $first = $pdo->query('SELECT MIN(id) FROM users')->fetchColumn();
    return $first !== false && (int)$first === (int)$user['id'];
}

/** What will be destroyed, shown on the confirmation page. */
function tf_uninstall_summary(PDO $pdo): array
{
    $count = static function (string $table) use ($pdo): ?int {
        $exists = $pdo->prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?");
        $exists->execute([$table]);
        return $exists->fetchColumn() ? (int)$pdo->query("SELECT COUNT(*) FROM \"$table\"")->fetchColumn() : null;
    };
    $path = tf_uninstall_db_path($pdo);
    return [
        'users'      => $count('users'),
        'places'     => $count('places'),
        'boxes'      => $count('boxes'),
        'items'      => $count('items'),
        'barcodes'   => $count('barcode_items'),
        'api_tokens' => $count('api_tokens'),
        'db_path'    => $path,
        'db_bytes'   => is_file($path) ? filesize($path) : 0,
    ];
}

/* ---------- CSRF (local to this page; the rest of the app has none yet) ---------- */

function tf_uninstall_csrf_token(): string
{
    if (empty($_SESSION['tf_uninstall_csrf'])) {
        $_SESSION['tf_uninstall_csrf'] = bin2hex(random_bytes(32));
    }
    return $_SESSION['tf_uninstall_csrf'];
}

function tf_uninstall_csrf_ok(?string $given): bool
{
    return is_string($given)
        && !empty($_SESSION['tf_uninstall_csrf'])
        && hash_equals($_SESSION['tf_uninstall_csrf'], $given);
}

/* ---------- backup ---------- */

/**
 * Stream a consistent copy of the database as a download.
 * VACUUM INTO gives a clean single-file snapshot even in WAL mode.
 */
function tf_uninstall_send_backup(PDO $pdo): void
{
    $tmp = tempnam(sys_get_temp_dir(), 'tfbak');
    @unlink($tmp); // VACUUM INTO refuses to overwrite
    try {
        $pdo->exec('VACUUM INTO ' . $pdo->quote($tmp));
    } catch (PDOException $e) {
        // SQLite < 3.27: fall back to a checkpoint + plain copy
        try { $pdo->exec('PRAGMA wal_checkpoint(TRUNCATE)'); } catch (PDOException $ignored) {}
        copy(tf_uninstall_db_path($pdo), $tmp);
    }
    $name = 'thingsfinder-backup-' . date('Ymd-His') . '.sqlite';
    header('Content-Type: application/vnd.sqlite3');
    header('Content-Disposition: attachment; filename="' . $name . '"');
    header('Content-Length: ' . filesize($tmp));
    header('Cache-Control: no-store');
    readfile($tmp);
    unlink($tmp);
}

/* ---------- the uninstall itself ---------- */

/**
 * Validates the request and, if everything checks out, deletes the database.
 * Returns a list of error strings ([] = uninstalled).
 */
function tf_uninstall_run(PDO $pdo, array $user, array $post): array
{
    $errors = [];
    if (!tf_uninstall_csrf_ok($post['csrf'] ?? null)) {
        $errors[] = 'The form expired. Reload the page and try again.';
    }
    if (!password_verify((string)($post['password'] ?? ''), (string)$user['password_hash'])) {
        $errors[] = 'Wrong password.';
    }
    if (trim((string)($post['phrase'] ?? '')) !== TF_UNINSTALL_PHRASE) {
        $errors[] = 'Type ' . TF_UNINSTALL_PHRASE . ' exactly to confirm.';
    }
    $others = (int)$pdo->query('SELECT COUNT(*) FROM users')->fetchColumn() - 1;
    if ($others > 0 && empty($post['ack_other_users'])) {
        $errors[] = "Tick the box confirming that the $others other account(s) will be deleted too.";
    }
    if ($errors) { return $errors; }

    $path = tf_uninstall_db_path($pdo);

    // Revoke tokens first, so phones stop syncing even if the file delete fails.
    try { $pdo->exec('DELETE FROM api_tokens'); } catch (PDOException $ignored) {}

    $deleted = tf_uninstall_delete_files($path);
    if (!$deleted) {
        return ["Could not delete $path — check that the web server user can write to its folder."];
    }

    tf_uninstall_destroy_session();
    return [];
}

/** Delete the SQLite file and its sidecars. True if the main file is gone. */
function tf_uninstall_delete_files(string $path): bool
{
    foreach (['-wal', '-shm', '-journal'] as $suffix) {
        if (is_file($path . $suffix)) { @unlink($path . $suffix); }
    }
    if (is_file($path)) { @unlink($path); }
    clearstatcache();
    return !is_file($path);
}

function tf_uninstall_destroy_session(): void
{
    $_SESSION = [];
    if (ini_get('session.use_cookies')) {
        $p = session_get_cookie_params();
        setcookie(session_name(), '', time() - 42000, $p['path'], $p['domain'], $p['secure'], $p['httponly']);
    }
    if (session_status() === PHP_SESSION_ACTIVE) { session_destroy(); }
}
