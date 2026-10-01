<?php
/**
 * Groups: who shares which places/boxes/items.
 *
 * Every place belongs to exactly one group, and every member of that group
 * sees everything in it. Members with 'edit' permission (the default for
 * anyone who joins) can add, rename, move and delete anything; 'view'
 * members can only look — that only comes from accounts migrated from the
 * old per-owner "shares", and the group's owner can promote them.
 *
 * Each account gets a personal group when it's created, and can create more
 * and invite people in three ways, all backed by two secrets on the group:
 *   - a link, /join/{invite_token} (also shown as a QR code),
 *   - the group's name plus its 8-character join key, typed in by hand.
 * The owner can regenerate both at any time, which kills old invites.
 *
 * The table is called inventory_groups because GROUPS is an SQL keyword.
 */

const JOIN_KEY_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // no 0/O/1/I — read aloud or typed from a screen
const JOIN_KEY_LENGTH = 8;
const GROUP_NAME_MAX_LENGTH = 80;

function init_groups_schema(PDO $pdo): void
{
    $pdo->exec("CREATE TABLE IF NOT EXISTS inventory_groups (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        invite_token TEXT NOT NULL UNIQUE,
        join_key TEXT NOT NULL,
        created_at TEXT NOT NULL DEFAULT (datetime('now'))
    )");

    // role 'owner' manages the group itself (rename, invites, members,
    // delete); permission decides what a member can do with its contents.
    $pdo->exec("CREATE TABLE IF NOT EXISTS group_members (
        group_id INTEGER NOT NULL REFERENCES inventory_groups(id) ON DELETE CASCADE,
        user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        role TEXT NOT NULL DEFAULT 'member' CHECK (role IN ('owner', 'member')),
        permission TEXT NOT NULL DEFAULT 'edit' CHECK (permission IN ('edit', 'view')),
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        PRIMARY KEY (group_id, user_id)
    )");
    $pdo->exec('CREATE INDEX IF NOT EXISTS idx_group_members_user ON group_members(user_id)');

    // The group a user lands in by default (web login, and app syncs that don't name one).
    $cols = array_column($pdo->query('PRAGMA table_info(users)')->fetchAll(), 'name');
    if (!in_array('default_group_id', $cols, true)) {
        $pdo->exec('ALTER TABLE users ADD COLUMN default_group_id INTEGER');
    }
}

/**
 * Upgrades a database from per-owner places (places.owner_id + shares) to
 * groups: every user gets a personal group holding the places they owned,
 * and every share becomes a membership of the sharer's group at the same
 * permission, so everybody keeps seeing exactly what they saw before.
 * No-op once places has a group_id column.
 */
function migrate_places_to_groups(PDO $pdo): void
{
    $cols = array_column($pdo->query('PRAGMA table_info(places)')->fetchAll(), 'name');
    if (in_array('group_id', $cols, true)) {
        return;
    }

    // Same rebuild procedure (and the same reasons for both pragmas) as
    // migrate_places_table_if_needed() in includes/db.php.
    $pdo->exec('PRAGMA foreign_keys = OFF');
    $pdo->exec('PRAGMA legacy_alter_table = ON');
    $pdo->beginTransaction();
    try {
        $groupOf = [];
        foreach ($pdo->query('SELECT id, username FROM users ORDER BY id')->fetchAll() as $u) {
            $groupOf[(int)$u['id']] = create_group($pdo, (int)$u['id'], personal_group_name($u['username']));
        }
        $hasShares = (bool)$pdo->query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'shares'")->fetchColumn();
        if ($hasShares) {
            foreach ($pdo->query('SELECT owner_id, user_id, permission FROM shares')->fetchAll() as $s) {
                $groupId = $groupOf[(int)$s['owner_id']] ?? null;
                if ($groupId !== null) {
                    add_group_member($pdo, $groupId, (int)$s['user_id'], 'member', $s['permission'] === 'edit' ? 'edit' : 'view');
                }
            }
        }

        $owners = $pdo->query('SELECT id, owner_id FROM places')->fetchAll();
        $pdo->exec('ALTER TABLE places RENAME TO places_old_migration');
        $pdo->exec(places_table_sql());
        $oldCols = array_column($pdo->query('PRAGMA table_info(places_old_migration)')->fetchAll(), 'name');
        $newCols = array_column($pdo->query('PRAGMA table_info(places)')->fetchAll(), 'name');
        $copy = implode(', ', array_values(array_intersect($oldCols, $newCols)));
        $pdo->exec("INSERT INTO places ($copy) SELECT $copy FROM places_old_migration");
        $pdo->exec('DROP TABLE places_old_migration');

        $set = $pdo->prepare('UPDATE places SET group_id = ? WHERE id = ?');
        foreach ($owners as $row) {
            $groupId = $row['owner_id'] !== null ? ($groupOf[(int)$row['owner_id']] ?? null) : null;
            $set->execute([$groupId, (int)$row['id']]); // owner-less places stay NULL until /setup adopts them
        }
        $pdo->commit();
    } catch (Throwable $e) {
        $pdo->rollBack();
        throw $e;
    } finally {
        $pdo->exec('PRAGMA legacy_alter_table = OFF');
        $pdo->exec('PRAGMA foreign_keys = ON');
    }
}

