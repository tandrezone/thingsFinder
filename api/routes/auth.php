<?php
/**
 * Accounts and app tokens: /api/auth/register, /api/auth/login (no credentials needed), /api/auth/logout and /api/me.
 *
 * Included by api/index.php, which has already set $pdo, $method and
 * $segments. Every branch ends the request (json_response/json_error exit).
 */

// ---- /api/auth/register — creates an account (and its personal group) ------
if ($segments === ['auth', 'register']) {
    if ($method !== 'POST') {
        json_error('Method not allowed', 405);
    }
    if (!registration_enabled()) {
        json_error('Registration is turned off on this server — ask its admin for an account', 403);
    }
    $body = read_body();
    $username = is_string($body['username'] ?? null) ? trim($body['username']) : '';
    $password = is_string($body['password'] ?? null) ? $body['password'] : '';
    $problem = validate_new_account($pdo, $username, $password);
    if ($problem !== null) {
        json_error($problem, $problem === USERNAME_TAKEN ? 409 : 400);
    }
    [$newUserId, $newGroupId] = register_user($pdo, $username, $password);
    $device = is_string($body['device_name'] ?? null) ? trim($body['device_name']) : 'Android';
    $token = create_api_token($pdo, $newUserId, $device !== '' ? $device : 'Android');
    json_response([
        'token' => $token,
        'user' => ['id' => $newUserId, 'username' => $username],
        'group' => group_out(find_membership($pdo, $newGroupId, $newUserId)),
    ], 201);
}

// ---- /api/auth/login — needs no credentials either -------------------------
if ($segments === ['auth', 'login']) {
    if ($method !== 'POST') {
        json_error('Method not allowed', 405);
    }
    $body = read_body();
    $username = is_string($body['username'] ?? null) ? trim($body['username']) : '';
    $password = is_string($body['password'] ?? null) ? $body['password'] : '';
    if ($username === '' || $password === '') {
        json_error('username and password are required', 400);
    }
    $user = find_user_by_username($pdo, $username);
    if (!$user || !password_verify($password, $user['password_hash'])) {
        usleep(400000); // slow down password guessing a little
        json_error('Wrong username or password', 401);
    }
    $device = is_string($body['device_name'] ?? null) ? trim($body['device_name']) : 'Android';
    $token = create_api_token($pdo, (int)$user['id'], $device !== '' ? $device : 'Android');
    json_response(['token' => $token, 'user' => ['id' => (int)$user['id'], 'username' => $user['username']]], 201);
}

// ---- /api/auth/logout, /api/me ----------------------------------------
if ($segments === ['auth', 'logout']) {
    if ($method !== 'POST') {
        json_error('Method not allowed', 405);
    }
    $token = request_bearer_token();
    if ($token !== null) {
        delete_api_token($pdo, $token);
    }
    json_response(['logged_out' => true]);
}
if ($segments === ['me']) {
    $me = find_user_by_id($pdo, $userId);
    json_response([
        'user' => ['id' => (int)$me['id'], 'username' => $me['username']],
        'default_group_id' => default_group_id($pdo, $userId),
    ]);
}
