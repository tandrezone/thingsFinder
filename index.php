<?php
/**
 * UI front controller.
 *
 * Public routes (no login):
 *   GET/POST  /login                          sign in
 *   GET/POST  /setup                          create the first account (only until one exists)
 *   POST      /logout                         sign out
 *   GET       /view/{token}                    read-only box contents — what a box's QR code links to
 *
 * Everything else requires login, and is scoped to whichever account is
 * currently "active" (your own by default, or one that shared with you —
 * see includes/auth.php):
 *   GET/POST  /                              home: list of places, create place
 *   GET       /search?q=...                  search within the active account
 *   GET/POST  /place/{placeSlug}              boxes + place-level items, create/rename/delete
 *   GET/POST  /place/{placeSlug}/{boxSlug}    items inside a box, create/rename/delete
 *   GET/POST  /barcodes                       the barcode -> item register: view, add, rename, remove
 *   POST      /switch-context                 switch which account's stuff you're viewing
 *   GET/POST  /people                         who can access your stuff, and at what permission
 *   GET/POST  /account                        change your own password
 *
 * "Edit" permission is required for every POST above except /switch-context,
 * /login, /setup and /logout; "view" permission is enough for every GET.
 */

require_once __DIR__ . '/includes/db.php';
require_once __DIR__ . '/includes/helpers.php';
require_once __DIR__ . '/includes/qrcode.php';
require_once __DIR__ . '/includes/auth.php';
require_once __DIR__ . '/includes/ocr.php';

$pdo = get_db();
$method = $_SERVER['REQUEST_METHOD'];
$path = current_path();
$segments = path_segments($path);

/** $nav is null for the public /view/{token} page (no account chrome shown); set once login is confirmed. */
function layout(string $title, string $body, array $breadcrumbs = [], ?array $nav = null): void
{
    $flash = flash_take();
    $flashIcon = ['success' => 'check', 'error' => 'alert', 'info' => 'inbox'][$flash['type'] ?? ''] ?? 'inbox';
    ?><!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title><?= h($title) ?> · thingsFinder</title>
<?= favicon_tags() ?>
<link rel="stylesheet" href="/assets/style.css">
</head>
<body>
<header class="topbar">
  <a class="brand" href="/"><span class="brand-mark">📦</span> thingsFinder</a>
  <?php if ($nav): ?>
    <details class="card-menu context-switcher">
      <summary aria-label="Switch account"><?= icon('users', 15) ?><span class="btn-label"><?= h($nav['contextLabel']) ?></span></summary>
      <div class="card-menu-body">
        <?php if ((int)$nav['ownerId'] !== (int)$nav['userId']): ?>
          <form method="post" action="/switch-context" class="inline-form">
            <input type="hidden" name="owner_id" value="<?= (int)$nav['userId'] ?>">
            <button type="submit" class="secondary">My stuff</button>
          </form>
        <?php endif; ?>
        <?php foreach ($nav['shares'] as $s): ?>
          <?php if ((int)$s['owner_id'] === (int)$nav['ownerId']) { continue; } ?>
          <form method="post" action="/switch-context" class="inline-form">
            <input type="hidden" name="owner_id" value="<?= (int)$s['owner_id'] ?>">
            <button type="submit" class="secondary"><?= h($s['owner_username']) ?>'s stuff · <?= h($s['permission']) ?></button>
          </form>
        <?php endforeach; ?>
        <?php if (!$nav['shares']): ?>
          <p class="meta">Nobody has shared their stuff with you yet.</p>
        <?php endif; ?>
      </div>
    </details>
    <a class="btn btn-ghost topbar-link" href="/barcodes"><?= icon('barcode', 15) ?><span class="btn-label">Barcodes</span></a>
  <?php endif; ?>
  <form class="search-form" action="/search" method="get">
    <div class="search-wrap">
      <?= icon('search', 15) ?>
      <input type="search" name="q" placeholder="Search for an item…" value="<?= h($_GET['q'] ?? '') ?>" autocomplete="off">
    </div>
    <button type="submit"><?= icon('search', 15) ?><span class="btn-label">Search</span></button>
  </form>
  <?php if ($nav): ?>
    <details class="card-menu">
      <summary aria-label="Account menu"><?= icon('user', 15) ?><span class="btn-label"><?= h($nav['username']) ?></span></summary>
      <div class="card-menu-body">
        <a class="btn secondary" href="/people"><?= icon('users', 14) ?>People</a>
        <a class="btn secondary" href="/account"><?= icon('lock', 14) ?>Account</a>
        <form method="post" action="/logout">
          <button type="submit" class="danger"><?= icon('log-out', 14) ?>Log out</button>
        </form>
      </div>
    </details>
  <?php endif; ?>
</header>
<main>
<?php if ($breadcrumbs): ?>
  <nav class="breadcrumbs">
    <a href="/">Home</a>
    <?php foreach ($breadcrumbs as $label => $url): ?>
      <?= icon('chevron', 13) ?>
      <?php if ($url): ?><a href="<?= h($url) ?>"><?= h($label) ?></a><?php else: ?><span><?= h($label) ?></span><?php endif; ?>
    <?php endforeach; ?>
  </nav>
<?php endif; ?>
<?php if ($flash): ?>
  <div class="flash flash-<?= h($flash['type']) ?>"><?= icon($flashIcon, 17) ?><span><?= h($flash['message']) ?></span></div>
<?php endif; ?>
<?= $body ?>
</main>
<footer>
  <small>JSON API available under <code>/api</code> (requires login) — e.g. <code>/api/search?q=glue</code></small>
</footer>
<script src="/assets/scan.js" defer></script>
</body>
</html>
<?php
}

/** A stripped-down page shell for the public, no-login /view/{token} page — no search, no account menu, nothing personal. */
function layout_public(string $title, string $body): void
{
    ?><!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title><?= h($title) ?> · thingsFinder</title>
<?= favicon_tags() ?>
<link rel="stylesheet" href="/assets/style.css">
</head>
<body>
<header class="topbar">
  <span class="brand"><span class="brand-mark">📦</span> thingsFinder</span>
  <span class="meta">Shared read-only view</span>
</header>
<main>
<?= $body ?>
</main>
<footer>
  <small>You're viewing a read-only page shared via QR code or link — nothing here can be changed.</small>
</footer>
</body>
</html>
<?php
}

function render_error(int $status, string $message, ?array $nav = null): void
{
    http_response_code($status);
    layout('Error', '<div class="empty-state">' . icon('alert', 28) . '<p>' . h($message) . '</p></div>', [], $nav);
    exit;
}

/**
 * Shared handling for the item-related POST actions. Items can now live
 * directly in a place OR inside a box, so this is called from both the
 * place page and the box page — exactly one of $boxId/$placeId is non-null.
 * Returns true if $action was item-related (caller should stop looking at
 * its own actions and redirect); false otherwise. $ownerId scopes
 * move_item's destination picker to the active account.
 */
