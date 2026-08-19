<?php
/**
 * Thin wrapper around the vendored QRCode library (includes/qrcode-lib.php)
 * for the specific thing we need: turn a URL into a QR code, as inline SVG
 * (works everywhere, no GD needed) or as a downloadable SVG/PNG file.
 */

require_once __DIR__ . '/qrcode-lib.php';

/** Build a QRCode object sized to fit $data at the given error-correction level. */
function build_qrcode(string $data, string $ecLevel = 'M'): QRCode
{
    $levels = [
        'L' => QR_ERROR_CORRECT_LEVEL_L,
        'M' => QR_ERROR_CORRECT_LEVEL_M,
        'Q' => QR_ERROR_CORRECT_LEVEL_Q,
        'H' => QR_ERROR_CORRECT_LEVEL_H,
    ];
    $level = $levels[$ecLevel] ?? QR_ERROR_CORRECT_LEVEL_M;
    return QRCode::getMinimumQRCode($data, $level);
}

/** Returns a standalone <svg>...</svg> string for $data, safe to echo inline in HTML. */
function qrcode_svg_markup(string $data, int $moduleSize = 4): string
{
    $qr = build_qrcode($data);
    ob_start();
    $qr->printSVG($moduleSize);
    return ob_get_clean();
}

/** Sends $data as a downloadable image/svg+xml response and exits. */
function qrcode_send_svg(string $data, int $moduleSize = 8): void
{
    header('Content-Type: image/svg+xml');
    header('Cache-Control: no-store');
    echo qrcode_svg_markup($data, $moduleSize);
    exit;
}

/** Sends $data as a downloadable image/png response and exits. Requires GD. */
function qrcode_send_png(string $data, int $moduleSize = 8): void
{
    if (!function_exists('imagecreatetruecolor')) {
        http_response_code(501);
        header('Content-Type: text/plain');
        echo 'PNG output requires the GD extension, which is not available on this server. Use the SVG version instead.';
        exit;
    }
    $qr = build_qrcode($data);
    $image = $qr->createImage($moduleSize, $moduleSize * 2);
    header('Content-Type: image/png');
    header('Cache-Control: no-store');
    imagepng($image);
    imagedestroy($image);
    exit;
}
