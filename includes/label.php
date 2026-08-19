<?php
/**
 * Printable label generator for a box: a small rectangular image with a
 * border, the box's QR code, its name, its place name, and a couple of
 * icons — sized for a label printer (default 50x30mm at 300dpi for the
 * PNG; the SVG is resolution-independent). Width/height/dpi are all
 * overridable via query params on the label.svg/label.png routes.
 *
 * The design is a small "tag": a double-line frame, a light accent chip
 * behind each icon, a dashed divider between the QR and the text column,
 * and a decorative corner hole — built from a couple of accent colors that
 * match the app's own palette (includes/../assets/style.css --accent).
 * Everything decorative stays high-contrast enough to still look right on
 * a monochrome thermal printer, where the accent color just prints as
 * solid black/gray.
 *
 * Two formats:
 *   - SVG: vector, scales to any size perfectly, and is what most
 *     label-printer software (Dymo Connect, Brother P-touch, NIIMBOT apps,
 *     etc.) and browsers can print directly. Text uses a normal CSS
 *     font-family, rendered by whatever is printing the SVG.
 *   - PNG: rasterized via GD, for tools that only accept a bitmap image.
 *     Text is drawn with the vendored Liberation Sans TrueType font
 *     (assets/fonts/) when GD's FreeType support is available, so it's
 *     crisp on any server regardless of what system fonts are installed;
 *     otherwise it falls back to GD's built-in bitmap font so PNG export
 *     still works with zero configuration.
 */

require_once __DIR__ . '/qrcode.php';
require_once __DIR__ . '/helpers.php';

// Same palette as assets/style.css (--text / --accent / --accent-soft), so
// the printed label feels like it belongs to the rest of the app.
const LABEL_INK = '#2b2620';
const LABEL_MUTED = '#5b5245';
const LABEL_ACCENT = '#b5652b';
const LABEL_ACCENT_SOFT = '#f4e4d5';

function mm_to_px(float $mm, int $dpi): int
{
    return (int)round($mm / 25.4 * $dpi);
}

/** The vendored TrueType font, or null if it's missing or GD lacks FreeType support. */
function label_find_ttf(bool $bold = false): ?string
{
    if (!function_exists('imagettftext')) {
        return null;
    }
    $vendored = __DIR__ . '/../assets/fonts/LiberationSans-' . ($bold ? 'Bold' : 'Regular') . '.ttf';
    if (is_file($vendored)) {
        return $vendored;
    }
    // Fall back to common system locations in case the assets/fonts copy
    // was removed — still optional, PNG export degrades gracefully without it.
    foreach ([
        $bold ? '/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf' : '/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf',
        $bold ? '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf' : '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',
    ] as $path) {
        if (is_file($path)) {
            return $path;
        }
    }
    return null;
}

/** Best-effort text truncation for the SVG label, using an average character-width estimate (no real text metrics available server-side without a browser). */
function svg_truncate(string $text, float $maxWidthMm, float $fontSizeMm, float $avgCharWidthFactor = 0.55): string
{
    if ($maxWidthMm <= 0 || $text === '') {
        return $text;
    }
    $maxChars = max(1, (int)floor($maxWidthMm / ($fontSizeMm * $avgCharWidthFactor)));
    if (mb_strlen($text, 'UTF-8') <= $maxChars) {
        return $text;
    }
    if ($maxChars <= 1) {
        return mb_substr($text, 0, 1, 'UTF-8') . '…';
    }
    return mb_substr($text, 0, $maxChars - 1, 'UTF-8') . '…';
}

/**
 * Picks the largest font size (down to $minFontSize) that lets $text fit
 * within $maxWidthMm without truncating, using the same average
 * character-width estimate as svg_truncate(). Small labels still truncate
 * long names at the floor size — there's only so much room on a 50x30mm
 * label — but this "shrink first" step avoids truncating names that would
 * fit fine at a slightly smaller size.
 */
function svg_fit_font_size(string $text, float $maxWidthMm, float $maxFontSize, float $minFontSize, float $avgCharWidthFactor = 0.55): float
{
    $len = max(1, mb_strlen($text, 'UTF-8'));
    $fitted = $maxWidthMm / ($len * $avgCharWidthFactor);
    return max($minFontSize, min($maxFontSize, $fitted));
}