function handle_item_action(PDO $pdo, string $action, ?int $boxId, ?int $placeId, int $ownerId): bool
{
    if ($action === 'create_item') {
        $name = trim($_POST['name'] ?? '');
        $barcode = trim($_POST['barcode'] ?? '');
        $quantity = max(1, (int)($_POST['quantity'] ?? 1));
        $known = $barcode !== '' ? find_barcode($pdo, $barcode) : null;
        if ($known) {
            // A recognized barcode always wins — that's the whole point of scanning.
            $name = $known['name'];
        }
        if ($name === '' && $barcode !== '') {
            // Not in our own register — try a free, keyless product lookup
            // before giving up and asking the person to type a name.
            $suggestion = external_barcode_lookup($barcode);
            if ($suggestion) {
                pending_barcode_set($barcode);
                pending_name_set($suggestion['name']);
                flash_set(
                    'Found "' . $suggestion['name'] . '" (via ' . $suggestion['source'] . ') for that barcode — '
                    . 'check the name below and tap Add to save it.',
                    'info'
                );
            } else {
                pending_barcode_set($barcode);
                flash_set('That barcode isn\'t registered yet — type an item name so thingsFinder remembers it.', 'error');
            }
        } elseif ($name === '') {
            flash_set('Item name cannot be empty.', 'error');
        } else {
            $pdo->prepare('INSERT INTO items (box_id, place_id, name, quantity) VALUES (?, ?, ?, ?)')
                ->execute([$boxId, $placeId, $name, $quantity]);
            if ($barcode !== '' && !$known) {
                remember_barcode($pdo, $barcode, $name);
                flash_set('Item "' . $name . '" added — barcode remembered for next time.', 'success');
            } elseif ($known) {
                flash_set('Recognized barcode — added "' . $name . '".', 'success');
            } else {
                flash_set('Item "' . $name . '" added.', 'success');
            }
        }
        return true;
    }
    if ($action === 'rename_item') {
        $id = (int)($_POST['id'] ?? 0);
        $name = trim($_POST['name'] ?? '');
        $quantity = max(1, (int)($_POST['quantity'] ?? 1));
        if ($id && $name !== '') {
            $pdo->prepare('UPDATE items SET name = ?, quantity = ? WHERE id = ?')->execute([$name, $quantity, $id]);
            flash_set('Item updated.', 'success');
        }
        return true;
    }
    if ($action === 'delete_item') {
        $id = (int)($_POST['id'] ?? 0);
        if ($id) {
            $pdo->prepare('DELETE FROM items WHERE id = ?')->execute([$id]);
            flash_set('Item deleted.', 'success');
        }
        return true;
    }
    if ($action === 'move_item') {
        $id = (int)($_POST['id'] ?? 0);
        $destination = (string)($_POST['destination'] ?? '');
        if ($id && move_item_to($pdo, $id, $ownerId, $destination)) {
            flash_set('Item moved.', 'success');
        } else {
            flash_set('Couldn\'t move that item — pick a valid destination.', 'error');
        }
        return true;
    }
    return false;
}

/**
 * Shared handling for the "add items from a photo" POST actions (upload,
 * edit a candidate line, remove a line, discard). scan_add_all — the one
 * action that actually writes items — is handled by the caller since it
 * needs $boxId/$placeId, which this function doesn't take.
 */
function handle_scan_action(string $action, string $containerKey): bool
{
    if ($action === 'scan_upload') {
        if (!isset($_FILES['photo']) || $_FILES['photo']['error'] === UPLOAD_ERR_NO_FILE) {
            flash_set('Choose or take a photo first.', 'error');
            return true;
        }
        if ($_FILES['photo']['error'] !== UPLOAD_ERR_OK) {
            flash_set('That upload didn\'t go through — try again.', 'error');
            return true;
        }
        try {
            $found = ocr_extract_items($_FILES['photo']['tmp_name'], $_FILES['photo']['name']);
        } catch (RuntimeException $e) {
            flash_set($e->getMessage(), 'error');
            return true;
        }
        if (!$found) {
            flash_set('Couldn\'t find any readable text in that photo — try a clearer, well-lit, straight-on shot of the list.', 'error');
            return true;
        }
        $existing = ocr_review_get($containerKey);
        $seen = array_map(fn($i) => mb_strtolower($i['name'], 'UTF-8'), $existing);
        $added = 0;
        foreach ($found as $item) {
            if (!in_array(mb_strtolower($item['name'], 'UTF-8'), $seen, true)) {
                $existing[] = $item;
                $added++;
            }
        }
        ocr_review_set($containerKey, $existing);
        flash_set('Found ' . $added . ' line(s) — review them below, then add what looks right.', 'success');
        return true;
    }
    if ($action === 'scan_update_line') {
        $items = ocr_review_get($containerKey);
        $i = (int)($_POST['index'] ?? -1);
        $name = trim($_POST['name'] ?? '');
        $qty = max(1, (int)($_POST['quantity'] ?? 1));
        if (isset($items[$i]) && $name !== '') {
            $items[$i] = ['name' => $name, 'quantity' => $qty];
            ocr_review_set($containerKey, $items);
            flash_set('Line updated.', 'success');
        }
        return true;
    }
    if ($action === 'scan_remove_line') {
        $items = ocr_review_get($containerKey);
        $i = (int)($_POST['index'] ?? -1);
        if (isset($items[$i])) {
            array_splice($items, $i, 1);
            ocr_review_set($containerKey, $items);
        }
        return true;
    }
    if ($action === 'scan_cancel') {
        ocr_review_clear($containerKey);
        flash_set('Discarded.', 'info');
        return true;
    }
    return false;
}

/**
 * Renders the "Items" section (card list + add-item tile with barcode
 * scanning) shared by the box page and the place page. Management
 * controls (rename/delete/move/add) only appear when $canEdit is true.
 * $moveDestinations is the list_move_destinations() shape (places, each
 * with a nested `boxes` array) used to build each item's move-to picker —
 * pass [] when $canEdit is false, since it's unused then.
 */
function render_items_section(array $items, string $pendingBarcode, string $pendingName, bool $canEdit, array $moveDestinations = []): string
{
    ob_start();
    ?>
    <h2>Items</h2>
    <?php if (!$items): ?>
      <div class="empty-state">
        <?= icon('box', 28) ?>
        <p>No items here yet.</p>
      </div>
    <?php endif; ?>
    <ul class="card-list">
      <?php foreach ($items as $it): ?>
        <li class="card">
          <div class="card-head">
            <div class="card-body">
              <span class="card-title"><?= h($it['name']) ?></span>
              <?php if ((int)$it['quantity'] > 1): ?><span class="qty-badge">×<?= (int)$it['quantity'] ?></span><?php endif; ?>
            </div>
            <?php if ($canEdit): ?>
            <details class="card-menu">
              <summary aria-label="Manage item"><?= icon('dots', 16) ?></summary>
              <div class="card-menu-body">
                <form method="post" class="inline-form">
                  <input type="hidden" name="action" value="rename_item">
                  <input type="hidden" name="id" value="<?= (int)$it['id'] ?>">
                  <input type="text" name="name" value="<?= h($it['name']) ?>" required>
                  <input type="number" name="quantity" value="<?= (int)$it['quantity'] ?>" min="1" class="qty-input" aria-label="Quantity">
                  <button type="submit" class="secondary"><?= icon('edit', 14) ?>Save</button>
                </form>
                <?php if ($moveDestinations): ?>
                <form method="post" class="inline-form">
                  <input type="hidden" name="action" value="move_item">
                  <input type="hidden" name="id" value="<?= (int)$it['id'] ?>">
                  <select name="destination" aria-label="Move to">
                    <?php foreach ($moveDestinations as $place): ?>
                      <optgroup label="<?= h($place['name']) ?>">
                        <option value="place:<?= (int)$place['id'] ?>" <?= ($it['box_id'] === null && (int)$it['place_id'] === (int)$place['id']) ? 'selected' : '' ?>>
                          (loose in <?= h($place['name']) ?>)
                        </option>
                        <?php foreach ($place['boxes'] as $b): ?>
                          <option value="box:<?= (int)$b['id'] ?>" <?= ((int)($it['box_id'] ?? 0) === (int)$b['id']) ? 'selected' : '' ?>>
                            <?= h($b['name']) ?>
                          </option>
                        <?php endforeach; ?>
                      </optgroup>
                    <?php endforeach; ?>
                  </select>
                  <button type="submit" class="secondary"><?= icon('move', 14) ?>Move</button>
                </form>
                <?php endif; ?>
                <form method="post" class="inline-form" onsubmit="return confirm('Delete this item?');">
                  <input type="hidden" name="action" value="delete_item">
                  <input type="hidden" name="id" value="<?= (int)$it['id'] ?>">
                  <button type="submit" class="danger"><?= icon('trash', 14) ?>Delete</button>
                </form>
              </div>
            </details>
            <?php endif; ?>
          </div>
        </li>
      <?php endforeach; ?>
      <?php if ($canEdit): ?>
      <li class="card add-card">
        <details<?= ($pendingBarcode !== '' || $pendingName !== '') ? ' open' : '' ?>>
          <summary><?= icon('plus', 15) ?>Add an item</summary>
          <form method="post" class="add-item-form">
            <input type="hidden" name="action" value="create_item">
            <div class="barcode-row">
              <input type="text" name="barcode" value="<?= h($pendingBarcode) ?>" placeholder="Barcode — scan or type" autocomplete="off" inputmode="numeric">
              <button type="button" class="secondary scan-btn" hidden><?= icon('camera', 14) ?>Scan</button>
            </div>
            <p class="scan-support-note meta" hidden></p>
            <p class="scan-hint meta" hidden></p>
            <div class="name-qty-row">
              <input type="text" name="name" value="<?= h($pendingName) ?>" placeholder="e.g. Hot glue gun" class="name-input">
              <input type="number" name="quantity" value="1" min="1" class="qty-input" title="Quantity" aria-label="Quantity">
            </div>
            <button type="submit">Add</button>
          </form>
          <div class="scanner-overlay" hidden>
            <video playsinline muted></video>
            <button type="button" class="btn btn-ghost scan-cancel">Cancel</button>
          </div>
        </details>
      </li>
      <?php endif; ?>
    </ul>
    <?php if ($canEdit): ?>
    <p class="meta">Know a barcode already? Scan it and thingsFinder either adds the item it remembers, or checks free barcode databases for a name to suggest — confirm or edit it once and it's remembered for next time. Manage all associations on the <a href="/barcodes">barcode register</a>.</p>
    <?php endif; ?>
    <?php
    return ob_get_clean();
}

