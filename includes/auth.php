<?php
/**
 * Login/accounts/sharing.
 *
 * Every place belongs to one user (its owner). A user always has full
 * access to their own stuff. An owner can grant another user "view" (look
 * only) or "edit" (do anything the owner can) access to *everything* they
 * own, via a row in the `shares` table — there's no per-place sharing.
 *
 * The person currently logged in is $_SESSION['user_id']. Because someone
 * can be looking at either their own stuff or an account that shared with
 * them, there's a second, independent idea of "whose stuff am I currently
 * looking at" — $_SESSION['active_owner_id'] — set via the context
 * switcher in the topbar. Every route resolves both before doing anything.
 */

function ensure_session_started(): void
{
    if (session_status() !== PHP_SESSION_ACTIVE) {
        session_start();
    }
}

function current_user_id(): ?int
{
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
    $_SESSION['active_owner_id'] = $userId;
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

/** Same idea as require_login(), for api.php — a 401 instead of a redirect. */
function require_login_api(): void
{
    if (current_user_id() === null) {
        json_error('Login required', 401);
    }
}

/**
 * Whose stuff is being looked at right now: the logged-in user themself
 * by default, or another account that shared their stuff with this user
 * and was selected via the context switcher. Falls back to "my own stuff"
 * if the stashed choice is no longer valid (e.g. access was revoked).
 */
function active_owner_id(PDO $pdo): int
{
    ensure_session_started();
    $userId = current_user_id();
    $chosen = isset($_SESSION['active_owner_id']) ? (int)$_SESSION['active_owner_id'] : $userId;
    if ($chosen === $userId) {
        return $userId;
    }
    if (find_share($pdo, $chosen, $userId) !== null) {
        return $chosen;
    }
    $_SESSION['active_owner_id'] = $userId;
    return $userId;
}

/** 'edit' on your own stuff is automatic; on someone else's, it's whatever they granted you. */
function current_permission(PDO $pdo): string
{
    $userId = current_user_id();
    $ownerId = active_owner_id($pdo);
    if ($ownerId === $userId) {
        return 'edit';
    }
    $share = find_share($pdo, $ownerId, $userId);
    return $share['permission'] ?? 'view';
}

function can_edit(PDO $pdo): bool
{
    return current_permission($pdo) === 'edit';
}

/** Blocks a mutating UI action with a flash message if the active context is read-only. */
function require_edit(PDO $pdo): void
{
    if (!can_edit($pdo)) {
        flash_set("You only have view access here — ask the owner for edit access to make changes.", 'error');
        redirect(current_path());
    }
}

/** Same idea as require_edit(), for api.php. */
function require_edit_api(PDO $pdo): void
{
    if (!can_edit($pdo)) {
        json_error('You have view-only access to this account\'s data', 403);
    }
}

/**
 * Switches which account's stuff is being viewed. Returns false (and
 * leaves the session untouched) if $ownerId isn't the user themself or an
 * account that has shared with them — call sites should treat that as a
 * "you don't have access to that" error.
 */
function set_active_owner(PDO $pdo, int $ownerId): bool
{
    ensure_session_started();
    $userId = current_user_id();
    if ($ownerId !== $userId && find_share($pdo, $ownerId, $userId) === null) {
        return false;
    }
    $_SESSION['active_owner_id'] = $ownerId;
    return true;
}