/** A light accent-tinted circle "chip" sitting behind an icon, for a bit of visual pop. */
function label_chip_svg(float $cx, float $cy, float $r): string
{
    return '<circle cx="' . $cx . '" cy="' . $cy . '" r="' . $r . '" fill="' . LABEL_ACCENT_SOFT . '"/>';
}

/** Solid (filled, not outlined) box glyph — bolder and more legible at label sizes than a thin-stroke icon. */
function label_box_icon_svg(float $x, float $y, float $size): string
{
    $s = $size / 24;
    return '<g transform="translate(' . $x . ',' . $y . ') scale(' . $s . ')" fill="' . LABEL_INK . '">'
        . '<rect x="2" y="3" width="20" height="5.5" rx="1"/>'
        . '<rect x="3" y="10" width="18" height="11" rx="1.5"/>'
        . '<rect x="10.4" y="10" width="3.2" height="4.5" fill="' . LABEL_ACCENT_SOFT . '"/>'
        . '</g>';
}

/** Solid map-pin glyph (teardrop + punched hole), reusing the outline icon's path but filled. */
function label_pin_icon_svg(float $x, float $y, float $size): string
{
    $s = $size / 24;
    $paths = icon_paths();
    return '<g transform="translate(' . $x . ',' . $y . ') scale(' . $s . ')">'
        . '<path d="M12 22s7-7.58 7-12A7 7 0 0 0 5 10c0 4.42 7 12 7 12z" fill="' . LABEL_INK . '"/>'
        . '<circle cx="12" cy="10" r="3" fill="#ffffff"/>'
        . '</g>';
}

/**
 * Builds the label as a standalone <svg>...</svg> string, in millimeter
 * user units (1 unit = 1mm), so the physical print size is exact.
 */