/**
 * Renders either the "add items from a photo" upload tile, or — while a
 * batch of OCR'd candidate lines is pending — the editable review list for
 * that batch. Returns '' entirely when $canEdit is false.
 */
function render_photo_scan_section(string $containerKey, array $reviewItems, bool $canEdit): string
{
    if (!$canEdit) {
        return '';
    }
    ob_start();
    if ($reviewItems) {
        ?>
        <h2>Review items from photo</h2>
        <p class="meta">Edit a line and tap Save, or remove it — then add whatever's left.</p>
        <ul class="scan-review-list">
          <?php foreach ($reviewItems as $i => $ri): ?>
            <li class="card">
              <div class="card-head">
                <form method="post" class="inline-form">
                  <input type="hidden" name="action" value="scan_update_line">
                  <input type="hidden" name="index" value="<?= (int)$i ?>">
                  <input type="text" name="name" value="<?= h($ri['name']) ?>" required class="name-input">
                  <input type="number" name="quantity" value="<?= (int)$ri['quantity'] ?>" min="1" class="qty-input" aria-label="Quantity">
                  <button type="submit" class="secondary"><?= icon('check', 14) ?>Save</button>
                </form>
                <form method="post" class="inline-form" onsubmit="return confirm('Remove this line?');">
                  <input type="hidden" name="action" value="scan_remove_line">
                  <input type="hidden" name="index" value="<?= (int)$i ?>">
                  <button type="submit" class="danger" aria-label="Remove"><?= icon('trash', 14) ?></button>
                </form>
              </div>
            </li>
          <?php endforeach; ?>
        </ul>
        <div class="row-actions">
          <form method="post" class="inline-form">
            <input type="hidden" name="action" value="scan_add_all">
            <button type="submit"><?= icon('check', 14) ?>Add <?= count($reviewItems) ?> item<?= count($reviewItems) === 1 ? '' : 's' ?></button>
          </form>
          <form method="post" class="inline-form">
            <input type="hidden" name="action" value="scan_cancel">
            <button type="submit" class="btn-ghost">Discard all</button>
          </form>
        </div>
        <?php
    } else {
        ?>
        <div class="card add-card photo-scan-card">
          <details>
            <summary><?= icon('camera', 15) ?>Add items from a photo</summary>
            <?php if (!ocr_available()): ?>
              <p class="meta scan-support-note">OCR isn't set up on this server — it needs the <code>tesseract</code> command-line program installed and PHP's <code>shell_exec()</code> enabled. See the README for install instructions.</p>
            <?php else: ?>
              <form method="post" enctype="multipart/form-data">
                <input type="hidden" name="action" value="scan_upload">
                <p class="meta">Take or upload a photo of a printed/handwritten list (not a photo of the items themselves) — thingsFinder reads the text and lets you review it before anything is added.</p>
                <input type="file" name="photo" accept="image/*" capture="environment" required>
                <button type="submit"><?= icon('camera', 14) ?>Extract items</button>
              </form>
            <?php endif; ?>
          </details>
        </div>
        <?php
    }
    return ob_get_clean();
}

// -------------------------------------------------------------------------
// Route: /view/{token} — public, read-only, no login. What a box's QR
// code / printable label links to.
// -------------------------------------------------------------------------
if (count($segments) === 2 && $segments[0] === 'view') {
    $box = find_box_by_token($pdo, $segments[1]);
    if (!$box) {
        http_response_code(404);
        layout_public('Not found', '<div class="empty-state">' . icon('alert', 28) . '<p>This link is invalid or no longer exists.</p></div>');
        exit;
    }
    $stmt = $pdo->prepare('SELECT * FROM places WHERE id = ?');
    $stmt->execute([(int)$box['place_id']]);
    $place = $stmt->fetch();

    $stmt = $pdo->prepare('SELECT * FROM items WHERE box_id = ? ORDER BY name COLLATE NOCASE');
    $stmt->execute([(int)$box['id']]);
    $items = $stmt->fetchAll();

    ob_start();
    ?>
    <h1><?= h($box['name']) ?></h1>
    <p class="meta">In <?= h($place['name'] ?? 'a place') ?> · read-only</p>
    <?php if (!$items): ?>
      <div class="empty-state">
        <?= icon('box', 28) ?>
        <p>This box is empty.</p>
      </div>
    <?php else: ?>
      <ul class="card-list">
        <?php foreach ($items as $it): ?>
          <li class="card">
            <div class="card-body">
              <span class="card-title"><?= h($it['name']) ?></span>
              <?php if ((int)$it['quantity'] > 1): ?><span class="qty-badge">×<?= (int)$it['quantity'] ?></span><?php endif; ?>
            </div>
          </li>
        <?php endforeach; ?>
      </ul>
    <?php endif; ?>
    <?php
    layout_public($box['name'], ob_get_clean());
    exit;
}

// -------------------------------------------------------------------------
// Route: /setup — create the first account. Only usable before any
// account exists; once one does, this always bounces to /login.
// -------------------------------------------------------------------------
if ($segments === ['setup']) {
    if (has_any_users($pdo)) {
        redirect('/login');
    }
    $error = '';
    if ($method === 'POST') {
        $username = trim($_POST['username'] ?? '');
        $password = (string)($_POST['password'] ?? '');
        $confirm = (string)($_POST['password_confirm'] ?? '');
        if ($username === '' || $password === '') {
            $error = 'Choose a username and password.';
        } elseif ($password !== $confirm) {
            $error = 'Passwords don\'t match.';
        } elseif (strlen($password) < 8) {
            $error = 'Password must be at least 8 characters.';
        } else {
            $userId = create_user($pdo, $username, $password);
            adopt_orphan_places($pdo, $userId); // upgrading from a version with no login: your existing places become yours
            login_user($userId);
            flash_set('Welcome to thingsFinder!', 'success');
            redirect('/');
        }
    }
    ob_start();
    ?>
    <div class="auth-card">
      <h1>Set up thingsFinder</h1>
      <p class="page-subtitle">Create the first account. You can invite others to see or edit your stuff later, from People.</p>
      <?php if ($error): ?><p class="flash flash-error"><?= icon('alert', 16) ?><span><?= h($error) ?></span></p><?php endif; ?>
      <form method="post" class="stack-form-v">
        <label>Username<input type="text" name="username" value="<?= h($_POST['username'] ?? '') ?>" autofocus required></label>
        <label>Password<input type="password" name="password" required minlength="8"></label>
        <label>Confirm password<input type="password" name="password_confirm" required minlength="8"></label>
        <button type="submit">Create account</button>
      </form>
    </div>
    <?php
    layout_public('Set up', ob_get_clean());
    exit;
}

