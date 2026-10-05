#!/usr/bin/env bash
# =============================================================================
# thingsFinder — Apache virtual host setup for Debian/Ubuntu
#
#   Installs PHP + Apache (mod_php), configures a VirtualHost for the domain
#   with web/ as its DocumentRoot (nothing else in the project is served),
#   installs Composer dependencies, creates .env
#   from .env.example, prepares the SQLite data directory, optionally issues a
#   Let's Encrypt certificate, and then verifies everything.
#
#   thingsFinder uses SQLite (data/database.sqlite, created automatically) — so
#   there is no MySQL server or DB user to set up. Settings live in .env (see
#   .env.example); an existing .env is never overwritten.
#
# Usage (run from the project root, or pass --app-dir):
#   sudo ./setup.sh                              # HTTP vhost for thingsfinder.xyz
#   sudo ./setup.sh --ssl you@example.com        # + Let's Encrypt HTTPS (+ redirect)
#   sudo ./setup.sh --local-hosts                # + map the domain to 127.0.0.1 in /etc/hosts
#   sudo ./setup.sh --verify-only                # only run the checks
#
# Options:
#   --domain NAME      Domain (default: thingsfinder.xyz; www.<domain> is added as alias)
#   --app-dir DIR      Project root (default: directory of this script); the
#                      DocumentRoot is DIR/web
#   --ssl EMAIL        Obtain/renew a Let's Encrypt cert via certbot (DNS must already
#                      point at this server and port 80/443 must be reachable)
#   --no-ocr           Skip installing tesseract-ocr ("add items from a photo")
#   --no-www           Don't add www.<domain> as a ServerAlias / to the cert
#   --local-hosts      Add "127.0.0.1 <domain> www.<domain>" to /etc/hosts (local testing)
#   --force            Overwrite an existing, locally-modified vhost file (a backup is kept)
#   --verify-only      Skip setup, just run verification
#
# Upgrading: a vhost from before web/ existed (DocumentRoot = project root) has
# its DocumentRoot and <Directory> moved to web/ in place — in <domain>.conf
# and certbot's <domain>-le-ssl.conf — keeping any other local edits (a backup
# is kept). Use --force to replace <domain>.conf with the generated one instead.
#
# Safe to re-run: packages are only installed when missing, the vhost is only
# (re)written when absent or with --force, and the SQLite database is never
# touched beyond the app's own CREATE TABLE IF NOT EXISTS schema bootstrap.
# =============================================================================
set -Eeuo pipefail

# ---------- defaults ----------------------------------------------------------
DOMAIN="thingsfinder.xyz"
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SSL_EMAIL=""
WITH_OCR=1
WITH_WWW=1
LOCAL_HOSTS=0
FORCE=0
VERIFY_ONLY=0
WEB_USER="www-data"
WEB_GROUP="www-data"
PHP_MIN_VERSION="8.1"

# ---------- output helpers ----------------------------------------------------
if [[ -t 1 ]]; then C_G=$'\e[32m'; C_Y=$'\e[33m'; C_R=$'\e[31m'; C_B=$'\e[1m'; C_0=$'\e[0m'
else C_G=""; C_Y=""; C_R=""; C_B=""; C_0=""; fi
info()  { echo "${C_B}==>${C_0} $*"; }
ok()    { echo "  ${C_G}✔${C_0} $*"; }
warn()  { echo "  ${C_Y}!${C_0} $*"; }
err()   { echo "  ${C_R}✘${C_0} $*" >&2; }
die()   { err "$*"; exit 1; }
trap 'err "Setup aborted at line $LINENO (exit $?)"' ERR

usage() { sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

# ---------- args --------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --domain)      DOMAIN="${2:?--domain needs a value}"; shift 2 ;;
    --app-dir)     APP_DIR="$(cd "${2:?--app-dir needs a value}" && pwd)"; shift 2 ;;
    --ssl)         SSL_EMAIL="${2:?--ssl needs an email address}"; shift 2 ;;
    --no-ocr)      WITH_OCR=0; shift ;;
    --no-www)      WITH_WWW=0; shift ;;
    --local-hosts) LOCAL_HOSTS=1; shift ;;
    --force)       FORCE=1; shift ;;
    --verify-only) VERIFY_ONLY=1; shift ;;
    -h|--help)     usage 0 ;;
    *)             err "Unknown option: $1"; usage 1 ;;
  esac
done

