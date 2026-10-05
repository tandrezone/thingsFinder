<?php
/**
 * /api/groups[/...] — groups, invites and members (see includes/groups.php).
 *
 * Included by api/index.php, which has already set $pdo, $method and
 * $segments, and — for anything but register/login — $userId and $groupId. Every branch ends the request (json_response/json_error exit).
 */

/** /api/groups/... — $rest is the path after "groups". Always ends the request. */
function api_groups(PDO $pdo, string $method, array $rest, int $userId): void
{
    // /api/groups
    if ($rest === []) {
        if ($method === 'GET') {
            json_response([
                'groups' => array_map('group_out', list_user_groups($pdo, $userId)),
                'default_group_id' => default_group_id($pdo, $userId),
            ]);
        }
        if ($method === 'POST') {
            $name = clean_group_name(read_body()['name'] ?? null);
            if ($name === '') {
                json_error('name is required');
            }
            $id = create_group($pdo, $userId, $name);
            json_response(['group' => group_out(find_membership($pdo, $id, $userId))], 201);
        }
        json_error('Method not allowed', 405);
    }

    // /api/groups/join — by invite token (link / QR code), or by name + key
    if ($rest === ['join']) {
        if ($method !== 'POST') {
            json_error('Method not allowed', 405);
        }
        $body = read_body();
        $token = is_string($body['token'] ?? null) ? trim($body['token']) : '';
        $group = $token !== ''
            ? find_group_by_invite_token($pdo, $token)
            : find_group_by_name_and_key($pdo, (string)($body['name'] ?? ''), (string)($body['key'] ?? ''));
        if (!$group) {
            usleep(300000); // keys are short — slow down guessing a little
            json_error('Invite not found — check the link, or the group name and key', 404);
        }
        add_group_member($pdo, (int)$group['id'], $userId);
        json_response(['group' => group_out(find_membership($pdo, (int)$group['id'], $userId))]);
    }

    $groupId = (int)$rest[0];
    $membership = find_membership($pdo, $groupId, $userId);
    if (!$membership) {
        json_error('Group not found', 404);
    }
    $isOwner = $membership['role'] === 'owner';
    $requireOwner = function () use ($isOwner) {
        if (!$isOwner) {
            json_error('Only the group\'s owner can do that', 403);
        }
    };

    // /api/groups/{id}
    if (count($rest) === 1) {
        if ($method === 'GET') {
            $members = array_map(fn($m) => [
                'id' => (int)$m['id'], 'username' => $m['username'], 'role' => $m['role'], 'permission' => $m['permission'],
            ], list_group_members($pdo, $groupId));
            json_response(['group' => group_out($membership), 'members' => $members]);
        }
        if ($method === 'PUT' || $method === 'PATCH') {
            $requireOwner();
            $name = clean_group_name(read_body()['name'] ?? null);
            if ($name === '') {
                json_error('name is required');
            }
            rename_group($pdo, $groupId, $name);
            json_response(['group' => group_out(find_membership($pdo, $groupId, $userId))]);
        }
        if ($method === 'DELETE') {
            $requireOwner();
            delete_group($pdo, $groupId);
            json_response(['deleted' => true]);
        }
        json_error('Method not allowed', 405);
    }

    // /api/groups/{id}/invite/reset
    if (array_slice($rest, 1) === ['invite', 'reset'] && $method === 'POST') {
        $requireOwner();
        reset_group_invite($pdo, $groupId);
        json_response(['group' => group_out(find_membership($pdo, $groupId, $userId))]);
    }

    // /api/groups/{id}/leave
    if (array_slice($rest, 1) === ['leave'] && $method === 'POST') {
        if ($isOwner) {
            json_error('You own this group — delete it instead of leaving', 400);
        }
        remove_group_member($pdo, $groupId, $userId);
        json_response(['left' => true]);
    }

    // /api/groups/{id}/members/{userId}
    if (count($rest) === 3 && $rest[1] === 'members') {
        $requireOwner();
        $memberId = (int)$rest[2];
        if ($memberId === $userId) {
            json_error('That\'s you — the owner stays in the group', 400);
        }
        if ($method === 'DELETE') {
            remove_group_member($pdo, $groupId, $memberId);
            json_response(['removed' => true]);
        }
        if ($method === 'PUT' || $method === 'PATCH') {
            set_member_permission($pdo, $groupId, $memberId, (string)(read_body()['permission'] ?? 'edit'));
            json_response(['updated' => true]);
        }
        json_error('Method not allowed', 405);
    }

    json_error('Not found', 404);
}

// ---- /api/groups[/...] ----------------------------------------------
if (($segments[0] ?? null) === 'groups') {
    api_groups($pdo, $method, array_slice($segments, 1), $userId);
}