// -------------------------------------------------------------------------
// Route: /login
// -------------------------------------------------------------------------
if ($segments === ['login']) {
    if (!has_any_users($pdo)) {
        redirect('/setup');
    }
    if (current_user_id() !== null) {
        redirect('/');
    }
    $error = '';
    if ($method === 'POST') {
        $username = trim($_POST['username'] ?? '');
        $password = (string)($_POST['password'] ?? '');
        $user = find_user_by_username($pdo, $username);
        if ($user && password_verify($password, $user['password_hash'])) {
            login_user((int)$user['id']);
            $next = (string)($_POST['next'] ?? '/');
            redirect($next !== '' && $next[0] === '/' ? $next : '/');
        }
        $error = 'Wrong username or password.';
    }
    $next = (string)($_GET['next'] ?? '');
    ob_start();
    ?>
    <div class="auth-card">
      <h1>Log in</h1>
      <?php if ($error): ?><p class="flash flash-error"><?= icon('alert', 16) ?><span><?= h($error) ?></span></p><?php endif; ?>
      <form method="post" class="stack-form-v">
        <input type="hidden" name="next" value="<?= h($next) ?>">
        <label>Username<input type="text" name="username" value="<?= h($_POST['username'] ?? '') ?>" autofocus required></label>
        <label>Password<input type="password" name="password" required></label>
        <button type="submit">Log in</button>
      </form>
    </div>
    <?php
    layout_public('Log in', ob_get_clean());
    exit;
}

// -------------------------------------------------------------------------
// Route: /logout
// -------------------------------------------------------------------------
if ($segments === ['logout']) {
    if ($method === 'POST') {
        logout_user();
    }
    redirect('/login');
}

// -------------------------------------------------------------------------
// Everything below requires a logged-in session.
// -------------------------------------------------------------------------
if (!has_any_users($pdo)) {
    redirect('/setup');
}
require_login();

$currentUser = current_user($pdo);
if (!$currentUser) {
    // Session pointed at a user that no longer exists (deleted account).
    logout_user();
    redirect('/login');
}
$ownerId = active_owner_id($pdo);
$owner = ((int)$ownerId === (int)$currentUser['id']) ? $currentUser : find_user_by_id($pdo, $ownerId);
$canEdit = can_edit($pdo);
$sharesReceived = list_shares_received_by($pdo, (int)$currentUser['id']);
$nav = [
    'userId' => (int)$currentUser['id'],
    'username' => $currentUser['username'],
    'ownerId' => (int)$ownerId,
    'contextLabel' => ((int)$ownerId === (int)$currentUser['id'])
        ? 'My stuff'
        : ($owner['username'] ?? 'Shared') . "'s stuff",
    'shares' => $sharesReceived,
];

// -------------------------------------------------------------------------
// Route: /switch-context
// -------------------------------------------------------------------------
if ($segments === ['switch-context']) {
    if ($method === 'POST') {
        $requested = (int)($_POST['owner_id'] ?? 0);
        if ($requested && set_active_owner($pdo, $requested)) {
            flash_set('Switched.', 'success');
        } else {
            flash_set('You don\'t have access to that account.', 'error');
        }
    }
    $back = (string)($_POST['next'] ?? '/');
    redirect($back !== '' && $back[0] === '/' ? $back : '/');
}

