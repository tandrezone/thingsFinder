<?php
/**
 * How rows are shaped in API responses — one function per resource.
 */

/** A group as the app sees it; the invite is only shown to members who can edit. */
function group_out(array $g): array
{
    return [
        'id' => (int)$g['id'],
        'name' => $g['name'],
        'role' => $g['role'],
        'permission' => $g['permission'],
        'member_count' => (int)$g['member_count'],
        'invite' => $g['permission'] === 'edit' ? [
            'url' => group_invite_url($g),
            'token' => $g['invite_token'],
            'key' => format_join_key($g['join_key']),
        ] : null,
    ];
}

function place_out(array $p): array
{
    return [
        'id' => (int)$p['id'], 'name' => $p['name'], 'slug' => $p['slug'], 'url' => '/place/' . $p['slug'],
        'add_url' => '/add/' . $p['share_token'], 'remove_url' => '/remove/' . $p['share_token'],
    ];
}

function box_out(array $b, ?array $place = null): array
{
    $out = [
        'id' => (int)$b['id'],
        'place_id' => (int)$b['place_id'],
        'name' => $b['name'],
        'slug' => $b['slug'],
        'view_url' => isset($b['share_token']) ? '/view/' . $b['share_token'] : null,
        'add_url' => isset($b['share_token']) ? '/add/' . $b['share_token'] : null,
        'remove_url' => isset($b['share_token']) ? '/remove/' . $b['share_token'] : null,
    ];
    if ($place) {
        $out['url'] = '/place/' . $place['slug'] . '/' . $b['slug'];
        $out['place'] = ['id' => (int)$place['id'], 'name' => $place['name'], 'slug' => $place['slug']];
    }
    return $out;
}

function item_out(array $i): array
{
    return [
        'id' => (int)$i['id'],
        'box_id' => $i['box_id'] !== null ? (int)$i['box_id'] : null,
        'place_id' => $i['place_id'] !== null ? (int)$i['place_id'] : null,
        'name' => $i['name'],
        'quantity' => (int)($i['quantity'] ?? 1),
    ];
}

function barcode_out(array $b): array
{
    return ['barcode' => $b['barcode'], 'name' => $b['name']];
}