WEB_ROOT="$APP_DIR/web"   # the only public directory
SITE_NAME="$DOMAIN"
VHOST_FILE="/etc/apache2/sites-available/${SITE_NAME}.conf"
SSL_VHOST_FILE="/etc/apache2/sites-available/${SITE_NAME}-le-ssl.conf"  # written by certbot
ALIASES=""; [[ $WITH_WWW -eq 1 ]] && ALIASES="www.${DOMAIN}"

# ---------- privilege helpers -------------------------------------------------
if [[ $EUID -eq 0 ]]; then SUDO=""; else
  command -v sudo >/dev/null || die "Run as root, or install sudo."
  SUDO="sudo"
fi
as_web_user() {  # run a command as the Apache user
  if [[ $EUID -eq 0 ]] && command -v runuser >/dev/null; then runuser -u "$WEB_USER" -- "$@"
  else $SUDO -u "$WEB_USER" "$@"; fi
}
svc() {  # svc <action> <service> — systemd if it's PID 1, else SysV `service`
  if [[ -d /run/systemd/system ]]; then $SUDO systemctl "$1" "$2"
  else $SUDO service "$2" "$1"; fi
}
svc_active() {
  if [[ -d /run/systemd/system ]]; then systemctl is-active --quiet "$1"
  else $SUDO service "$1" status >/dev/null 2>&1; fi
}

# =============================================================================
# Phase 1 — prerequisites
# =============================================================================
check_os() {
  info "Checking operating system"
  [[ -r /etc/os-release ]] || die "Cannot detect OS (/etc/os-release missing)."
  # shellcheck disable=SC1091
  . /etc/os-release
  case " ${ID:-} ${ID_LIKE:-} " in
    *" debian "*|*" ubuntu "*) ok "${PRETTY_NAME:-$ID}" ;;
    *) die "Unsupported OS '${PRETTY_NAME:-unknown}' — this script targets Debian/Ubuntu." ;;
  esac
  command -v apt-get >/dev/null || die "apt-get not found."
  ok "apt-get available"
  [[ -f "$WEB_ROOT/index.php" && -f "$APP_DIR/includes/db.php" ]] \
    || die "$APP_DIR doesn't look like thingsFinder (web/index.php / includes/db.php missing). Use --app-dir."
  ok "Project root: $APP_DIR (web root: $WEB_ROOT)"
}

