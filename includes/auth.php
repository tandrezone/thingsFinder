<?php
/**
 * Login, registration and the active group.
 *
 * Every place belongs to a group (includes/groups.php) and everyone in the
 * group shares it. The person currently logged in is $_SESSION['user_id'];
 * which of their groups they're looking at is a second, independent idea —
 * $_SESSION['active_group_id'] — set via the group switcher in the topbar.
 * Every route resolves both before doing anything.
 */

function ensure_session_started(): void
{
    if (session_status() !== PHP_SESSION_ACTIVE) {
        session_start();
    }
}

/**
 * Set for the length of one API request when it authenticated with a bearer
 * token (the Android app) instead of the session cookie. Token requests
 * never start a PHP session; they pick their group per request.
 */
function api_token_user_id(?int $set = null, bool $assign = false): ?int
{
    static $userId = null;
    if ($assign) {
        $userId = $set;
    }
    return $userId;
}

function current_user_id(): ?int
{
    if (api_token_user_id() !== null) {
        return api_token_user_id();
    }
    ensure_session_started();
    return isset($_SESSION['user_id']) ? (int)$_SESSION['user_id'] : null;
}

function current_user(PDO $pdo): ?array
{
    $id = current_user_id();
    return $id ? find_user_by_id($pdo, $id) : null;
}

function login_user(int $userId): void
{
    ensure_session_started();
    session_regenerate_id(true);
    $_SESSION['user_id'] = $userId;
    unset($_SESSION['active_group_id']); // resolved to their default group on first use
}

function logout_user(): void
{
    ensure_session_started();
    $_SESSION = [];
    session_regenerate_id(true);
}

/**
 * Redirects to /login (preserving where the person was headed) if nobody
 * is logged in. Call at the top of every UI route except the public
 * read-only /view/{token} page and the login/setup pages themselves.
 */
function require_login(): void
{
    if (current_user_id() === null) {
        $back = current_path();
        redirect('/login' . ($back !== '/' ? '?next=' . rawurlencode($back) : ''));
    }
}

/**
 * Same idea as require_login(), for api.php — a 401 instead of a redirect.
 * Accepts either the web session cookie or an app bearer token.
 */
function require_login_api(?PDO $pdo = null): void
{
    $token = request_bearer_token();
    if ($token !== null) {
        $userId = $pdo ? find_api_token_user($pdo, $token) : null;
        if ($userId === null || find_user_by_id($pdo, $userId) === null) {
            json_error('Invalid or expired token — sign in again', 401);
        }
        api_token_user_id($userId, true);
        return;
    }
    if (current_user_id() === null) {
        json_error('Login required', 401);
    }
}

/**
 * Which group is being looked at right now. Web sessions use the group
 * picked in the switcher (falling back to the user's default group if that
 * choice is no longer valid, e.g. they left it); token requests use
 * ?group_id= when given and they're a member, else the default group.
 */
function active_group_id(PDO $pdo): int
{
    static $resolved = null;
    if ($resolved !== null) {
        return $resolved;
    }
    $userId = (int)current_user_id();
    if (api_token_user_id() !== null) {
        $asked = isset($_GET['group_id']) ? (int)$_GET['group_id'] : 0;
        return $resolved = ($asked && find_membership($pdo, $asked, $userId)) ? $asked : default_group_id($pdo, $userId);
    }
    ensure_session_started();
    $chosen = (int)($_SESSION['active_group_id'] ?? 0);
    if (!$chosen || !find_membership($pdo, $chosen, $userId)) {
        $chosen = default_group_id($pdo, $userId);
        $_SESSION['active_group_id'] = $chosen;
    }
    return $resolved = $chosen;
}

/** The current user's permission in the active group: 'edit' or 'view'. */
function current_permission(PDO $pdo): string
{
    $membership = find_membership($pdo, active_group_id($pdo), (int)current_user_id());
    return $membership['permission'] ?? 'view';
}

function can_edit(PDO $pdo): bool
{
    return current_permission($pdo) === 'edit';
}

/** Blocks a mutating UI action with a flash message if the active group is read-only for this user. */
function require_edit(PDO $pdo): void
{
    if (!can_edit($pdo)) {
        flash_set("You only have view access in this group — ask its owner for edit access to make changes.", 'error');
        redirect(current_path());
    }
}

/** Same idea as require_edit(), for api.php. */
function require_edit_api(PDO $pdo): void
{
    if (!can_edit($pdo)) {
        json_error('You have view-only access to this group', 403);
    }
}

/**
 * Switches the group being viewed (and remembers it as the user's default
 * for next time). Returns false, leaving everything untouched, if they
 * aren't a member of $groupId.
 */
function set_active_group(PDO $pdo, int $groupId): bool
{
    ensure_session_started();
    $userId = (int)current_user_id();
    if (!find_membership($pdo, $groupId, $userId)) {
        return false;
    }
    $_SESSION['active_group_id'] = $groupId;
    set_default_group($pdo, $userId, $groupId);
    return true;
}

// -------------------------------------------------------------------------
// Registration
// -------------------------------------------------------------------------

const USERNAME_TAKEN = 'That username is taken.';

/** Open sign-up is on unless TF_REGISTRATION=off (environment or .env). */
function registration_enabled(): bool
{
    return env_bool('TF_REGISTRATION', true);
}

/** Why a new username/password isn't acceptable, or null if it is. */
function validate_new_account(PDO $pdo, string $username, string $password): ?string
{
    if (!preg_match('/^[A-Za-z0-9._-]{3,40}$/', $username)) {
        return 'Usernames are 3–40 characters: letters, digits, dot, dash or underscore.';
    }
    if (strlen($password) < 8) {
        return 'Password must be at least 8 characters.';
    }
    // Case-insensitive, so "Tiago" can't register next to "tiago".
    $stmt = $pdo->prepare('SELECT 1 FROM users WHERE username = ? COLLATE NOCASE');
    $stmt->execute([$username]);
    if ($stmt->fetchColumn()) {
        return USERNAME_TAKEN;
    }
    return null;
}

/** Creates the account and its personal group; returns [userId, groupId]. */
function register_user(PDO $pdo, string $username, string $password): array
{
    $pdo->beginTransaction();
    try {
        $userId = create_user($pdo, $username, $password);
        $groupId = create_group($pdo, $userId, personal_group_name($username));
        set_default_group($pdo, $userId, $groupId);
        $pdo->commit();
    } catch (Throwable $e) {
        $pdo->rollBack();
        throw $e;
    }
    return [$userId, $groupId];
}