/** The current shape of `places` — shared by the fresh-install CREATE and the migration above. */
function places_table_sql(string $ifNotExists = ''): string
{
    // A place's slug is unique within its group, so two groups can each have a "Garage".
    // uuid/updated_at/changed_at are the sync columns (includes/sync.php); share_token
    // identifies the place in its add-item / remove-item QR codes.
    return "CREATE TABLE $ifNotExists places (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        group_id INTEGER REFERENCES inventory_groups(id) ON DELETE CASCADE,
        name TEXT NOT NULL,
        slug TEXT NOT NULL,
        share_token TEXT,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        uuid TEXT,
        updated_at INTEGER,
        changed_at INTEGER,
        UNIQUE(group_id, slug)
    )";
}

function personal_group_name(string $username): string
{
    return mb_substr($username . "'s things", 0, GROUP_NAME_MAX_LENGTH);
}

function clean_group_name($value): string
{
    $name = is_string($value) ? trim(preg_replace('/\s+/u', ' ', $value)) : '';
    return mb_substr($name, 0, GROUP_NAME_MAX_LENGTH);
}

function new_join_key(): string
{
    $key = '';
    for ($i = 0; $i < JOIN_KEY_LENGTH; $i++) {
        $key .= JOIN_KEY_ALPHABET[random_int(0, strlen(JOIN_KEY_ALPHABET) - 1)];
    }
    return $key;
}

/** "K7F39QX2" -> "K7F3-9QX2", for showing to people. */
function format_join_key(string $key): string
{
    return substr($key, 0, 4) . '-' . substr($key, 4);
}

/** Whatever someone typed ("k7f3 9qx2", "K7F3-9QX2") -> "K7F39QX2". */
function normalize_join_key(string $typed): string
{
    return strtoupper(preg_replace('/[^A-Za-z0-9]/', '', $typed));
}

/** Creates a group with $ownerId as its owner (edit permission) and returns its id. */
function create_group(PDO $pdo, int $ownerId, string $name): int
{
    $pdo->prepare('INSERT INTO inventory_groups (name, invite_token, join_key) VALUES (?, ?, ?)')
        ->execute([$name, bin2hex(random_bytes(16)), new_join_key()]);
    $groupId = (int)$pdo->lastInsertId();
    add_group_member($pdo, $groupId, $ownerId, 'owner', 'edit');
    return $groupId;
}

/** Adds a member; an existing membership is left exactly as it is. */
function add_group_member(PDO $pdo, int $groupId, int $userId, string $role = 'member', string $permission = 'edit'): void
{
    $pdo->prepare(
        'INSERT INTO group_members (group_id, user_id, role, permission) VALUES (?, ?, ?, ?)
         ON CONFLICT(group_id, user_id) DO NOTHING'
    )->execute([$groupId, $userId, $role, $permission]);
}

function remove_group_member(PDO $pdo, int $groupId, int $userId): void
{
    $pdo->prepare('DELETE FROM group_members WHERE group_id = ? AND user_id = ?')->execute([$groupId, $userId]);
}

function set_member_permission(PDO $pdo, int $groupId, int $userId, string $permission): void
{
    $pdo->prepare("UPDATE group_members SET permission = ? WHERE group_id = ? AND user_id = ? AND role != 'owner'")
        ->execute([$permission === 'edit' ? 'edit' : 'view', $groupId, $userId]);
}

function rename_group(PDO $pdo, int $groupId, string $name): void
{
    $pdo->prepare('UPDATE inventory_groups SET name = ? WHERE id = ?')->execute([$name, $groupId]);
}

