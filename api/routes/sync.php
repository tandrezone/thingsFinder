<?php
/**
 * POST /api/sync — phone <-> server sync for one group (see includes/sync.php).
 *
 * Included by api/index.php, which has already set $pdo, $method and
 * $segments, and — for anything but register/login — $userId and $groupId. Every branch ends the request (json_response/json_error exit).
 */

if ($segments === ['sync']) {
    if ($method !== 'POST') {
        json_error('Method not allowed', 405);
    }
    // One group per sync: the one the phone names, or the user's default.
    $body = read_body();
    $syncGroupId = isset($body['group_id']) && is_numeric($body['group_id']) ? (int)$body['group_id'] : default_group_id($pdo, $userId);
    $membership = find_membership($pdo, $syncGroupId, $userId);
    if (!$membership) {
        json_error('You are not a member of that group', 403);
    }
    $result = sync_apply($pdo, $syncGroupId, $body, $membership['permission'] === 'edit');
    $result['group'] = [
        'id' => (int)$membership['id'], 'name' => $membership['name'],
        'permission' => $membership['permission'], 'role' => $membership['role'],
    ];
    json_response($result);
}