function label_svg_markup(string $qrUrl, string $boxName, string $placeName, float $widthMm, float $heightMm): string
{
    $widthMm = max(10.0, $widthMm);
    $heightMm = max(10.0, $heightMm);
    $short = min($widthMm, $heightMm);
    $margin = max(1.0, min(4.0, $short * 0.08));
    $gap = max(1.0, $short * 0.05);
    $borderR = min(2.0, $margin);

    $qrSize = $heightMm - 2 * $margin;
    $qrX = $margin;
    $qrY = $margin;

    $textX = $qrX + $qrSize + $gap;
    $dividerX = $textX - $gap / 2;

    $nameFontMax = max(2.4, min(6.0, $heightMm * 0.17));
    $placeFontMax = max(1.9, min(4.5, $heightMm * 0.115));
    $nameFontMin = max(1.7, $nameFontMax * 0.5);
    $placeFontMin = max(1.4, $placeFontMax * 0.5);
    // Icon size and row positions are pinned to the max font size so the
    // two rows keep a consistent height even if the text itself shrinks
    // to fit a long name.
    $iconSize = $nameFontMax * 0.95;
    $pinSize = $iconSize * 0.85;
    $textStartX = $textX + $iconSize + $gap * 0.7;
    $textAvailWidth = max(2.0, $widthMm - $textStartX - $margin);

    $nameY = $margin + $nameFontMax * 0.95;
    $placeY = $nameY + $placeFontMax + $gap * 0.9;

    $nameFontSize = svg_fit_font_size($boxName, $textAvailWidth, $nameFontMax, $nameFontMin, 0.58);
    $placeFontSize = svg_fit_font_size($placeName, $textAvailWidth, $placeFontMax, $placeFontMin, 0.55);
    $boxNameDisp = svg_truncate($boxName, $textAvailWidth, $nameFontSize, 0.58);
    $placeNameDisp = svg_truncate($placeName, $textAvailWidth, $placeFontSize, 0.55);

    // Icon chips + glyphs. Chip radius/position is centered on each icon's
    // (0,0)-(size,size) box.
    $boxChipR = $iconSize * 0.62;
    $boxChipCx = $textX + $iconSize / 2;
    $boxChipCy = ($nameY - $iconSize * 0.85) + $iconSize / 2;
    $pinChipR = $pinSize * 0.62;
    $pinChipCx = $textX + $iconSize / 2;
    $pinChipCy = ($placeY - $pinSize * 0.85) + $pinSize / 2;

    $boxIcon = label_chip_svg($boxChipCx, $boxChipCy, $boxChipR) . label_box_icon_svg($textX, $nameY - $iconSize * 0.85, $iconSize);
    $pinIcon = label_chip_svg($pinChipCx, $pinChipCy, $pinChipR)
        . label_pin_icon_svg($textX + ($iconSize - $pinSize) / 2, $placeY - $pinSize * 0.85, $pinSize);

    // Decorative corner hole (like a tag punch) — only if there's room
    // below the place-name row so it never overlaps the text.
    $holeR = min(1.6, $margin * 0.55);
    $holeCx = $widthMm - $margin * 1.3;
    $holeCy = $heightMm - $margin * 1.3;
    $hole = '';
    if ($holeCy - $holeR > $placeY + 1.2 && $holeCx - $holeR > $textStartX) {
        $hole = '<circle cx="' . $holeCx . '" cy="' . $holeCy . '" r="' . $holeR . '" fill="#ffffff" stroke="' . LABEL_ACCENT . '" stroke-width="0.35"/>';
    }

    $matPad = min(0.6, $margin * 0.3);
    $qrMat = '<rect x="' . ($qrX - $matPad) . '" y="' . ($qrY - $matPad) . '" '
        . 'width="' . ($qrSize + $matPad * 2) . '" height="' . ($qrSize + $matPad * 2) . '" '
        . 'rx="1" fill="none" stroke="' . LABEL_ACCENT . '" stroke-width="0.35"/>';

    $divider = '<line x1="' . $dividerX . '" y1="' . ($margin + 0.6) . '" x2="' . $dividerX . '" y2="' . ($heightMm - $margin - 0.6) . '" '
        . 'stroke="' . LABEL_ACCENT . '" stroke-width="0.35" stroke-dasharray="1.3,1.3" stroke-linecap="round"/>';

    $qrInner = qrcode_svg_markup($qrUrl, 4);

    return '<svg xmlns="http://www.w3.org/2000/svg" width="' . $widthMm . 'mm" height="' . $heightMm . 'mm" '
        . 'viewBox="0 0 ' . $widthMm . ' ' . $heightMm . '">'
        . '<rect x="0" y="0" width="' . $widthMm . '" height="' . $heightMm . '" fill="#ffffff"/>'
        . '<rect x="' . ($margin * 0.4) . '" y="' . ($margin * 0.4) . '" '
        . 'width="' . ($widthMm - $margin * 0.8) . '" height="' . ($heightMm - $margin * 0.8) . '" '
        . 'rx="' . $borderR . '" fill="none" stroke="' . LABEL_INK . '" stroke-width="0.7"/>'
        . '<rect x="' . ($margin * 0.4 + 0.9) . '" y="' . ($margin * 0.4 + 0.9) . '" '
        . 'width="' . ($widthMm - $margin * 0.8 - 1.8) . '" height="' . ($heightMm - $margin * 0.8 - 1.8) . '" '
        . 'rx="' . ($borderR * 0.7) . '" fill="none" stroke="' . LABEL_ACCENT . '" stroke-width="0.3"/>'
        . $divider
        . $qrMat
        . '<svg x="' . $qrX . '" y="' . $qrY . '" width="' . $qrSize . '" height="' . $qrSize . '">' . $qrInner . '</svg>'
        . $boxIcon
        . '<text x="' . $textStartX . '" y="' . $nameY . '" font-family="Liberation Sans, Arial, sans-serif" '
        . 'font-size="' . $nameFontSize . '" font-weight="700" fill="' . LABEL_INK . '">' . h($boxNameDisp) . '</text>'
        . $pinIcon
        . '<text x="' . $textStartX . '" y="' . $placeY . '" font-family="Liberation Sans, Arial, sans-serif" '
        . 'font-size="' . $placeFontSize . '" fill="' . LABEL_MUTED . '">' . h($placeNameDisp) . '</text>'
        . $hole
        . '</svg>';
}

function label_send_svg(string $qrUrl, string $boxName, string $placeName, float $widthMm, float $heightMm): void
{
    header('Content-Type: image/svg+xml');
    header('Cache-Control: no-store');
    echo label_svg_markup($qrUrl, $boxName, $placeName, $widthMm, $heightMm);
    exit;
}

