<?php
/**
 * Group scoping for the API: every place/box/item is looked up through the
 * active group, so ids from another group read as "not found".
 */

/** Call before any create/update/delete — view-only shares get a 403. */
function require_write(PDO $pdo): void
{
    require_edit_api($pdo);
}

/** Looks up a place, scoped to the given group — returns null if it's in another group (or doesn't exist). */
function api_find_place(PDO $pdo, int $placeId, int $groupId): ?array
{
    $stmt = $pdo->prepare('SELECT * FROM places WHERE id = ? AND group_id = ?');
    $stmt->execute([$placeId, $groupId]);
    $row = $stmt->fetch();
    return $row ?: null;
}

/** Looks up a box, scoped to the given group via its place — returns null if out of scope or missing. */
function api_find_box(PDO $pdo, int $boxId, int $groupId): ?array
{
    $stmt = $pdo->prepare(
        'SELECT boxes.* FROM boxes JOIN places ON places.id = boxes.place_id
         WHERE boxes.id = ? AND places.group_id = ?'
    );
    $stmt->execute([$boxId, $groupId]);
    $row = $stmt->fetch();
    return $row ?: null;
}

/** Looks up an item, scoped to the given group via its box's place or its own place — returns null if out of scope or missing. */
function api_find_item(PDO $pdo, int $itemId, int $groupId): ?array
{
    $stmt = $pdo->prepare(
        'SELECT items.* FROM items
         LEFT JOIN boxes ON boxes.id = items.box_id
         JOIN places ON places.id = COALESCE(items.place_id, boxes.place_id)
         WHERE items.id = ? AND places.group_id = ?'
    );
    $stmt->execute([$itemId, $groupId]);
    $row = $stmt->fetch();
    return $row ?: null;
}
