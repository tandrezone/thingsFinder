<?php
/**
 * UI front controller.
 *
 * Routes:
 *   GET/POST  /                              home: list of places, create place
 *   GET       /search?q=...                  global search
 *   GET/POST  /place/{placeSlug}              boxes in a place, create box, rename/delete place or box
 *   GET/POST  /place/{placeSlug}/{boxSlug}    items in a box, create item, rename/delete box or item
 *   GET/POST  /barcodes                       the barcode -> item register: view, add, rename, remove
 */

require_once __DIR__ . '/includes/db.php';
require_once __DIR__ . '/includes/helpers.php';
require_once __DIR__ . '/includes/qrcode.php';

$pdo = get_db();
$method = $_SERVER['REQUEST_METHOD'];
$path = current_path();
$segments = path_segments($path);

function layout(string $title, string $body, array $breadcrumbs = []): void
{
    $flash = flash_take();
    $flashIcon = ['success' => 'check', 'error' => 'alert', 'info' => 'inbox'][$flash['type'] ?? ''] ?? 'inbox';
    ?><!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title><?= h($title) ?> · thingsFinder</title>
<link rel="stylesheet" href="/assets/style.css">
</head>
<body>
<header class="topbar">
  <a class="brand" href="/"><span class="brand-mark">📦</span> thingsFinder</a>
  <a class="btn btn-ghost topbar-link" href="/barcodes"><?= icon('barcode', 15) ?><span class="btn-label">Barcodes</span></a>
  <form class="search-form" action="/search" method="get">
    <div class="search-wrap">
      <?= icon('search', 15) ?>
      <input type="search" name="q" placeholder="Search for an item…" value="<?= h($_GET['q'] ?? '') ?>" autocomplete="off">
    </div>
    <button type="submit"><?= icon('search', 15) ?><span class="btn-label">Search</span></button>
  </form>
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
  <small>JSON API available under <code>/api</code> — e.g. <code>/api/search?q=glue</code></small>
</footer>
<script src="/assets/scan.js" defer></script>
</body>
</html>
<?php
}

function render_error(int $status, string $message): void
{
    http_response_code($status);
    layout('Error', '<div class="empty-state">' . icon('alert', 28) . '<p>' . h($message) . '</p></div>');
    exit;
}

// -------------------------------------------------------------------------
// Route: home
// -------------------------------------------------------------------------
if ($segments === []) {
    if ($method === 'POST') {
        $action = $_POST['action'] ?? '';
        if ($action === 'create_place') {
            $name = trim($_POST['name'] ?? '');
            if ($name !== '') {
                $slug = unique_place_slug($pdo, $name);
                $pdo->prepare('INSERT INTO places (name, slug) VALUES (?, ?)')->execute([$name, $slug]);
                flash_set('Place "' . $name . '" created.', 'success');
            } else {
                flash_set('Place name cannot be empty.', 'error');
            }
        } elseif ($action === 'rename_place') {
            $id = (int)($_POST['id'] ?? 0);
            $name = trim($_POST['name'] ?? '');
            if ($id && $name !== '') {
                $slug = unique_place_slug($pdo, $name, $id);
                $pdo->prepare('UPDATE places SET name = ?, slug = ? WHERE id = ?')->execute([$name, $slug, $id]);
                flash_set('Place renamed.', 'success');
            }
        } elseif ($action === 'delete_place') {
            $id = (int)($_POST['id'] ?? 0);
            if ($id) {
                $pdo->prepare('DELETE FROM places WHERE id = ?')->execute([$id]);
                flash_set('Place deleted.', 'success');
            }
        }
        redirect('/');
    }

    $places = $pdo->query(
        "SELECT places.*,
                (SELECT COUNT(*) FROM boxes WHERE boxes.place_id = places.id) AS box_count,
                (SELECT COUNT(*) FROM items JOIN boxes ON boxes.id = items.box_id WHERE boxes.place_id = places.id) AS item_count
         FROM places ORDER BY name COLLATE NOCASE"
    )->fetchAll();

    ob_start();
    ?>
    <h1>Places</h1>
    <p class="page-subtitle">Everything you own, findable in seconds.</p>
    <?php if (!$places): ?>
      <div class="empty-state">
        <?= icon('inbox', 28) ?>
        <p>No places yet.</p>
        <p class="empty-hint">Add your first place below (e.g. "Garage", "Attic", "Kitchen").</p>
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
          </div>
        </li>
      <?php endforeach; ?>
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
    </ul>
    <?php
    layout('Places', ob_get_clean());
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
            "SELECT items.id, items.name AS item_name, boxes.name AS box_name, boxes.slug AS box_slug,
                    places.name AS place_name, places.slug AS place_slug
             FROM items
             JOIN boxes ON boxes.id = items.box_id
             JOIN places ON places.id = boxes.place_id
             WHERE items.name LIKE ? ESCAPE '\\'
             ORDER BY items.name COLLATE NOCASE"
        );
        $stmt->execute([$like]);
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
              <span class="card-title"><?= h($r['item_name']) ?></span>
              <span class="meta">in
                <a href="/place/<?= h($r['place_slug']) ?>"><?= h($r['place_name']) ?></a>
                <?= icon('chevron', 12) ?>
                <a href="/place/<?= h($r['place_slug']) ?>/<?= h($r['box_slug']) ?>"><?= h($r['box_name']) ?></a>
              </span>
            </div>
          </li>
        <?php endforeach; ?>
      </ul>
    <?php endif; ?>
    <?php
    layout('Search', ob_get_clean());
    exit;
}

