<?php
/**
 * /uninstall — the inverse of /setup. See includes/uninstall.php.
 */
declare(strict_types=1);

require_once __DIR__ . '/includes/db.php';
require_once __DIR__ . '/includes/auth.php';
require_once __DIR__ . '/includes/uninstall.php';

if (session_status() !== PHP_SESSION_ACTIVE) { session_start(); }

if (!TF_UNINSTALL_ENABLED) {
    http_response_code(404);
    exit('Not found');
}

$h = static fn($s) => htmlspecialchars((string)$s, ENT_QUOTES, 'UTF-8');

// Finished screen (shown after the redirect below, session already gone).
if (($_GET['done'] ?? '') === '1') {
    ?><!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>thingsFinder uninstalled</title><link rel="stylesheet" href="/style.css"></head>
<body><main class="container narrow">
  <h1>thingsFinder has been uninstalled</h1>
  <p>The database and every account were deleted. Phones that were syncing will
     get “signed out” on their next sync; their own copy of the data stays on the phone.</p>
  <p>To remove the app completely, delete the thingsFinder folder from your web server.
     To start again, go to <a href="/setup">setup</a>.</p>
</main></body></html><?php
    exit;
}

$pdo  = tf_uninstall_pdo();
$user = null;
try {
    $user = tf_uninstall_current_user($pdo);
} catch (PDOException $e) {
    // No users table: never set up (or already uninstalled).
    header('Location: /setup');
    exit;
}
if ($user === null) {
    header('Location: /login?next=' . rawurlencode('/uninstall'));
    exit;
}
if (!tf_uninstall_is_owner($pdo, $user)) {
    http_response_code(403);
    exit('Only the account created during setup can uninstall thingsFinder.');
}

$errors = [];
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $action = $_POST['action'] ?? '';
    if ($action === 'backup') {
        if (!tf_uninstall_csrf_ok($_POST['csrf'] ?? null)) {
            $errors[] = 'The form expired. Reload the page and try again.';
        } else {
            tf_uninstall_send_backup($pdo);
            exit;
        }
    } elseif ($action === 'uninstall') {
        $errors = tf_uninstall_run($pdo, $user, $_POST);
        if (!$errors) {
            header('Location: /uninstall?done=1', true, 303);
            exit;
        }
    }
}

$s     = tf_uninstall_summary($pdo);
$csrf  = tf_uninstall_csrf_token();
$other = max(0, (int)$s['users'] - 1);
$n     = static fn($v) => $v === null ? '—' : number_format($v);
?><!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Uninstall thingsFinder</title>
  <link rel="stylesheet" href="/style.css">
  <style>
    .danger { border: 2px solid #c62828; border-radius: 8px; padding: 1rem 1.25rem; }
    .danger h2 { color: #c62828; margin-top: 0; }
    .summary td { padding: .15rem 1rem .15rem 0; }
    .errors { background: #fdecea; color: #8a1c1c; padding: .75rem 1rem; border-radius: 6px; }
    button.delete { background: #c62828; color: #fff; border: 0; padding: .6rem 1.2rem; border-radius: 6px; }
    label { display: block; margin: .75rem 0 .25rem; }
    code { user-select: all; }
  </style>
</head>
<body>
<main class="container narrow">
  <p><a href="/account">← Back to account</a></p>
  <h1>Uninstall thingsFinder</h1>
  <p>This undoes <a href="/setup">setup</a>: it permanently deletes the database —
     every place, box, item, the barcode register, all accounts and sync tokens —
     and logs you out. Afterwards the site shows the setup page again.</p>

  <?php if ($errors): ?>
    <div class="errors" role="alert"><?php foreach ($errors as $e): ?><div><?= $h($e) ?></div><?php endforeach; ?></div>
  <?php endif; ?>

  <h2>What will be deleted</h2>
  <table class="summary">
    <tr><td>Accounts</td><td><?= $n($s['users']) ?><?= $other ? ' (you + ' . $other . ' other)' : '' ?></td></tr>
    <tr><td>Places</td><td><?= $n($s['places']) ?></td></tr>
    <tr><td>Boxes</td><td><?= $n($s['boxes']) ?></td></tr>
    <tr><td>Items</td><td><?= $n($s['items']) ?></td></tr>
    <tr><td>Barcodes</td><td><?= $n($s['barcodes']) ?></td></tr>
    <tr><td>Phone sync tokens</td><td><?= $n($s['api_tokens']) ?></td></tr>
    <tr><td>Database file</td><td><code><?= $h($s['db_path']) ?></code> (<?= $n((int)round($s['db_bytes'] / 1024)) ?> KB)</td></tr>
  </table>

  <h2>1. Download a backup (recommended)</h2>
  <p>A copy of the SQLite file. The Android app can import it under
     <em>Settings → Database file</em>, or put it back on the server to restore.</p>
  <form method="post">
    <input type="hidden" name="csrf" value="<?= $h($csrf) ?>">
    <input type="hidden" name="action" value="backup">
    <button type="submit">Download backup</button>
  </form>

  <h2>2. Uninstall</h2>
  <form method="post" class="danger" autocomplete="off">
    <h2>This cannot be undone</h2>
    <input type="hidden" name="csrf" value="<?= $h($csrf) ?>">
    <input type="hidden" name="action" value="uninstall">

    <label for="password">Your password (<?= $h($user['username']) ?>)</label>
    <input type="password" id="password" name="password" required autocomplete="current-password">

    <label for="phrase">Type <code><?= $h(TF_UNINSTALL_PHRASE) ?></code> to confirm</label>
    <input type="text" id="phrase" name="phrase" required spellcheck="false" autocapitalize="characters">

    <?php if ($other > 0): ?>
      <label><input type="checkbox" name="ack_other_users" value="1" required>
        I understand the <?= $other ?> other account(s), and everything shared through them, will be deleted too.</label>
    <?php endif; ?>

    <p><button type="submit" class="delete">Delete everything and uninstall</button></p>
  </form>
</main>
</body>
</html>