// -------------------------------------------------------------------------
// Route: /people — who can access *your own* stuff (not the account
// you're currently viewing — sharing is always about what you own).
// -------------------------------------------------------------------------
if ($segments === ['people']) {
    $myId = (int)$currentUser['id'];
    if ($method === 'POST') {
        $action = $_POST['action'] ?? '';
        if ($action === 'add_person') {
            $username = trim($_POST['username'] ?? '');
            $password = (string)($_POST['password'] ?? '');
            $permission = ($_POST['permission'] ?? 'view') === 'edit' ? 'edit' : 'view';
            $existing = $username !== '' ? find_user_by_username($pdo, $username) : null;
            if ($username === '') {
                flash_set('Enter a username.', 'error');
            } elseif ($existing && (int)$existing['id'] === $myId) {
                flash_set('That\'s your own account.', 'error');
            } elseif ($existing) {
                upsert_share($pdo, $myId, (int)$existing['id'], $permission);
                flash_set('Granted "' . $existing['username'] . '" ' . $permission . ' access.', 'success');
            } elseif ($password === '' || strlen($password) < 8) {
                flash_set('That username doesn\'t exist yet — set a password (8+ characters) to create it.', 'error');
            } else {
                $newId = create_user($pdo, $username, $password);
                upsert_share($pdo, $myId, $newId, $permission);
                flash_set('Created "' . $username . '" with ' . $permission . ' access. Share their username/password with them.', 'success');
            }
        } elseif ($action === 'update_permission') {
            $userId = (int)($_POST['user_id'] ?? 0);
            $permission = ($_POST['permission'] ?? 'view') === 'edit' ? 'edit' : 'view';
            if ($userId) {
                upsert_share($pdo, $myId, $userId, $permission);
                flash_set('Updated.', 'success');
            }
        } elseif ($action === 'revoke') {
            $userId = (int)($_POST['user_id'] ?? 0);
            if ($userId) {
                delete_share($pdo, $myId, $userId);
                flash_set('Access removed.', 'success');
            }
        }
        redirect('/people');
    }

    $granted = list_shares_granted_by($pdo, $myId);

    ob_start();
    ?>
    <h1>People</h1>
    <p class="page-subtitle">Everyone listed here can see everything you own; "edit" means they can add, rename, and delete too.</p>
    <?php if (!$granted): ?>
      <div class="empty-state">
        <?= icon('users', 28) ?>
        <p>Nobody else has access to your stuff yet.</p>
      </div>
    <?php endif; ?>
    <ul class="card-list">
      <?php foreach ($granted as $s): ?>
        <li class="card">
          <div class="card-head">
            <div class="card-body">
              <span class="card-title"><?= h($s['username']) ?></span>
              <span class="meta"><?= h($s['permission']) ?> access</span>
            </div>
            <details class="card-menu">
              <summary aria-label="Manage access"><?= icon('dots', 16) ?></summary>
              <div class="card-menu-body">
                <form method="post" class="inline-form">
                  <input type="hidden" name="action" value="update_permission">
                  <input type="hidden" name="user_id" value="<?= (int)$s['user_id'] ?>">
                  <select name="permission">
                    <option value="view" <?= $s['permission'] === 'view' ? 'selected' : '' ?>>View only</option>
                    <option value="edit" <?= $s['permission'] === 'edit' ? 'selected' : '' ?>>Can edit</option>
                  </select>
                  <button type="submit" class="secondary"><?= icon('check', 14) ?>Save</button>
                </form>
                <form method="post" class="inline-form" onsubmit="return confirm('Remove this person\'s access?');">
                  <input type="hidden" name="action" value="revoke">
                  <input type="hidden" name="user_id" value="<?= (int)$s['user_id'] ?>">
                  <button type="submit" class="danger"><?= icon('trash', 14) ?>Remove</button>
                </form>
              </div>
            </details>
          </div>
        </li>
      <?php endforeach; ?>
      <li class="card add-card">
        <details>
          <summary><?= icon('plus', 15) ?>Add a person</summary>
          <form method="post" class="stack-form-v">
            <input type="hidden" name="action" value="add_person">
            <label>Username<input type="text" name="username" placeholder="e.g. their name" required></label>
            <label>Password <span class="meta">(only needed if this username doesn't exist yet)</span><input type="password" name="password" placeholder="Leave blank for an existing account" minlength="8"></label>
            <label>Permission
              <select name="permission">
                <option value="view">View only</option>
                <option value="edit">Can edit</option>
              </select>
            </label>
            <button type="submit">Add</button>
          </form>
        </details>
      </li>
    </ul>
    <?php
    layout('People', ob_get_clean(), ['People' => null], $nav);
    exit;
}

// -------------------------------------------------------------------------
// Route: /account — change your own password
// -------------------------------------------------------------------------
if ($segments === ['account']) {
    if ($method === 'POST') {
        $current = (string)($_POST['current_password'] ?? '');
        $new = (string)($_POST['new_password'] ?? '');
        $confirm = (string)($_POST['new_password_confirm'] ?? '');
        if (!password_verify($current, $currentUser['password_hash'])) {
            flash_set('Current password is wrong.', 'error');
        } elseif (strlen($new) < 8) {
            flash_set('New password must be at least 8 characters.', 'error');
        } elseif ($new !== $confirm) {
            flash_set('New passwords don\'t match.', 'error');
        } else {
            $pdo->prepare('UPDATE users SET password_hash = ? WHERE id = ?')
                ->execute([password_hash($new, PASSWORD_DEFAULT), (int)$currentUser['id']]);
            flash_set('Password updated.', 'success');
        }
        redirect('/account');
    }
    ob_start();
    ?>
    <h1>Account</h1>
    <p class="page-subtitle">Signed in as <strong><?= h($currentUser['username']) ?></strong>.</p>
    <form method="post" class="stack-form-v">
      <label>Current password<input type="password" name="current_password" required></label>
      <label>New password<input type="password" name="new_password" required minlength="8"></label>
      <label>Confirm new password<input type="password" name="new_password_confirm" required minlength="8"></label>
      <button type="submit">Change password</button>
    </form>
    <?php
    layout('Account', ob_get_clean(), ['Account' => null], $nav);
    exit;
}

// -------------------------------------------------------------------------
// Route: home
// -------------------------------------------------------------------------
if ($segments === []) {
    if ($method === 'POST') {
        require_edit($pdo);
        $action = $_POST['action'] ?? '';
        if ($action === 'create_place') {
            $name = trim($_POST['name'] ?? '');
            if ($name !== '') {
                $slug = unique_place_slug($pdo, $ownerId, $name);
                $pdo->prepare('INSERT INTO places (owner_id, name, slug) VALUES (?, ?, ?)')->execute([$ownerId, $name, $slug]);
                flash_set('Place "' . $name . '" created.', 'success');
            } else {
                flash_set('Place name cannot be empty.', 'error');
            }
        } elseif ($action === 'rename_place') {
            $id = (int)($_POST['id'] ?? 0);
            $name = trim($_POST['name'] ?? '');
            if ($id && $name !== '') {
                $slug = unique_place_slug($pdo, $ownerId, $name, $id);
                $pdo->prepare('UPDATE places SET name = ?, slug = ? WHERE id = ? AND owner_id = ?')->execute([$name, $slug, $id, $ownerId]);
                flash_set('Place renamed.', 'success');
            }
        } elseif ($action === 'delete_place') {
            $id = (int)($_POST['id'] ?? 0);
            if ($id) {
                $pdo->prepare('DELETE FROM places WHERE id = ? AND owner_id = ?')->execute([$id, $ownerId]);
                flash_set('Place deleted.', 'success');
            }
        }
        redirect('/');
    }

    $stmt = $pdo->prepare(
        "SELECT places.*,
                (SELECT COUNT(*) FROM boxes WHERE boxes.place_id = places.id) AS box_count,
                (SELECT COUNT(*) FROM items JOIN boxes ON boxes.id = items.box_id WHERE boxes.place_id = places.id)
                    + (SELECT COUNT(*) FROM items WHERE items.place_id = places.id) AS item_count
         FROM places WHERE owner_id = ? ORDER BY name COLLATE NOCASE"
    );
    $stmt->execute([$ownerId]);
    $places = $stmt->fetchAll();

    ob_start();
    ?>
    <h1>Places</h1>
    <p class="page-subtitle">Everything <?= (int)$ownerId === (int)$currentUser['id'] ? 'you own' : h($owner['username']) . ' owns' ?>, findable in seconds.</p>
    <?php if (!$places): ?>
      <div class="empty-state">
        <?= icon('inbox', 28) ?>
        <p>No places yet.</p>
        <?php if ($canEdit): ?><p class="empty-hint">Add your first place below (e.g. "Garage", "Attic", "Kitchen").</p><?php endif; ?>
      </div>
    <?php endif; ?>
    <ul class="card-list">
      <?php foreach ($places as $p): ?>
        <li class="card">
          <div class="card-head">
            <div class="card-body">
              <a class="card-title" href="/place/<?= h($p['slug']) ?>"><?= h($p['name']) ?></a>
              <span class="meta"><?= (int)$p['box_count'] ?> box(es) · <?= (int)$p['item_count'] ?> item(s)</span>
            </div>
            <?php if ($canEdit): ?>
            <details class="card-menu">
              <summary aria-label="Manage place"><?= icon('dots', 16) ?></summary>
              <div class="card-menu-body">
                <form method="post" class="inline-form">
                  <input type="hidden" name="action" value="rename_place">
                  <input type="hidden" name="id" value="<?= (int)$p['id'] ?>">
                  <input type="text" name="name" value="<?= h($p['name']) ?>" required>
                  <button type="submit" class="secondary"><?= icon('edit', 14) ?>Rename</button>
                </form>
                <form method="post" class="inline-form" onsubmit="return confirm('Delete this place and everything inside it?');">
                  <input type="hidden" name="action" value="delete_place">
                  <input type="hidden" name="id" value="<?= (int)$p['id'] ?>">
                  <button type="submit" class="danger"><?= icon('trash', 14) ?>Delete</button>
                </form>
              </div>
            </details>
            <?php endif; ?>
          </div>
        </li>
      <?php endforeach; ?>
      <?php if ($canEdit): ?>
      <li class="card add-card">
        <details>
          <summary><?= icon('plus', 15) ?>Add a place</summary>
          <form method="post">
            <input type="hidden" name="action" value="create_place">
            <input type="text" name="name" placeholder="e.g. Garage" required>
            <button type="submit">Add</button>
          </form>
        </details>
      </li>
      <?php endif; ?>
    </ul>
    <?php
    layout('Places', ob_get_clean(), [], $nav);
    exit;
}

// -------------------------------------------------------------------------
// Route: /search
// -------------------------------------------------------------------------
if ($segments === ['search']) {
    $q = trim($_GET['q'] ?? '');
    $results = [];
    if ($q !== '') {
        $like = '%' . str_replace(['\\', '%', '_'], ['\\\\', '\\%', '\\_'], $q) . '%';
        $stmt = $pdo->prepare(
            "SELECT items.id, items.name AS item_name, items.quantity,
                    boxes.name AS box_name, boxes.slug AS box_slug,
                    places.name AS place_name, places.slug AS place_slug
             FROM items
             LEFT JOIN boxes ON boxes.id = items.box_id
             JOIN places ON places.id = COALESCE(items.place_id, boxes.place_id)
             WHERE places.owner_id = ? AND items.name LIKE ? ESCAPE '\\'
             ORDER BY items.name COLLATE NOCASE"
        );
        $stmt->execute([$ownerId, $like]);
        $results = $stmt->fetchAll();
    }

    ob_start();
    ?>
    <h1>Search</h1>
    <form method="get" class="stack-form">
      <div class="search-wrap">
        <?= icon('search', 15) ?>
        <input type="search" name="q" value="<?= h($q) ?>" placeholder="e.g. glue" autofocus>
      </div>
      <button type="submit">Search</button>
    </form>
    <?php if ($q === ''): ?>
      <div class="empty-state">
        <?= icon('search', 28) ?>
        <p>Type something above to search across every place and box.</p>
      </div>
    <?php elseif (!$results): ?>
      <div class="empty-state">
        <?= icon('inbox', 28) ?>
        <p>No items matching "<?= h($q) ?>" were found.</p>
      </div>
    <?php else: ?>
      <p class="meta"><?= count($results) ?> result(s) for "<?= h($q) ?>"</p>
      <ul class="card-list">
        <?php foreach ($results as $r): ?>
          <li class="card">
            <div class="card-body">
              <span class="card-title"><?= h($r['item_name']) ?><?php if ((int)$r['quantity'] > 1): ?> <span class="qty-badge">×<?= (int)$r['quantity'] ?></span><?php endif; ?></span>
              <span class="meta">in
                <a href="/place/<?= h($r['place_slug']) ?>"><?= h($r['place_name']) ?></a>
                <?php if ($r['box_name'] !== null): ?>
                  <?= icon('chevron', 12) ?>
                  <a href="/place/<?= h($r['place_slug']) ?>/<?= h($r['box_slug']) ?>"><?= h($r['box_name']) ?></a>
                <?php endif; ?>
              </span>
            </div>
          </li>
        <?php endforeach; ?>
      </ul>
    <?php endif; ?>
    <?php
    layout('Search', ob_get_clean(), [], $nav);
    exit;
}

// -------------------------------------------------------------------------
// Route: /place/{placeSlug}
// -------------------------------------------------------------------------
if (count($segments) >= 2 && $segments[0] === 'place') {
    $placeSlug = $segments[1];
    $place = find_place_by_slug($pdo, $ownerId, $placeSlug);
    if (!$place) {
        render_error(404, 'No place found for "' . $placeSlug . '".', $nav);
    }
    $placeId = (int)$place['id'];

    // ---------------------------------------------------------------
    // Route: /place/{placeSlug}/{boxSlug}/qr.svg | qr.png
    //   Downloadable QR code for the box, encoding the public read-only
    //   /view/{token} page.
    // ---------------------------------------------------------------
    if (count($segments) === 4 && in_array($segments[3], ['qr.svg', 'qr.png'], true)) {
        $boxSlug = $segments[2];
        $box = find_box_by_slug($pdo, $placeId, $boxSlug);
        if (!$box) {
            render_error(404, 'No box "' . $boxSlug . '" found in "' . $place['name'] . '".', $nav);
        }
        $viewUrl = base_url() . '/view/' . $box['share_token'];
        if ($segments[3] === 'qr.png') {
            qrcode_send_png($viewUrl);
        } else {
            qrcode_send_svg($viewUrl);
        }
        exit;
    }

    // ---------------------------------------------------------------
    // Route: /place/{placeSlug}/{boxSlug}/label.svg | label.png
    //   A printable label: QR code + box name + place name + border +
    //   icons, sized for a label printer. Query params (all optional):
    //     ?w=50&h=30     label size in millimeters (default 50x30)
    //     ?dpi=300       PNG resolution only (default 300)
    // ---------------------------------------------------------------
    if (count($segments) === 4 && in_array($segments[3], ['label.svg', 'label.png'], true)) {
        $boxSlug = $segments[2];
        $box = find_box_by_slug($pdo, $placeId, $boxSlug);
        if (!$box) {
            render_error(404, 'No box "' . $boxSlug . '" found in "' . $place['name'] . '".', $nav);
        }
        require_once __DIR__ . '/includes/label.php';
        $viewUrl = base_url() . '/view/' . $box['share_token'];
        $widthMm = isset($_GET['w']) ? (float)$_GET['w'] : 50.0;
        $heightMm = isset($_GET['h']) ? (float)$_GET['h'] : 30.0;
        $dpi = isset($_GET['dpi']) ? (int)$_GET['dpi'] : 300;
        if ($segments[3] === 'label.png') {
            label_send_png($viewUrl, $box['name'], $place['name'], $widthMm, $heightMm, $dpi);
        } else {
            label_send_svg($viewUrl, $box['name'], $place['name'], $widthMm, $heightMm);
        }
        exit;
    }

    // ---------------------------------------------------------------
    // Route: /place/{placeSlug}/{boxSlug}
    // ---------------------------------------------------------------
    if (count($segments) === 3) {
        $boxSlug = $segments[2];
        $box = find_box_by_slug($pdo, $placeId, $boxSlug);
        if (!$box) {
            render_error(404, 'No box "' . $boxSlug . '" found in "' . $place['name'] . '".', $nav);
        }
        $boxId = (int)$box['id'];
        $viewUrl = base_url() . '/view/' . $box['share_token'];
        $scanKey = 'box:' . $boxId;

        if ($method === 'POST') {
            require_edit($pdo);
            $action = $_POST['action'] ?? '';
            if (handle_item_action($pdo, $action, $boxId, null, $ownerId)) {
                // handled — falls through to the redirect below
            } elseif (handle_scan_action($action, $scanKey)) {
                // handled — falls through to the redirect below
            } elseif ($action === 'scan_add_all') {
                $reviewItems = ocr_review_get($scanKey);
                foreach ($reviewItems as $ri) {
                    $pdo->prepare('INSERT INTO items (box_id, name, quantity) VALUES (?, ?, ?)')
                        ->execute([$boxId, $ri['name'], $ri['quantity']]);
                }
                ocr_review_clear($scanKey);
                flash_set(count($reviewItems) . ' item(s) added.', 'success');
            } elseif ($action === 'rename_box') {
                $name = trim($_POST['name'] ?? '');
                if ($name !== '') {
                    $slug = unique_box_slug($pdo, $placeId, $name, $boxId);
                    $pdo->prepare('UPDATE boxes SET name = ?, slug = ? WHERE id = ?')->execute([$name, $slug, $boxId]);
                    flash_set('Box renamed.', 'success');
                    redirect('/place/' . $placeSlug . '/' . $slug);
                }
            } elseif ($action === 'move_box') {
                $newPlaceId = (int)($_POST['place_id'] ?? 0);
                $newSlug = move_box_to($pdo, $boxId, $ownerId, $newPlaceId);
                if ($newSlug !== null) {
                    $newPlaceStmt = $pdo->prepare('SELECT slug FROM places WHERE id = ?');
                    $newPlaceStmt->execute([$newPlaceId]);
                    $newPlaceSlug = $newPlaceStmt->fetchColumn();
                    flash_set('Box moved.', 'success');
                    redirect('/place/' . $newPlaceSlug . '/' . $newSlug);
                }
                flash_set('Couldn\'t move that box — pick a valid place.', 'error');
            } elseif ($action === 'delete_box') {
                $pdo->prepare('DELETE FROM boxes WHERE id = ?')->execute([$boxId]);
                flash_set('Box deleted.', 'success');
                redirect('/place/' . $placeSlug);
            }
            redirect('/place/' . $placeSlug . '/' . $boxSlug);
        }

        $stmt = $pdo->prepare('SELECT * FROM items WHERE box_id = ? ORDER BY name COLLATE NOCASE');
        $stmt->execute([$boxId]);
        $items = $stmt->fetchAll();
        $pendingBarcode = pending_barcode_take();
        $pendingName = pending_name_take();
        $reviewItems = ocr_review_get($scanKey);
        $moveDestinations = $canEdit ? list_move_destinations($pdo, $ownerId) : [];

        ob_start();
        ?>
        <div class="card-head">
          <div class="card-body">
            <h1><?= h($box['name']) ?></h1>
            <p class="meta">Box in <a href="/place/<?= h($placeSlug) ?>"><?= h($place['name']) ?></a> · permalink <code>/place/<?= h($placeSlug) ?>/<?= h($box['slug']) ?></code></p>
          </div>
          <?php if ($canEdit): ?>
          <details class="card-menu">
            <summary aria-label="Manage box"><?= icon('dots', 18) ?></summary>
            <div class="card-menu-body">
              <form method="post" class="inline-form">
                <input type="hidden" name="action" value="rename_box">
                <input type="text" name="name" value="<?= h($box['name']) ?>" required>
                <button type="submit" class="secondary"><?= icon('edit', 14) ?>Rename</button>
              </form>
              <?php if (count($moveDestinations) > 1): ?>
              <form method="post" class="inline-form">
                <input type="hidden" name="action" value="move_box">
                <select name="place_id" aria-label="Move to place">
                  <?php foreach ($moveDestinations as $p): ?>
                    <option value="<?= (int)$p['id'] ?>" <?= ((int)$p['id'] === $placeId) ? 'selected' : '' ?>><?= h($p['name']) ?></option>
                  <?php endforeach; ?>
                </select>
                <button type="submit" class="secondary"><?= icon('move', 14) ?>Move</button>
              </form>
              <?php endif; ?>
              <form method="post" class="inline-form" onsubmit="return confirm('Delete this box and all its items?');">
                <input type="hidden" name="action" value="delete_box">
                <button type="submit" class="danger"><?= icon('trash', 14) ?>Delete box</button>
              </form>
            </div>
          </details>
          <?php endif; ?>
        </div>

        <div class="qr-block">
          <div class="qr-image"><?= qrcode_svg_markup($viewUrl, 4) ?></div>
          <div class="qr-info">
            <p class="meta">Scan this to see the box's contents — a read-only page, no login needed, handy for a printed label on the box itself.</p>
            <p class="meta"><code><?= h($viewUrl) ?></code></p>
            <div class="row-actions">
              <a class="btn secondary" href="/place/<?= h($placeSlug) ?>/<?= h($box['slug']) ?>/qr.svg" download><?= icon('download', 14) ?>SVG</a>
              <a class="btn secondary" href="/place/<?= h($placeSlug) ?>/<?= h($box['slug']) ?>/qr.png" download><?= icon('download', 14) ?>PNG</a>
              <a class="btn secondary" href="/api/boxes/<?= $boxId ?>/contents" target="_blank" rel="noopener"><?= icon('external', 14) ?>Open JSON</a>
            </div>
          </div>
        </div>

        <h2>Printable label</h2>
        <div class="label-block">
          <div class="label-preview">
            <img src="/place/<?= h($placeSlug) ?>/<?= h($box['slug']) ?>/label.svg" alt="Printable label for <?= h($box['name']) ?>" width="300" height="180">
          </div>
          <div class="label-info">
            <p class="meta">A ready-to-print label: QR code, box name, place name, a border, and a couple of icons. Default size is 50×30mm — add <code>?w=</code>/<code>?h=</code> (millimeters) to the link for a different label size, or <code>?dpi=</code> for the PNG's resolution.</p>
            <div class="row-actions">
              <a class="btn secondary" href="/place/<?= h($placeSlug) ?>/<?= h($box['slug']) ?>/label.svg" download><?= icon('download', 14) ?>SVG</a>
              <a class="btn secondary" href="/place/<?= h($placeSlug) ?>/<?= h($box['slug']) ?>/label.png" download><?= icon('download', 14) ?>PNG</a>
            </div>
          </div>
        </div>

        <?= render_photo_scan_section($scanKey, $reviewItems, $canEdit) ?>
        <?= render_items_section($items, $pendingBarcode, $pendingName, $canEdit, $moveDestinations) ?>
        <?php
        layout($box['name'], ob_get_clean(), [$place['name'] => '/place/' . $placeSlug, $box['name'] => null], $nav);
        exit;
    }

    // ---------------------------------------------------------------
    // Route: /place/{placeSlug}  (list of boxes)
    // ---------------------------------------------------------------
    $scanKey = 'place:' . $placeId;

    if ($method === 'POST') {
        require_edit($pdo);
        $action = $_POST['action'] ?? '';
        if (handle_item_action($pdo, $action, null, $placeId, $ownerId)) {
            // handled — falls through to the redirect below
        } elseif (handle_scan_action($action, $scanKey)) {
            // handled — falls through to the redirect below
        } elseif ($action === 'scan_add_all') {
            $reviewItems = ocr_review_get($scanKey);
            foreach ($reviewItems as $ri) {
                $pdo->prepare('INSERT INTO items (place_id, name, quantity) VALUES (?, ?, ?)')
                    ->execute([$placeId, $ri['name'], $ri['quantity']]);
            }
            ocr_review_clear($scanKey);
            flash_set(count($reviewItems) . ' item(s) added.', 'success');
        } elseif ($action === 'create_box') {
            $name = trim($_POST['name'] ?? '');
            if ($name !== '') {
                $slug = unique_box_slug($pdo, $placeId, $name);
                $pdo->prepare('INSERT INTO boxes (place_id, name, slug, share_token) VALUES (?, ?, ?, ?)')
                    ->execute([$placeId, $name, $slug, new_share_token()]);
                flash_set('Box "' . $name . '" created.', 'success');
            }
        } elseif ($action === 'rename_box') {
            $id = (int)($_POST['id'] ?? 0);
            $name = trim($_POST['name'] ?? '');
            if ($id && $name !== '') {
                $slug = unique_box_slug($pdo, $placeId, $name, $id);
                $pdo->prepare('UPDATE boxes SET name = ?, slug = ? WHERE id = ?')->execute([$name, $slug, $id]);
                flash_set('Box renamed.', 'success');
            }
        } elseif ($action === 'move_box') {
            $id = (int)($_POST['id'] ?? 0);
            $newPlaceId = (int)($_POST['place_id'] ?? 0);
            if ($id) {
                if (move_box_to($pdo, $id, $ownerId, $newPlaceId) !== null) {
                    flash_set('Box moved.', 'success');
                } else {
                    flash_set('Couldn\'t move that box — pick a valid place.', 'error');
                }
            }
        } elseif ($action === 'delete_box') {
            $id = (int)($_POST['id'] ?? 0);
            if ($id) {
                $pdo->prepare('DELETE FROM boxes WHERE id = ?')->execute([$id]);
                flash_set('Box deleted.', 'success');
            }
        } elseif ($action === 'rename_place') {
            $name = trim($_POST['name'] ?? '');
            if ($name !== '') {
                $slug = unique_place_slug($pdo, $ownerId, $name, $placeId);
                $pdo->prepare('UPDATE places SET name = ?, slug = ? WHERE id = ?')->execute([$name, $slug, $placeId]);
                flash_set('Place renamed.', 'success');
                redirect('/place/' . $slug);
            }
        } elseif ($action === 'delete_place') {
            $pdo->prepare('DELETE FROM places WHERE id = ?')->execute([$placeId]);
            flash_set('Place deleted.', 'success');
            redirect('/');
        }
        redirect('/place/' . $placeSlug);
    }

    $stmt = $pdo->prepare(
        "SELECT boxes.*, (SELECT COUNT(*) FROM items WHERE items.box_id = boxes.id) AS item_count
         FROM boxes WHERE place_id = ? ORDER BY name COLLATE NOCASE"
    );
    $stmt->execute([$placeId]);
    $boxes = $stmt->fetchAll();

    $stmt = $pdo->prepare('SELECT * FROM items WHERE place_id = ? ORDER BY name COLLATE NOCASE');
    $stmt->execute([$placeId]);
    $placeItems = $stmt->fetchAll();
    $pendingBarcode = pending_barcode_take();
    $pendingName = pending_name_take();
    $reviewItems = ocr_review_get($scanKey);
    $moveDestinations = $canEdit ? list_move_destinations($pdo, $ownerId) : [];

    ob_start();
    ?>
    <div class="card-head">
      <h1><?= h($place['name']) ?></h1>
      <?php if ($canEdit): ?>
      <details class="card-menu">
        <summary aria-label="Manage place"><?= icon('dots', 18) ?></summary>
        <div class="card-menu-body">
          <form method="post" class="inline-form">
            <input type="hidden" name="action" value="rename_place">
            <input type="text" name="name" value="<?= h($place['name']) ?>" required>
            <button type="submit" class="secondary"><?= icon('edit', 14) ?>Rename</button>
          </form>
          <form method="post" class="inline-form" onsubmit="return confirm('Delete this place and everything inside it?');">
            <input type="hidden" name="action" value="delete_place">
            <button type="submit" class="danger"><?= icon('trash', 14) ?>Delete place</button>
          </form>
        </div>
      </details>
      <?php endif; ?>
    </div>

    <h2>Boxes</h2>
    <?php if (!$boxes): ?>
      <div class="empty-state">
        <?= icon('box', 28) ?>
        <p>No boxes here yet<?= $canEdit ? ' — add one below.' : '.' ?></p>
      </div>
    <?php endif; ?>
    <ul class="card-list">
      <?php foreach ($boxes as $b): ?>
        <li class="card">
          <div class="card-head">
            <div class="card-body">
              <a class="card-title" href="/place/<?= h($placeSlug) ?>/<?= h($b['slug']) ?>"><?= h($b['name']) ?></a>
              <span class="meta"><?= (int)$b['item_count'] ?> item(s) · <code>/place/<?= h($placeSlug) ?>/<?= h($b['slug']) ?></code></span>
            </div>
            <?php if ($canEdit): ?>
            <details class="card-menu">
              <summary aria-label="Manage box"><?= icon('dots', 16) ?></summary>
              <div class="card-menu-body">
                <form method="post" class="inline-form">
                  <input type="hidden" name="action" value="rename_box">
                  <input type="hidden" name="id" value="<?= (int)$b['id'] ?>">
                  <input type="text" name="name" value="<?= h($b['name']) ?>" required>
                  <button type="submit" class="secondary"><?= icon('edit', 14) ?>Rename</button>
                </form>
                <?php if (count($moveDestinations) > 1): ?>
                <form method="post" class="inline-form">
                  <input type="hidden" name="action" value="move_box">
                  <input type="hidden" name="id" value="<?= (int)$b['id'] ?>">
                  <select name="place_id" aria-label="Move to place">
                    <?php foreach ($moveDestinations as $p): ?>
                      <option value="<?= (int)$p['id'] ?>" <?= ((int)$p['id'] === $placeId) ? 'selected' : '' ?>><?= h($p['name']) ?></option>
                    <?php endforeach; ?>
                  </select>
                  <button type="submit" class="secondary"><?= icon('move', 14) ?>Move</button>
                </form>
                <?php endif; ?>
                <form method="post" class="inline-form" onsubmit="return confirm('Delete this box and all its items?');">
                  <input type="hidden" name="action" value="delete_box">
                  <input type="hidden" name="id" value="<?= (int)$b['id'] ?>">
                  <button type="submit" class="danger"><?= icon('trash', 14) ?>Delete</button>
                </form>
              </div>
            </details>
            <?php endif; ?>
          </div>
        </li>
      <?php endforeach; ?>
      <?php if ($canEdit): ?>
      <li class="card add-card">
        <details>
          <summary><?= icon('plus', 15) ?>Add a box</summary>
          <form method="post">
            <input type="hidden" name="action" value="create_box">
            <input type="text" name="name" placeholder="e.g. Tools bin 2" required>
            <button type="submit">Add</button>
          </form>
        </details>
      </li>
      <?php endif; ?>
    </ul>

    <?php if ($canEdit || $placeItems): ?>
    <p class="section-note">Items below are loose in <?= h($place['name']) ?> itself, not inside any box.</p>
    <?php endif; ?>
    <?= render_photo_scan_section($scanKey, $reviewItems, $canEdit) ?>
    <?= render_items_section($placeItems, $pendingBarcode, $pendingName, $canEdit, $moveDestinations) ?>
    <?php
    layout($place['name'], ob_get_clean(), [$place['name'] => null], $nav);
    exit;
}

// -------------------------------------------------------------------------
// Route: /barcodes — the barcode -> item register (shared across every
// account — a barcode identifies a real-world product, not personal data).
// -------------------------------------------------------------------------
if ($segments === ['barcodes']) {
    if ($method === 'POST') {
        require_edit($pdo);
        $action = $_POST['action'] ?? '';
        if ($action === 'create_barcode') {
            $barcode = trim($_POST['barcode'] ?? '');
            $name = trim($_POST['name'] ?? '');
            if ($barcode !== '' && $name !== '') {
                remember_barcode($pdo, $barcode, $name);
                flash_set('Barcode linked to "' . $name . '".', 'success');
            } else {
                flash_set('A barcode and an item name are both required.', 'error');
            }
        } elseif ($action === 'rename_barcode') {
            $barcode = $_POST['barcode'] ?? '';
            $name = trim($_POST['name'] ?? '');
            if ($barcode !== '' && $name !== '') {
                $pdo->prepare('UPDATE barcode_items SET name = ? WHERE barcode = ?')->execute([$name, $barcode]);
                flash_set('Barcode updated.', 'success');
            }
        } elseif ($action === 'delete_barcode') {
            $barcode = $_POST['barcode'] ?? '';
            if ($barcode !== '') {
                $pdo->prepare('DELETE FROM barcode_items WHERE barcode = ?')->execute([$barcode]);
                flash_set('Barcode association removed.', 'success');
            }
        }
        redirect('/barcodes');
    }

    $barcodes = $pdo->query('SELECT * FROM barcode_items ORDER BY name COLLATE NOCASE')->fetchAll();

    ob_start();
    ?>
    <h1>Barcodes</h1>
    <p class="page-subtitle">Scan a barcode once while adding an item, and thingsFinder remembers what it is from then on — shared by everyone who uses this app.</p>
    <?php if (!$barcodes): ?>
      <div class="empty-state">
        <?= icon('barcode', 28) ?>
        <p>No barcodes registered yet.</p>
        <?php if ($canEdit): ?><p class="empty-hint">Scan one while adding an item, or add an association manually below.</p><?php endif; ?>
      </div>
    <?php endif; ?>
    <ul class="card-list">
      <?php foreach ($barcodes as $bc): ?>
        <li class="card">
          <div class="card-head">
            <div class="card-body">
              <span class="card-title"><?= h($bc['name']) ?></span>
              <span class="meta"><code><?= h($bc['barcode']) ?></code></span>
            </div>
            <?php if ($canEdit): ?>
            <details class="card-menu">
              <summary aria-label="Manage barcode"><?= icon('dots', 16) ?></summary>
              <div class="card-menu-body">
                <form method="post" class="inline-form">
                  <input type="hidden" name="action" value="rename_barcode">
                  <input type="hidden" name="barcode" value="<?= h($bc['barcode']) ?>">
                  <input type="text" name="name" value="<?= h($bc['name']) ?>" required>
                  <button type="submit" class="secondary"><?= icon('edit', 14) ?>Rename</button>
                </form>
                <form method="post" class="inline-form" onsubmit="return confirm('Remove this association? The barcode will need to be scanned and named again to relearn it.');">
                  <input type="hidden" name="action" value="delete_barcode">
                  <input type="hidden" name="barcode" value="<?= h($bc['barcode']) ?>">
                  <button type="submit" class="danger"><?= icon('trash', 14) ?>Remove</button>
                </form>
              </div>
            </details>
            <?php endif; ?>
          </div>
        </li>
      <?php endforeach; ?>
      <?php if ($canEdit): ?>
      <li class="card add-card">
        <details>
          <summary><?= icon('plus', 15) ?>Add a barcode</summary>
          <form method="post">
            <input type="hidden" name="action" value="create_barcode">
            <input type="text" name="barcode" placeholder="Barcode number" required inputmode="numeric">
            <input type="text" name="name" placeholder="Item name, e.g. Hot glue gun" required>
            <button type="submit">Add</button>
          </form>
        </details>
      </li>
      <?php endif; ?>
    </ul>
    <?php
    layout('Barcodes', ob_get_clean(), [], $nav);
    exit;
}

render_error(404, 'Page not found.', $nav);
