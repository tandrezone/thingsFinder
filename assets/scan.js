/**
 * Optional camera barcode scanning for the "Add an item" form.
 *
 * Progressive enhancement only: the form works perfectly with no JavaScript
 * (type a barcode from a physical scanner, or just type an item name) —
 * this script just adds a "Scan" button on browsers that support the
 * native BarcodeDetector API and a camera, auto-fills/auto-submits when a
 * known barcode is recognized, and otherwise suggests a name from a free
 * product-lookup API for the person to confirm or edit before saving.
 */
(function () {
  var hasDetector = 'BarcodeDetector' in window;
  var hasCamera = !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia);
  // Browsers only expose the camera on a "secure context": https://, or
  // http://localhost. A self-hosted app reached over plain http://<lan-ip>
  // fails hasCamera for exactly this reason — window.isSecureContext (a
  // standard browser API, not a guess) tells us whether that's the cause.
  var isSecure = !!window.isSecureContext;

  document.querySelectorAll('.add-item-form').forEach(function (form) {
    var wrap = form.closest('.add-card-inner') || form.closest('details');
    var barcodeInput = form.querySelector('input[name="barcode"]');
    var nameInput = form.querySelector('input[name="name"]');
    var scanBtn = form.querySelector('.scan-btn');
    var hint = wrap ? wrap.querySelector('.scan-hint') : null;
    var supportNote = wrap ? wrap.querySelector('.scan-support-note') : null;
    var overlay = wrap ? wrap.querySelector('.scanner-overlay') : null;
    var video = overlay ? overlay.querySelector('video') : null;
    var cancelBtn = overlay ? overlay.querySelector('.scan-cancel') : null;
    if (!barcodeInput || !nameInput) {
      return;
    }

    if (hasDetector && hasCamera && scanBtn) {
      scanBtn.hidden = false;
    } else if (supportNote) {
      // Explain *why* the Scan button is missing instead of just omitting
      // it silently — this is the single most common support question.
      if (!isSecure) {
        supportNote.textContent = 'Camera scanning needs this page to be loaded over HTTPS (or "localhost") — '
          + 'browsers block camera access on plain http:// for anything else, which is likely how you\'re '
          + 'reaching this app. You can still type the barcode below.';
      } else if (!hasDetector) {
        supportNote.textContent = 'This browser doesn\'t support in-page barcode scanning (works on Chrome/Edge '
          + 'on Android; not yet on Safari/iOS). You can still type the barcode below.';
      } else {
        supportNote.textContent = 'Camera access isn\'t available on this device or was denied. '
          + 'You can still type the barcode below.';
      }
      supportNote.hidden = false;
    }

    var stream = null;
    var detector = null;
    var rafId = null;

    function setHint(text, flavor) {
      if (!hint) {
        return;
      }
      hint.textContent = text || '';
      hint.hidden = !text;
      hint.classList.toggle('scan-hint-new', flavor === 'new');
      hint.classList.toggle('scan-hint-warn', flavor === 'warn');
    }

    function stopScan() {
      if (rafId) {
        cancelAnimationFrame(rafId);
      }
      rafId = null;
      if (stream) {
        stream.getTracks().forEach(function (track) { track.stop(); });
        stream = null;
      }
      if (overlay) {
        overlay.hidden = true;
      }
    }

    function tick() {
      if (!stream || !detector) {
        return;
      }
      detector.detect(video).then(function (codes) {
        if (codes.length) {
          var code = codes[0].rawValue;
          stopScan();
          barcodeInput.value = code;
          lookupBarcode(code, true);
        } else {
          rafId = requestAnimationFrame(tick);
        }
      }).catch(function () {
        rafId = requestAnimationFrame(tick);
      });
    }

    function startScan() {
      if (!overlay || !video) {
        return;
      }
      try {
        detector = detector || new BarcodeDetector({
          formats: ['ean_13', 'ean_8', 'upc_a', 'upc_e', 'code_128', 'code_39', 'qr_code'],
        });
      } catch (err) {
        setHint('Barcode scanning is not available in this browser — type it instead.', 'warn');
        return;
      }
      navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
        .then(function (s) {
          stream = s;
          video.srcObject = stream;
          overlay.hidden = false;
          return video.play();
        })
        .then(function () {
          tick();
        })
        .catch(function () {
          stopScan();
          setHint('Could not access the camera — you can still type the barcode.', 'warn');
        });
    }

    function lookupBarcode(code, autoAddIfKnown) {
      setHint('Looking up barcode…', null);
      fetch('/api/barcodes/' + encodeURIComponent(code))
        .then(function (res) {
          if (res.status === 404) {
            return null;
          }
          if (!res.ok) {
            throw new Error('lookup failed');
          }
          return res.json();
        })
        .then(function (data) {
          if (data && data.barcode) {
            nameInput.value = data.barcode.name;
            if (autoAddIfKnown) {
              setHint('Known item — adding "' + data.barcode.name + '"…', null);
              form.submit();
            } else {
              setHint('Known item — "' + data.barcode.name + '". Tap Add to add it.', null);
            }
            return;
          }
          // Not in our own register yet — try a free product-name lookup
          // before asking the person to type one from scratch. This never
          // auto-submits: it's a suggestion, not a confirmed match, so it
          // always waits for a human to confirm or edit it first.
          suggestFromExternalLookup(code);
        })
        .catch(function () {
          setHint('Could not check that barcode — type a name to be safe.', 'warn');
          nameInput.focus();
        });
    }

    function suggestFromExternalLookup(code) {
      setHint('Checking barcode databases…', null);
      fetch('/api/lookup/' + encodeURIComponent(code))
        .then(function (res) {
          if (!res.ok) {
            throw new Error('lookup failed');
          }
          return res.json();
        })
        .then(function (data) {
          if (data && data.name) {
            nameInput.value = data.name;
            nameInput.focus();
            nameInput.select();
            setHint('Found "' + data.name + '" (via ' + data.source + ') — check it, then tap Add to save it.', 'new');
          } else {
            setHint('New barcode — type a name below to remember it.', 'new');
            nameInput.focus();
          }
        })
        .catch(function () {
          setHint('New barcode — type a name below to remember it.', 'new');
          nameInput.focus();
        });
    }

    if (scanBtn) {
      scanBtn.addEventListener('click', startScan);
    }
    if (cancelBtn) {
      cancelBtn.addEventListener('click', stopScan);
    }
    barcodeInput.addEventListener('change', function () {
      var code = barcodeInput.value.trim();
      if (code) {
        lookupBarcode(code, false);
      } else {
        setHint('', null);
      }
    });
  });
})();