APT_UPDATED=0
pkg_installed() { dpkg-query -W -f='${Status}' "$1" 2>/dev/null | grep -q "install ok installed"; }
ensure_packages() {
  local missing=() p
  for p in "$@"; do pkg_installed "$p" || missing+=("$p"); done
  if [[ ${#missing[@]} -eq 0 ]]; then ok "Already installed: $*"; return; fi
  info "Installing: ${missing[*]}"
  if [[ $APT_UPDATED -eq 0 ]]; then $SUDO apt-get update -qq || warn "apt-get update reported errors (continuing)"; APT_UPDATED=1; fi
  $SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends "${missing[@]}" \
      >/tmp/thingsfinder-apt.log 2>&1 || { tail -20 /tmp/thingsfinder-apt.log; die "apt-get install failed (full log: /tmp/thingsfinder-apt.log)"; }
  ok "Installed: ${missing[*]}"
}

# The PHP that Apache runs (mod_php) can differ from the default CLI `php`
# (e.g. with the ondrej/php PPA). Use the matching CLI binary for DB bootstrap.
APACHE_PHP=""; PHP_BIN="php"
detect_php() {
  APACHE_PHP="$(ls /etc/apache2/mods-available/ 2>/dev/null | sed -n 's/^php\([0-9.]*\)\.load$/\1/p' | sort -V | tail -1)"
  if [[ -n "$APACHE_PHP" ]] && command -v "php$APACHE_PHP" >/dev/null; then PHP_BIN="php$APACHE_PHP"; else PHP_BIN="php"; fi
}

install_dependencies() {
  info "Checking dependencies"
  # Apache + mod_php; PHP extensions the app uses:
  #   pdo_sqlite (database), mbstring (slugs/names), gd+freetype (PNG QR/labels),
  #   curl (barcode name lookups). session/json/random are built into PHP.
  #   composer + unzip: PHP dependencies (vlucas/phpdotenv, which reads .env).
  local pkgs=(apache2 libapache2-mod-php php-cli php-sqlite3 php-mbstring php-gd php-curl curl ca-certificates composer unzip)
  [[ $WITH_OCR -eq 1 ]] && pkgs+=(tesseract-ocr)          # "Add items from a photo"
  [[ -n "$SSL_EMAIL" ]] && pkgs+=(certbot python3-certbot-apache)
  ensure_packages "${pkgs[@]}"

  detect_php
  local v; v="$($PHP_BIN -r 'echo PHP_MAJOR_VERSION.".".PHP_MINOR_VERSION;')"
  if $PHP_BIN -r "exit(version_compare(PHP_VERSION, '$PHP_MIN_VERSION', '>=') ? 0 : 1);"; then ok "PHP $v"
  else die "PHP $v found, thingsFinder needs >= $PHP_MIN_VERSION"; fi
}

# =============================================================================
# Phase 2 — Apache
# =============================================================================
render_vhost() {
  local alias_line=""; [[ -n "$ALIASES" ]] && alias_line="    ServerAlias ${ALIASES}"
  cat <<EOF
# Managed by thingsFinder setup.sh — re-run with --force to regenerate.
<VirtualHost *:80>
    ServerName ${DOMAIN}
${alias_line}
    ServerAdmin webmaster@${DOMAIN}

    # Only web/ is public. The project root around it (.env, vendor/, data/,
    # includes/, api/, docs/, tests, the Android project) is never served;
    # PHP still include()s it from disk.
    DocumentRoot ${WEB_ROOT}

    <Directory ${APP_DIR}>
        Options -Indexes
        AllowOverride None
        Require all denied
    </Directory>
    <Directory ${WEB_ROOT}>
        Options -Indexes +FollowSymLinks
        # web/.htaccess holds the mod_rewrite front-controller rules.
        AllowOverride All
        Require all granted
    </Directory>

    <IfModule php_module>
        # Photo uploads for OCR are capped at 8MB by the app.
        php_admin_value upload_max_filesize 10M
        php_admin_value post_max_size 12M
        php_admin_flag  expose_php Off
    </IfModule>

    <IfModule mod_headers.c>
        Header always set X-Content-Type-Options "nosniff"
        Header always set Referrer-Policy "strict-origin-when-cross-origin"
    </IfModule>

    ErrorLog \${APACHE_LOG_DIR}/${SITE_NAME}_error.log
    CustomLog \${APACHE_LOG_DIR}/${SITE_NAME}_access.log combined
</VirtualHost>
EOF
}

# A vhost written before web/ existed serves the whole project root. Point its
# DocumentRoot and <Directory> at web/ in place (backup kept), leaving any other
# local edits alone. Also applied to certbot's -le-ssl.conf copy of the vhost.
migrate_docroot() {
  local f="$1" re web
  [[ -f "$f" ]] || return 0
  re="$(printf '%s' "${APP_DIR%/}" | sed 's/[][\.*^$()+?{}|/]/\\&/g')"
  web="$(printf '%s' "$WEB_ROOT" | sed 's/[\&|]/\\&/g')"
  grep -Eq "^[[:space:]]*DocumentRoot[[:space:]]+\"?${re}/?\"?[[:space:]]*\$" "$f" || return 0
  $SUDO cp -a "$f" "${f}.bak.$(date +%Y%m%d%H%M%S)"
  $SUDO sed -E -i \
    -e "s|^([[:space:]]*DocumentRoot[[:space:]]+)\"?${re}/?\"?[[:space:]]*\$|\\1${web}|" \
    -e "s|^([[:space:]]*<Directory[[:space:]]+)\"?${re}/?\"?[[:space:]]*>|\\1${web}>|" \
    "$f"
  ok "$(basename "$f"): DocumentRoot moved to $WEB_ROOT (backup kept)"
}

configure_apache() {
  info "Configuring Apache"
  local m
  for m in rewrite headers; do
    if a2query -q -m "$m" 2>/dev/null; then ok "mod_$m enabled"; else $SUDO a2enmod -q "$m" >/dev/null; ok "mod_$m enabled (new)"; fi
  done
  # mod_php needs the prefork MPM; the Debian package normally switches it.
  detect_php
  local phpmod="php${APACHE_PHP}"
  [[ -n "$APACHE_PHP" ]] || die "No Apache PHP module found in /etc/apache2/mods-available."
  if ! a2query -q -m "$phpmod" 2>/dev/null; then
    a2query -q -m mpm_event 2>/dev/null && $SUDO a2dismod -q mpm_event >/dev/null
    a2query -q -m mpm_worker 2>/dev/null && $SUDO a2dismod -q mpm_worker >/dev/null
    $SUDO a2enmod -q mpm_prefork "$phpmod" >/dev/null
    ok "$phpmod enabled (new)"
  else ok "$phpmod enabled"; fi

  [[ $FORCE -eq 1 ]] || migrate_docroot "$VHOST_FILE"
  migrate_docroot "$SSL_VHOST_FILE"

  local tmp; tmp="$(mktemp)"; render_vhost >"$tmp"
  if [[ ! -f "$VHOST_FILE" ]]; then
    $SUDO install -m 0644 "$tmp" "$VHOST_FILE"; ok "Created $VHOST_FILE"
  elif cmp -s "$tmp" "$VHOST_FILE"; then
    ok "$VHOST_FILE is up to date"
  elif [[ $FORCE -eq 1 ]]; then
    $SUDO cp -a "$VHOST_FILE" "${VHOST_FILE}.bak.$(date +%Y%m%d%H%M%S)"
    $SUDO install -m 0644 "$tmp" "$VHOST_FILE"; ok "Rewrote $VHOST_FILE (--force; backup kept)"
  else
    $SUDO install -m 0644 "$tmp" "${VHOST_FILE}.new"
    warn "$VHOST_FILE differs from the generated one — left untouched."
    warn "Proposed version saved as ${VHOST_FILE}.new (re-run with --force to apply)."
  fi
  rm -f "$tmp"

  if a2query -q -s "$SITE_NAME" 2>/dev/null; then ok "Site $SITE_NAME enabled"
  else $SUDO a2ensite -q "$SITE_NAME" >/dev/null; ok "Site $SITE_NAME enabled (new)"; fi

  # Silence "Could not reliably determine the server's FQDN" once, globally.
  if [[ ! -f /etc/apache2/conf-available/servername.conf ]]; then
    echo "ServerName localhost" | $SUDO tee /etc/apache2/conf-available/servername.conf >/dev/null
    $SUDO a2enconf -q servername >/dev/null
  fi

  $SUDO apache2ctl configtest 2>&1 | grep -q "Syntax OK" || { $SUDO apache2ctl configtest; die "Apache config test failed."; }
  ok "apache2ctl configtest: Syntax OK"
  if svc_active apache2; then svc reload apache2 >/dev/null; ok "Apache reloaded"
  else svc start apache2 >/dev/null; ok "Apache started"; fi
  [[ -d /run/systemd/system ]] && $SUDO systemctl enable -q apache2 2>/dev/null || true
}

# =============================================================================
# Phase 3 — permissions + SQLite database
# =============================================================================
configure_permissions() {
  info "Setting permissions"
  local data="$APP_DIR/data"
  $SUDO mkdir -p "$data"
  # SQLite needs write access to the directory (journal files), not just the file.
  $SUDO chgrp "$WEB_GROUP" "$data"
  $SUDO chmod 2775 "$data"
  local f
  for f in "$data"/database.sqlite*; do
    [[ -e "$f" ]] || continue
    $SUDO chgrp "$WEB_GROUP" "$f"; $SUDO chmod g+rw "$f"
  done
  ok "data/ owned by group $WEB_GROUP, setgid, group-writable"

  local u="${SUDO_USER:-${USER:-}}"
  if [[ -n "$u" && "$u" != "root" ]] && id "$u" >/dev/null 2>&1; then
    if id -nG "$u" | tr ' ' '\n' | grep -qx "$WEB_GROUP"; then ok "User $u is in group $WEB_GROUP"
    else $SUDO usermod -aG "$WEB_GROUP" "$u"; ok "Added $u to group $WEB_GROUP (log out/in to take effect)"; fi
  fi

  if as_web_user test -r "$WEB_ROOT/index.php" -a -x "$WEB_ROOT" -a -r "$APP_DIR/includes/db.php"; then ok "$WEB_USER can read the project"
  else
    err "$WEB_USER cannot read $APP_DIR (a parent directory is probably not world-traversable,"
    err "e.g. a 750 home dir). Either: sudo chmod o+x on each parent, or move the project to"
    die "/var/www/thingsfinder and re-run with --app-dir /var/www/thingsfinder."
  fi
}

# Composer dependencies, installed as the project's owner (not root) so later
# `composer` runs by that user keep working. vendor/ is blocked from the web.
install_php_dependencies() {
  info "Installing PHP dependencies (Composer)"
  [[ -f "$APP_DIR/composer.json" ]] || { warn "No composer.json — skipping"; return 0; }
  local owner; owner="$(stat -c %U "$APP_DIR")"
  local run=(composer install --no-dev --no-interaction --no-progress --optimize-autoloader --working-dir="$APP_DIR")
  if [[ "$owner" == "root" ]]; then
    $SUDO env COMPOSER_ALLOW_SUPERUSER=1 "${run[@]}" >/tmp/thingsfinder-composer.log 2>&1
  else
    $SUDO -u "$owner" "${run[@]}" >/tmp/thingsfinder-composer.log 2>&1
  fi || { tail -20 /tmp/thingsfinder-composer.log; die "composer install failed (full log: /tmp/thingsfinder-composer.log)"; }
  ok "vendor/ installed (as $owner)"
}

# .env holds the app's settings. Created once from .env.example; readable by
# the web server's group only.
configure_env() {
  info "Configuring .env"
  local env="$APP_DIR/.env"
  if [[ -f "$env" ]]; then ok ".env exists — left untouched"
  elif [[ -f "$APP_DIR/.env.example" ]]; then
    $SUDO cp "$APP_DIR/.env.example" "$env"
    ok "Created .env from .env.example (edit it to change settings)"
  else warn "No .env.example — running on defaults"; return 0; fi
  $SUDO chgrp "$WEB_GROUP" "$env"; $SUDO chmod 0640 "$env"
  ok ".env readable by group $WEB_GROUP only"
}

init_database() {
  info "Initialising SQLite database"
  # The app creates/migrates its own schema on first connection (includes/db.php).
  # Run that bootstrap as the web user so the file gets the right owner.
  as_web_user "$PHP_BIN" -r '
    chdir($argv[1]); require "includes/db.php";
    $n = get_db()->query("SELECT COUNT(*) FROM sqlite_master WHERE type=\"table\"")->fetchColumn();
    echo $n;' "$APP_DIR" >/tmp/tf_tables.$$ || die "Schema bootstrap failed."
  ok "data/database.sqlite ready ($(cat /tmp/tf_tables.$$) tables)"; rm -f /tmp/tf_tables.$$
  $SUDO chmod 0660 "$APP_DIR/data/database.sqlite"
}

# =============================================================================
# Phase 4 — optional extras
# =============================================================================
configure_local_hosts() {
  [[ $LOCAL_HOSTS -eq 1 ]] || return 0
  info "Mapping $DOMAIN to 127.0.0.1 in /etc/hosts"
  if grep -Eq "^[^#]*[[:space:]]${DOMAIN//./\\.}([[:space:]]|\$)" /etc/hosts; then ok "Entry already present"
  else echo "127.0.0.1 ${DOMAIN} ${ALIASES}  # thingsFinder" | $SUDO tee -a /etc/hosts >/dev/null; ok "Added"; fi
}

configure_ssl() {
  [[ -n "$SSL_EMAIL" ]] || return 0
  info "Requesting Let's Encrypt certificate"
  local args=(-d "$DOMAIN"); [[ -n "$ALIASES" ]] && args+=(-d "$ALIASES")
  $SUDO certbot --apache --non-interactive --agree-tos --keep-until-expiring --redirect \
        -m "$SSL_EMAIL" "${args[@]}" \
    && ok "HTTPS configured (${SITE_NAME}-le-ssl.conf); HTTP now redirects to HTTPS" \
    || warn "certbot failed — check that DNS for $DOMAIN points here and ports 80/443 are open."
}

# =============================================================================
# Phase 5 — verification
# =============================================================================
FAILS=0
pass()  { ok "$*"; }
fail()  { err "$*"; FAILS=$((FAILS+1)); }

# http_code <scheme> <path> [extra curl args] — requests via 127.0.0.1 with the vhost's name
http_code() {
  local scheme="$1" path="$2"; shift 2
  local port=80; [[ $scheme == https ]] && port=443
  curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
       --resolve "${DOMAIN}:${port}:127.0.0.1" "$@" "${scheme}://${DOMAIN}${path}"
}
expect() {  # expect <label> <expected-codes-regex> <scheme> <path> [curl args]
  local label="$1" want="$2" code; shift 2
  code="$(http_code "$@")" || true
  if [[ "$code" =~ ^(${want})$ ]]; then pass "$label → HTTP $code"; else fail "$label → HTTP $code (expected $want)"; fi
}

verify() {
  info "Verification"
  trap - ERR; set +e

  # Services
  if svc_active apache2; then pass "apache2 service is running"; else fail "apache2 service is not running"; fi
  local vf
  for vf in "$VHOST_FILE" "$SSL_VHOST_FILE"; do
    [[ -f "$vf" ]] || continue
    grep -Eq "^[[:space:]]*DocumentRoot[[:space:]]+\"?${WEB_ROOT}/?\"?[[:space:]]*\$" "$vf" \
      && pass "$(basename "$vf"): DocumentRoot is $WEB_ROOT" \
      || fail "$(basename "$vf"): DocumentRoot is not $WEB_ROOT (re-run setup.sh to move it)"
  done
  $SUDO apache2ctl -S 2>/dev/null | grep -q "namevhost ${DOMAIN} " \
    && pass "VirtualHost for ${DOMAIN} is loaded" || fail "No VirtualHost for ${DOMAIN} in apache2ctl -S"
  $SUDO apache2ctl -M 2>/dev/null | grep -q rewrite_module && pass "mod_rewrite loaded" || fail "mod_rewrite not loaded"
  $SUDO apache2ctl -M 2>/dev/null | grep -q php_module && pass "mod_php loaded" || fail "mod_php not loaded"

  # PHP + database, checked *through Apache* (mod_php) with a throw-away probe,
  # so we test the PHP that actually serves the site, not just the CLI.
  detect_php
  local probe=".tf-probe-$RANDOM$RANDOM.php" pout
  cat <<'PHP' | $SUDO tee "$WEB_ROOT/$probe" >/dev/null
<?php
header('Content-Type: text/plain');
$r = ['version=' . PHP_VERSION, 'sapi=' . PHP_SAPI];
foreach (['pdo_sqlite','mbstring','gd','curl','session','json'] as $e) $r[] = "ext_$e=" . (extension_loaded($e) ? 1 : 0);
$r[] = 'freetype=' . (function_exists('imagettftext') ? 1 : 0);
$r[] = 'shell_exec=' . ((function_exists('shell_exec') && !in_array('shell_exec', array_map('trim', explode(',', (string)ini_get('disable_functions'))), true)) ? 1 : 0);
$r[] = 'upload_max=' . ini_get('upload_max_filesize');
try {
    $root = dirname(__DIR__); // the probe sits in web/
    chdir($root); require 'includes/db.php';
    $db = get_db();
    $need = ['users','shares','places','boxes','items','barcode_items'];
    $have = $db->query("SELECT name FROM sqlite_master WHERE type='table'")->fetchAll(PDO::FETCH_COLUMN);
    $miss = array_diff($need, $have);
    $ok = !$miss && $db->query('PRAGMA integrity_check')->fetchColumn() === 'ok'
          && is_writable($root . '/data') && is_writable($root . '/data/database.sqlite');
    $r[] = 'db=' . ($ok ? 'ok' : ($miss ? 'missing tables: ' . implode(',', $miss) : 'not writable / integrity'));
} catch (Throwable $e) { $r[] = 'db=' . $e->getMessage(); }
echo implode("\n", $r), "\n";
PHP
  local ps=http pp=80; [[ -n "$SSL_EMAIL" ]] && { ps=https; pp=443; }
  pout="$(curl -s --max-time 10 --resolve "${DOMAIN}:${pp}:127.0.0.1" "${ps}://${DOMAIN}/${probe}")"
  $SUDO rm -f "$WEB_ROOT/$probe"
  kv() { sed -n "s/^$1=//p" <<<"$pout"; }
  if [[ -z "$(kv version)" ]]; then
    fail "PHP probe through Apache returned no data (is mod_php executing .php files?)"
  else
    local v; v="$(kv version)"
    [[ "$(kv sapi)" == apache2handler ]] && pass "Apache runs PHP $v via mod_php" || fail "Unexpected PHP SAPI under Apache: $(kv sapi)"
    php -r "exit(version_compare('$v','$PHP_MIN_VERSION','>=')?0:1);" 2>/dev/null \
      && pass "PHP $v >= $PHP_MIN_VERSION" || fail "PHP $v < $PHP_MIN_VERSION"
    local e
    for e in pdo_sqlite mbstring gd curl session json; do
      [[ "$(kv "ext_$e")" == 1 ]] && pass "PHP extension (Apache): $e" || fail "PHP extension missing under Apache: $e (apt install php${APACHE_PHP}-${e/pdo_/})"
    done
    [[ "$(kv freetype)" == 1 ]] && pass "GD has FreeType (crisp PNG labels)" || warn "GD without FreeType — PNG labels fall back to bitmap font"
    [[ "$(kv upload_max)" == 10M ]] && pass "upload_max_filesize = 10M (photo OCR uploads)" || warn "upload_max_filesize = $(kv upload_max)"
    if [[ $WITH_OCR -eq 1 ]]; then
      [[ "$(kv shell_exec)" == 1 ]] && pass "shell_exec() enabled (needed for OCR)" || fail "shell_exec() disabled under Apache — OCR tile will be hidden"
    fi
    [[ "$(kv db)" == ok ]] && pass "PHP ↔ SQLite via Apache: schema complete, database writable by $WEB_USER" \
      || fail "PHP ↔ SQLite via Apache: $(kv db)"
  fi
  if [[ $WITH_OCR -eq 1 ]]; then
    command -v tesseract >/dev/null && pass "tesseract: $(tesseract --version 2>&1 | head -1)" || fail "tesseract not found"
  fi

  # HTTP (through the vhost, by name, regardless of public DNS)
  local final
  final="$(curl -s -L -o /dev/null -w '%{http_code} %{url_effective}' --max-time 10 \
           --resolve "${DOMAIN}:80:127.0.0.1" "http://${DOMAIN}/")"
  if [[ -n "$SSL_EMAIL" ]]; then
    expect "http://${DOMAIN}/ redirects to HTTPS" "301|302" http /
    final="$(curl -s -L -o /dev/null -w '%{http_code} %{url_effective}' --max-time 10 \
             --resolve "${DOMAIN}:443:127.0.0.1" "https://${DOMAIN}/")"
  fi
  [[ "${final%% *}" == 200 ]] && pass "GET / → 200 (landed on ${final#* })" || fail "GET / → ${final}"
  local s=http; [[ -n "$SSL_EMAIL" ]] && s=https
  expect "Login/setup page"               "200"          $s /login -L
  expect "Static asset /assets/style.css" "200"          $s /assets/style.css
  expect "Pretty URL routing (/search)"   "200|302|303"  $s "/search?q=test"
  expect "JSON API without session"       "401|403"      $s /api/places
  # These live outside web/, so the app answers instead (a redirect to /login,
  # or 403/404) — never with the file itself.
  local hidden="302|303|403|404"
  expect "Not served: data/database.sqlite" "$hidden"    $s /data/database.sqlite
  expect "Not served: includes/db.php"      "$hidden"    $s /includes/db.php
  expect "Not served: api/src/scope.php"    "$hidden"    $s /api/src/scope.php
  expect "Not served: README.md"            "$hidden"    $s /README.md
  expect "Not served: .git/config"          "$hidden"    $s /.git/config
  expect "Not served: router.php"           "$hidden"    $s /router.php
  expect "Not served: .env"                 "$hidden"    $s /.env
  expect "Not served: vendor/autoload.php"  "$hidden"    $s /vendor/autoload.php
  expect "Not served: composer.json"        "$hidden"    $s /composer.json

  # Informational
  local pub; pub="$(getent hosts "$DOMAIN" | awk '{print $1}' | head -1)"
  [[ -n "$pub" ]] && ok "DNS: $DOMAIN resolves to $pub" || warn "DNS: $DOMAIN does not resolve from this machine yet"
  [[ -z "$SSL_EMAIL" ]] && warn "Serving plain HTTP. Camera barcode scanning needs HTTPS — re-run with --ssl you@example.com once DNS points here."

  echo
  if [[ $FAILS -eq 0 ]]; then echo "${C_G}${C_B}All checks passed.${C_0} thingsFinder is served at ${s}://${DOMAIN}/"; return 0
  else echo "${C_R}${C_B}${FAILS} check(s) failed.${C_0} See ${C_B}/var/log/apache2/${SITE_NAME}_error.log${C_0}"; return 1; fi
}

# =============================================================================
main() {
  echo "${C_B}thingsFinder setup — ${DOMAIN}${C_0}"
  if [[ $VERIFY_ONLY -eq 0 ]]; then
    check_os
    install_dependencies
    configure_permissions
    install_php_dependencies
    configure_env
    init_database
    configure_apache
    configure_local_hosts
    configure_ssl
  fi
  verify
}
main "$@"