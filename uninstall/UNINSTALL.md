# /uninstall — the inverse of /setup

| /setup | /uninstall |
|---|---|
| Only when there are no users | Only for the first account (the one setup created) |
| Creates the SQLite schema | Deletes the SQLite file (+ -wal, -shm, -journal) |
| Creates the first account and logs in | Deletes every account, revokes all phone sync tokens, logs out |
| Ends on the home page | Ends on a "uninstalled" page; the next visit shows /setup |

Safety: password re-entry, typing `DELETE EVERYTHING`, an extra checkbox when other
accounts exist, a CSRF token, and a "Download backup" button (a consistent SQLite
snapshot via `VACUUM INTO`, importable by the Android app under Settings → Database file).

## Files

- `uninstall.php` — the page (web root, next to `setup.php`)
- `includes/uninstall.php` — the logic

## Integration (3 small edits)

1. `.htaccess` — add next to the `/setup` rule:
       RewriteRule ^uninstall/?$ uninstall.php [L,QSA]
2. `account.php` — add a link at the bottom, shown to the first account only:
       <?php if (tf_uninstall_is_owner(db(), $user)): ?>
         <p><a href="/uninstall" class="danger-link">Uninstall thingsFinder…</a></p>
       <?php endif; ?>
   (`require_once __DIR__ . '/includes/uninstall.php';` at the top.)
3. Check the assumptions at the top of `includes/uninstall.php`:
   `db()` (or `get_db()`) returns the PDO, the session key is `user_id`,
   `/login?next=` exists. Set `define('TF_UNINSTALL_ENABLED', false);` before
   the include to switch the page off.

The web server user needs write access to the database's folder (it already
has it, since SQLite creates -wal/-shm files there).

## Effect on phones

Phone data is untouched — it lives on the phone. Their next sync gets 401
(token gone). The Android app should treat 401 from /api/sync as "signed out":
stop the worker and show "Cloud sync: signed out" in Settings, not retry hourly.