/** Deletes the group and — through ON DELETE CASCADE — every place, box and item in it. */
function delete_group(PDO $pdo, int $groupId): void
{
    $pdo->prepare('DELETE FROM inventory_groups WHERE id = ?')->execute([$groupId]);
}

/** New link and key: every invite handed out so far stops working. */
function reset_group_invite(PDO $pdo, int $groupId): void
{
    $pdo->prepare('UPDATE inventory_groups SET invite_token = ?, join_key = ? WHERE id = ?')
        ->execute([bin2hex(random_bytes(16)), new_join_key(), $groupId]);
}

/** The group plus $userId's role/permission in it, or null if they aren't a member. */
function find_membership(PDO $pdo, int $groupId, int $userId): ?array
{
    $stmt = $pdo->prepare(
        'SELECT g.*, m.role, m.permission,
                (SELECT COUNT(*) FROM group_members c WHERE c.group_id = g.id) AS member_count
         FROM inventory_groups g JOIN group_members m ON m.group_id = g.id
         WHERE g.id = ? AND m.user_id = ?'
    );
    $stmt->execute([$groupId, $userId]);
    return $stmt->fetch() ?: null;
}

/** Every group $userId belongs to (same row shape as find_membership()), by name. */
function list_user_groups(PDO $pdo, int $userId): array
{
    $stmt = $pdo->prepare(
        'SELECT g.*, m.role, m.permission,
                (SELECT COUNT(*) FROM group_members c WHERE c.group_id = g.id) AS member_count
         FROM inventory_groups g JOIN group_members m ON m.group_id = g.id
         WHERE m.user_id = ? ORDER BY g.name COLLATE NOCASE, g.id'
    );
    $stmt->execute([$userId]);
    return $stmt->fetchAll();
}

function list_group_members(PDO $pdo, int $groupId): array
{
    $stmt = $pdo->prepare(
        "SELECT users.id, users.username, m.role, m.permission FROM group_members m
         JOIN users ON users.id = m.user_id
         WHERE m.group_id = ? ORDER BY m.role = 'owner' DESC, users.username COLLATE NOCASE"
    );
    $stmt->execute([$groupId]);
    return $stmt->fetchAll();
}

function find_group_by_invite_token(PDO $pdo, string $token): ?array
{
    if (!preg_match('/^[a-f0-9]{32}$/', $token)) {
        return null;
    }
    $stmt = $pdo->prepare('SELECT * FROM inventory_groups WHERE invite_token = ?');
    $stmt->execute([$token]);
    return $stmt->fetch() ?: null;
}

/** Group names aren't unique, so the key decides which "Family" is meant. */
function find_group_by_name_and_key(PDO $pdo, string $name, string $typedKey): ?array
{
    $key = normalize_join_key($typedKey);
    $name = clean_group_name($name);
    if ($name === '' || strlen($key) !== JOIN_KEY_LENGTH) {
        return null;
    }
    $stmt = $pdo->prepare('SELECT * FROM inventory_groups WHERE join_key = ? AND name = ? COLLATE NOCASE');
    $stmt->execute([$key, $name]);
    return $stmt->fetch() ?: null;
}

/**
 * The group $userId works in when nothing more specific was asked for: their
 * saved default if they're still a member, else the first group they own,
 * else any group they're in. Someone left with no group at all (they left or
 * deleted every one) gets a fresh personal group, so there's always one.
 */
function default_group_id(PDO $pdo, int $userId): int
{
    $stmt = $pdo->prepare(
        "SELECT m.group_id FROM group_members m JOIN users u ON u.id = m.user_id
         WHERE m.user_id = ?
         ORDER BY (m.group_id = u.default_group_id) DESC, (m.role = 'owner') DESC, m.group_id
         LIMIT 1"
    );
    $stmt->execute([$userId]);
    $groupId = $stmt->fetchColumn();
    if ($groupId !== false) {
        return (int)$groupId;
    }
    $user = find_user_by_id($pdo, $userId);
    $groupId = create_group($pdo, $userId, personal_group_name($user['username'] ?? 'My'));
    set_default_group($pdo, $userId, $groupId);
    return $groupId;
}

function set_default_group(PDO $pdo, int $userId, int $groupId): void
{
    $pdo->prepare('UPDATE users SET default_group_id = ? WHERE id = ?')->execute([$groupId, $userId]);
}

/** The /join/{token} link for a group, absolute so it works when shared or turned into a QR code. */
function group_invite_url(array $group): string
{
    return base_url() . '/join/' . $group['invite_token'];
}