/** Font size (in GD's "points") that renders roughly $desiredPx tall for the given font. */
function label_calibrate_pt(string $font, float $desiredPx): float
{
    $bbox = @imagettfbbox(100.0, 0, $font, 'Hg');
    if ($bbox === false) {
        return max(4.0, $desiredPx * 0.84);
    }
    $h = abs($bbox[7] - $bbox[1]);
    if ($h <= 0) {
        return max(4.0, $desiredPx * 0.84);
    }
    return max(4.0, $desiredPx * (100.0 / $h));
}

function ttf_measure_width(string $text, string $font, float $pt): float
{
    if ($text === '') {
        return 0.0;
    }
    $bbox = @imagettfbbox($pt, 0, $font, $text);
    return $bbox === false ? 0.0 : abs($bbox[2] - $bbox[0]);
}

/**
 * Largest point size (down to $minPt) at which $text fits within
 * $maxWidthPx, checked with real font metrics (unlike svg_fit_font_size's
 * character-count estimate, GD gives us exact glyph measurements here).
 */
function ttf_fit_pt(string $text, float $maxWidthPx, string $font, float $maxPt, float $minPt): float
{
    if ($text === '' || ttf_measure_width($text, $font, $maxPt) <= $maxWidthPx) {
        return $maxPt;
    }
    $pt = $maxPt;
    while ($pt > $minPt) {
        $pt -= 1.0;
        if (ttf_measure_width($text, $font, $pt) <= $maxWidthPx) {
            return $pt;
        }
    }
    return $minPt;
}

/** Truncates $text (adding an ellipsis) so it fits within $maxWidthPx when rendered with $font at $pt. */
function ttf_truncate(string $text, float $maxWidthPx, string $font, float $pt): string
{
    if ($text === '' || ttf_measure_width($text, $font, $pt) <= $maxWidthPx) {
        return $text;
    }
    $chars = preg_split('//u', $text, -1, PREG_SPLIT_NO_EMPTY) ?: [];
    $result = '';
    foreach ($chars as $ch) {
        if (ttf_measure_width($result . $ch . '…', $font, $pt) > $maxWidthPx) {
            break;
        }
        $result .= $ch;
    }
    return $result === '' ? mb_substr($text, 0, 1, 'UTF-8') . '…' : $result . '…';
}

/** Same idea as ttf_truncate, for GD's built-in bitmap font (fixed-width, ASCII only). */
function gd_truncate(string $text, float $maxWidthPx, int $fontIndex): string
{
    $charWidth = imagefontwidth($fontIndex);
    $maxChars = max(1, (int)floor($maxWidthPx / max(1, $charWidth)));
    if (strlen($text) <= $maxChars) {
        return $text;
    }
    return $maxChars > 1 ? substr($text, 0, $maxChars - 1) . '.' : substr($text, 0, 1);
}

/** Allocates (or reuses) a GD color from a "#rrggbb" hex string. */
function label_gd_color($image, string $hex)
{
    $hex = ltrim($hex, '#');
    $r = hexdec(substr($hex, 0, 2));
    $g = hexdec(substr($hex, 2, 2));
    $b = hexdec(substr($hex, 4, 2));
    return imagecolorallocate($image, $r, $g, $b);
}

/** A light accent-tinted circle "chip" behind an icon — the PNG counterpart of label_chip_svg(). */
function draw_icon_chip($image, int $cx, int $cy, int $r, int $color): void
{
    imagefilledellipse($image, $cx, $cy, $r * 2, $r * 2, $color);
}

/** A solid (filled) GD-drawn box glyph — lid + body + a light notch, bolder than an outline at small sizes. */
function draw_box_icon($image, int $x, int $y, int $size, int $inkColor, int $chipColor): void
{
    $size = max(6, $size);
    $lidH = (int)round($size * 0.26);
    imagefilledrectangle($image, $x, $y, $x + $size, $y + $lidH, $inkColor);
    imagefilledrectangle($image, $x + 1, $y + $lidH + 1, $x + $size, $y + $size, $inkColor);
    $notchW = max(2, (int)round($size * 0.18));
    $notchX = $x + (int)round(($size - $notchW) / 2);
    imagefilledrectangle($image, $notchX, $y + $lidH + 1, $notchX + $notchW, $y + $lidH + 1 + (int)round($size * 0.22), $chipColor);
}