// -------------------------------------------------------------------------
// Route: /place/{placeSlug}
// -------------------------------------------------------------------------
if (count($segments) >= 2 && $segments[0] === 'place') {
    $placeSlug = $segments[1];
    $place = find_place_by_slug($pdo, $placeSlug);
    if (!$place) {
        render_error(404, 'No place found for "' . $placeSlug . '".');
    }
    $placeId = (int)$place['id'];

    // ---------------------------------------------------------------
    // Route: /place/{placeSlug}/{boxSlug}/qr.svg | qr.png
    //   Downloadable QR code for the box, encoding the URL to its
    //   JSON contents at /api/boxes/{id}/contents.
    // ---------------------------------------------------------------
    if (count($segments) === 4 && in_array($segments[3], ['qr.svg', 'qr.png'], true)) {
        $boxSlug = $segments[2];
        $box = find_box_by_slug($pdo, $placeId, $boxSlug);
        if (!$box) {
            render_error(404, 'No box "' . $boxSlug . '" found in "' . $place['name'] . '".');
        }
        $contentsUrl = base_url() . '/api/boxes/' . (int)$box['id'] . '/contents';
        if ($segments[3] === 'qr.png') {
            qrcode_send_png($contentsUrl);
        } else {
            qrcode_send_svg($contentsUrl);
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
            render_error(404, 'No box "' . $boxSlug . '" found in "' . $place['name'] . '".');
        }
        $boxId = (int)$box['id'];
        $contentsUrl = base_url() . '/api/boxes/' . $boxId . '/contents';

        if ($method === 'POST') {
            $action = $_POST['action'] ?? '';
            if ($action === 'create_item') {
                $name = trim($_POST['name'] ?? '');
                $barcode = trim($_POST['barcode'] ?? '');
                $known = $barcode !== '' ? find_barcode($pdo, $barcode) : null;
                if ($known) {
                    // A recognized barcode always wins — that's the whole point of scanning.
                    $name = $known['name'];
                }
                if ($name === '') {
                    if ($barcode !== '') {
                        pending_barcode_set($barcode);
                        flash_set('That barcode isn\'t registered yet — type an item name so thingsFinder remembers it.', 'error');
                    } else {
                        flash_set('Item name cannot be empty.', 'error');
                    }
                } else {
                    $pdo->prepare('INSERT INTO items (box_id, name) VALUES (?, ?)')->execute([$boxId, $name]);
                    if ($barcode !== '' && !$known) {
                        remember_barcode($pdo, $barcode, $name);
                        flash_set('Item "' . $name . '" added — barcode remembered for next time.', 'success');
                    } elseif ($known) {
                        flash_set('Recognized barcode — added "' . $name . '".', 'success');
                    } else {
                        flash_set('Item "' . $name . '" added.', 'success');
                    }
                }
            } elseif ($action === 'rename_item') {
                $id = (int)($_POST['id'] ?? 0);
                $name = trim($_POST['name'] ?? '');
                if ($id && $name !== '') {
                    $pdo->prepare('UPDATE items SET name = ? WHERE id = ?')->execute([$name, $id]);
                    flash_set('Item renamed.', 'success');
                }
            } elseif ($action === 'delete_item') {
                $id = (int)($_POST['id'] ?? 0);
                if ($id) {
                    $pdo->prepare('DELETE FROM items WHERE id = ?')->execute([$id]);
                    flash_set('Item deleted.', 'success');
                }
            } elseif ($action === 'rename_box') {
                $name = trim($_POST['name'] ?? '');
                if ($name !== '') {
                    $slug = unique_box_slug($pdo, $placeId, $name, $boxId);
                    $pdo->prepare('UPDATE boxes SET name = ?, slug = ? WHERE id = ?')->execute([$name, $slug, $boxId]);
                    flash_set('Box renamed.', 'success');
                    redirect('/place/' . $placeSlug . '/' . $slug);
                }
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

        ob_start();
        ?>
        <div class="card-head">
          <div class="card-body">
            <h1><?= h($box['name']) ?></h1>
            <p class="meta">Box in <a href="/place/<?= h($placeSlug) ?>"><?= h($place['name']) ?></a> · permalink <code>/place/<?= h($placeSlug) ?>/<?= h($box['slug']) ?></code></p>
          </div>
          <details class="card-menu">
            <summary aria-label="Manage box"><?= icon('dots', 18) ?></summary>
            <div class="card-menu-body">
              <form method="post" class="inline-form">
                <input type="hidden" name="action" value="rename_box">
                <input type="text" name="name" value="<?= h($box['name']) ?>" required>
                <button type="submit" class="secondary"><?= icon('edit', 14) ?>Rename</button>
              </form>
              <form method="post" class="inline-form" onsubmit="return confirm('Delete this box and all its items?');">
                <input type="hidden" name="action" value="delete_box">
                <button type="submit" class="danger"><?= icon('trash', 14) ?>Delete box</button>
              </form>
            </div>
          </details>
        </div>

        <div class="qr-block">
          <div class="qr-image"><?= qrcode_svg_markup($contentsUrl, 4) ?></div>
          <div class="qr-info">
            <p class="meta">Scan this to get the box's contents as JSON — handy for a printed label on the box itself.</p>
            <p class="meta"><code><?= h($contentsUrl) ?></code></p>
            <div class="row-actions">
              <a class="btn secondary" href="/place/<?= h($placeSlug) ?>/<?= h($box['slug']) ?>/qr.svg" download><?= icon('download', 14) ?>SVG</a>
              <a class="btn secondary" href="/place/<?= h($placeSlug) ?>/<?= h($box['slug']) ?>/qr.png" download><?= icon('download', 14) ?>PNG</a>
              <a class="btn secondary" href="/api/boxes/<?= $boxId ?>/contents" target="_blank" rel="noopener"><?= icon('external', 14) ?>Open JSON</a>
            </div>
          </div>
        </div>

        <h2>Items</h2>
        <?php if (!$items): ?>
          <div class="empty-state">
            <?= icon('box', 28) ?>
            <p>No items in this box yet.</p>
          </div>
        <?php endif; ?>
        <ul class="card-list">
          <?php foreach ($items as $it): ?>
            <li class="card">
              <div class="card-head">
                <div class="card-body">
                  <span class="card-title"><?= h($it['name']) ?></span>
                </div>
                <details class="card-menu">
                  <summary aria-label="Manage item"><?= icon('dots', 16) ?></summary>
                  <div class="card-menu-body">
                    <form method="post" class="inline-form">
                      <input type="hidden" name="action" value="rename_item">
                      <input type="hidden" name="id" value="<?= (int)$it['id'] ?>">
                      <input type="text" name="name" value="<?= h($it['name']) ?>" required>
                      <button type="submit" class="secondary"><?= icon('edit', 14) ?>Rename</button>
                    </form>
                    <form method="post" class="inline-form" onsubmit="return confirm('Delete this item?');">
                      <input type="hidden" name="action" value="delete_item">
                      <input type="hidden" name="id" value="<?= (int)$it['id'] ?>">
                      <button type="submit" class="danger"><?= icon('trash', 14) ?>Delete</button>
                    </form>
                  </div>
                </details>
              </div>
            </li>
          <?php endforeach; ?>
          <li class="card add-card">
            <details<?= $pendingBarcode !== '' ? ' open' : '' ?>>
              <summary><?= icon('plus', 15) ?>Add an item</summary>
              <form method="post" class="add-item-form">
                <input type="hidden" name="action" value="create_item">
                <div class="barcode-row">
                  <input type="text" name="barcode" value="<?= h($pendingBarcode) ?>" placeholder="Barcode — scan or type" autocomplete="off" inputmode="numeric">
                  <button type="button" class="secondary scan-btn" hidden><?= icon('camera', 14) ?>Scan</button>
                </div>
                <p class="scan-hint meta" hidden></p>
                <input type="text" name="name" placeholder="e.g. Hot glue gun">
                <button type="submit">Add</button>
              </form>
              <div class="scanner-overlay" hidden>
                <video playsinline muted></video>
                <button type="button" class="btn btn-ghost scan-cancel">Cancel</button>
              </div>
            </details>
          </li>
        </ul>
        <p class="meta">Know a barcode already? Scan it and thingsFinder either adds the item it remembers, or asks you to name it once so it knows next time. Manage all associations on the <a href="/barcodes">barcode register</a>.</p>
        <?php
        layout($box['name'], ob_get_clean(), [$place['name'] => '/place/' . $placeSlug, $box['name'] => null]);
        exit;
    }

    // ---------------------------------------------------------------
    // Route: /place/{placeSlug}  (list of boxes)
    // ---------------------------------------------------------------
    if ($method === 'POST') {
        $action = $_POST['action'] ?? '';
        if ($action === 'create_box') {
            $name = trim($_POST['name'] ?? '');
            if ($name !== '') {
                $slug = unique_box_slug($pdo, $placeId, $name);
                $pdo->prepare('INSERT INTO boxes (place_id, name, slug) VALUES (?, ?, ?)')->execute([$placeId, $name, $slug]);
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
        } elseif ($action === 'delete_box') {
            $id = (int)($_POST['id'] ?? 0);
            if ($id) {
                $pdo->prepare('DELETE FROM boxes WHERE id = ?')->execute([$id]);
                flash_set('Box deleted.', 'success');
            }
        } elseif ($action === 'rename_place') {
            $name = trim($_POST['name'] ?? '');
            if ($name !== '') {
                $slug = unique_place_slug($pdo, $name, $placeId);
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

    ob_start();
    ?>
    <div class="card-head">
      <h1><?= h($place['name']) ?></h1>
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
    </div>

    <h2>Boxes</h2>
    <?php if (!$boxes): ?>
      <div class="empty-state">
        <?= icon('box', 28) ?>
        <p>No boxes here yet — add one below.</p>
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
            <details class="card-menu">
              <summary aria-label="Manage box"><?= icon('dots', 16) ?></summary>
              <div class="card-menu-body">
                <form method="post" class="inline-form">
                  <input type="hidden" name="action" value="rename_box">
                  <input type="hidden" name="id" value="<?= (int)$b['id'] ?>">
                  <input type="text" name="name" value="<?= h($b['name']) ?>" required>
                  <button type="submit" class="secondary"><?= icon('edit', 14) ?>Rename</button>
                </form>
                <form method="post" class="inline-form" onsubmit="return confirm('Delete this box and all its items?');">
                  <input type="hidden" name="action" value="delete_box">
                  <input type="hidden" name="id" value="<?= (int)$b['id'] ?>">
                  <button type="submit" class="danger"><?= icon('trash', 14) ?>Delete</button>
                </form>
              </div>
            </details>
          </div>
        </li>
      <?php endforeach; ?>
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
    </ul>
    <?php
    layout($place['name'], ob_get_clean(), [$place['name'] => null]);
    exit;
}

// -------------------------------------------------------------------------
// Route: /barcodes — the barcode -> item register
// -------------------------------------------------------------------------
if ($segments === ['barcodes']) {
    if ($method === 'POST') {
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
    <p class="page-subtitle">Scan a barcode once while adding an item, and thingsFinder remembers what it is from then on.</p>
    <?php if (!$barcodes): ?>
      <div class="empty-state">
        <?= icon('barcode', 28) ?>
        <p>No barcodes registered yet.</p>
        <p class="empty-hint">Scan one while adding an item, or add an association manually below.</p>
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
          </div>
        </li>
      <?php endforeach; ?>
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
    </ul>
    <?php
    layout('Barcodes', ob_get_clean());
    exit;
}

render_error(404, 'Page not found.');