/** A solid (filled) GD-drawn map-pin glyph (teardrop + punched hole). */
function draw_pin_icon($image, int $x, int $y, int $size, int $inkColor): void
{
    $size = max(6, $size);
    $white = imagecolorallocate($image, 255, 255, 255);
    $r = (int)round($size * 0.34);
    $cx = $x + (int)round($size / 2);
    $cy = $y + $r + 1;
    imagefilledellipse($image, $cx, $cy, $r * 2, $r * 2, $inkColor);
    $points = [
        $cx - (int)round($r * 0.85), $cy + (int)round($r * 0.35),
        $cx + (int)round($r * 0.85), $cy + (int)round($r * 0.35),
        $cx, $y + $size,
    ];
    if (PHP_VERSION_ID >= 80100) {
        imagefilledpolygon($image, $points, $inkColor);
    } else {
        imagefilledpolygon($image, $points, (int)(count($points) / 2), $inkColor);
    }
    imagefilledellipse($image, $cx, $cy, (int)round($r * 0.9), (int)round($r * 0.9), $white);
}

/** Draws a dashed vertical line — GD has no native dash style, so this walks short segments. */
function draw_dashed_vline($image, int $x, int $y1, int $y2, int $color, int $dash = 6, int $gapPx = 6): void
{
    $y = $y1;
    while ($y < $y2) {
        $end = min($y + $dash, $y2);
        imageline($image, $x, $y, $x, $end, $color);
        $y = $end + $gapPx;
    }
}

function label_send_png(string $qrUrl, string $boxName, string $placeName, float $widthMm, float $heightMm, int $dpi = 300): void
{
    if (!function_exists('imagecreatetruecolor')) {
        http_response_code(501);
        header('Content-Type: text/plain');
        echo 'PNG output requires the GD extension, which is not available on this server. Use the SVG version instead.';
        exit;
    }

    $widthMm = max(10.0, $widthMm);
    $heightMm = max(10.0, $heightMm);
    $dpi = max(72, min(1200, $dpi));

    $w = mm_to_px($widthMm, $dpi);
    $h = mm_to_px($heightMm, $dpi);
    $short = min($w, $h);
    $marginPx = max(mm_to_px(1.0, $dpi), min(mm_to_px(4.0, $dpi), (int)round($short * 0.08)));
    $gapPx = max(mm_to_px(1.0, $dpi), (int)round($short * 0.05));

    $image = imagecreatetruecolor($w, $h);
    imageantialias($image, true);
    $white = imagecolorallocate($image, 255, 255, 255);
    $ink = label_gd_color($image, LABEL_INK);
    $muted = label_gd_color($image, LABEL_MUTED);
    $accent = label_gd_color($image, LABEL_ACCENT);
    $accentSoft = label_gd_color($image, LABEL_ACCENT_SOFT);
    imagefilledrectangle($image, 0, 0, $w, $h, $white);

    // Double-line frame: outer ink border, inner accent border.
    $borderInset = (int)round($marginPx * 0.4);
    imagesetthickness($image, max(1, mm_to_px(0.7, $dpi)));
    imagerectangle($image, $borderInset, $borderInset, $w - 1 - $borderInset, $h - 1 - $borderInset, $ink);
    $accentInset = $borderInset + mm_to_px(0.9, $dpi);
    imagesetthickness($image, max(1, mm_to_px(0.3, $dpi)));
    imagerectangle($image, $accentInset, $accentInset, $w - 1 - $accentInset, $h - 1 - $accentInset, $accent);

    $qrSizePx = $h - 2 * $marginPx;
    $matPad = max(1, mm_to_px(0.5, $dpi));
    imagerectangle($image, $marginPx - $matPad, $marginPx - $matPad, $marginPx + $qrSizePx + $matPad, $marginPx + $qrSizePx + $matPad, $accent);

    $qr = build_qrcode($qrUrl);
    $moduleSize = 4;
    $qrRaw = $qr->createImage($moduleSize, $moduleSize * 2);
    $rawSize = imagesx($qrRaw);
    imagecopyresampled($image, $qrRaw, $marginPx, $marginPx, 0, 0, $qrSizePx, $qrSizePx, $rawSize, $rawSize);
    imagedestroy($qrRaw);

    $textX = $marginPx + $qrSizePx + $gapPx;
    $dividerX = $textX - (int)round($gapPx / 2);
    imagesetthickness($image, max(1, mm_to_px(0.3, $dpi)));
    draw_dashed_vline($image, $dividerX, $marginPx + (int)round($gapPx * 0.3), $h - $marginPx - (int)round($gapPx * 0.3), $accent, max(3, mm_to_px(0.5, $dpi)), max(3, mm_to_px(0.5, $dpi)));

    $nameFontPxMax = (int)round(max(mm_to_px(2.4, $dpi), min(mm_to_px(6.0, $dpi), $h * 0.17)));
    $placeFontPxMax = (int)round(max(mm_to_px(1.9, $dpi), min(mm_to_px(4.5, $dpi), $h * 0.115)));
    $iconSizePx = max(6, (int)round($nameFontPxMax * 0.95));
    $pinSizePx = max(6, (int)round($iconSizePx * 0.85));

    $nameBaselineY = $marginPx + (int)round($nameFontPxMax * 0.95);
    $placeBaselineY = $nameBaselineY + $placeFontPxMax + (int)round($gapPx * 0.9);

    $textStartX = $textX + $iconSizePx + (int)round($gapPx * 0.7);
    $availTextWidthPx = max(1, $w - $textStartX - $marginPx);

    // Icon chips, drawn before the icons/text so the icons sit on top.
    $boxIconY = $nameBaselineY - $iconSizePx;
    $pinIconY = $placeBaselineY - $pinSizePx;
    draw_icon_chip($image, $textX + (int)round($iconSizePx / 2), $boxIconY + (int)round($iconSizePx / 2), (int)round($iconSizePx * 0.62), $accentSoft);
    draw_icon_chip($image, $textX + (int)round($iconSizePx / 2), $pinIconY + (int)round($pinSizePx / 2), (int)round($pinSizePx * 0.62), $accentSoft);

    $ttfBold = label_find_ttf(true);
    $ttfRegular = label_find_ttf(false);

    if ($ttfBold) {
        $ptMax = label_calibrate_pt($ttfBold, (float)$nameFontPxMax);
        $ptMin = label_calibrate_pt($ttfBold, (float)$nameFontPxMax * 0.5);
        $pt = ttf_fit_pt($boxName, $availTextWidthPx, $ttfBold, $ptMax, $ptMin);
        $disp = ttf_truncate($boxName, $availTextWidthPx, $ttfBold, $pt);
        imagettftext($image, $pt, 0, $textStartX, $nameBaselineY, $ink, $ttfBold, $disp);
    } else {
        $disp = gd_truncate($boxName, $availTextWidthPx, 5);
        imagestring($image, 5, $textStartX, $nameBaselineY - 13, $disp, $ink);
    }
    if ($ttfRegular) {
        $ptMax = label_calibrate_pt($ttfRegular, (float)$placeFontPxMax);
        $ptMin = label_calibrate_pt($ttfRegular, (float)$placeFontPxMax * 0.5);
        $pt = ttf_fit_pt($placeName, $availTextWidthPx, $ttfRegular, $ptMax, $ptMin);
        $disp = ttf_truncate($placeName, $availTextWidthPx, $ttfRegular, $pt);
        imagettftext($image, $pt, 0, $textStartX, $placeBaselineY, $muted, $ttfRegular, $disp);
    } else {
        $disp = gd_truncate($placeName, $availTextWidthPx, 3);
        imagestring($image, 3, $textStartX, $placeBaselineY - 10, $disp, $muted);
    }

    draw_box_icon($image, $textX, $boxIconY, $iconSizePx, $ink, $accentSoft);
    draw_pin_icon($image, $textX + (int)round(($iconSizePx - $pinSizePx) / 2), $pinIconY, $pinSizePx, $ink);

    // Decorative corner hole, mirroring the SVG version — only if it clears the text.
    $holeR = min(mm_to_px(1.6, $dpi), (int)round($marginPx * 0.55));
    $holeCx = $w - (int)round($marginPx * 1.3);
    $holeCy = $h - (int)round($marginPx * 1.3);
    if ($holeCy - $holeR > $placeBaselineY + mm_to_px(1.2, $dpi) && $holeCx - $holeR > $textStartX) {
        imagefilledellipse($image, $holeCx, $holeCy, $holeR * 2, $holeR * 2, $white);
        imagesetthickness($image, max(1, mm_to_px(0.35, $dpi)));
        imageellipse($image, $holeCx, $holeCy, $holeR * 2, $holeR * 2, $accent);
    }

    header('Content-Type: image/png');
    header('Cache-Control: no-store');
    imagepng($image);
    imagedestroy($image);
    exit;
}
