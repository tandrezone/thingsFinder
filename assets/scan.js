/**
 * Optional camera barcode scanning for the "Add an item" form.
 *
 * Progressive enhancement only: the form works perfectly with no JavaScript
 * (type a barcode from a physical scanner, or just type an item name) —
 * this script just adds a "Scan" button on browsers that support the
 * native BarcodeDetector API and a camera, and auto-fills/auto-submits
 * when a known barcode is recognized.
 */
(function () {
  var hasDetector = 'BarcodeDetector' in window;
  var hasCamera = !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia);

  document.querySelectorAll('.add-item-form').forEach(function (form) {
    var wrap = form.closest('details');
    var barcodeInput = form.querySelector('input[name="barcode"]');
    var nameInput = form.querySelector('input[name="name"]');
    var scanBtn = form.querySelector('.scan-btn');
    var hint = wrap ? wrap.querySelector('.scan-hint') : null;
    var overlay = wrap ? wrap.querySelector('.scanner-overlay') : null;
    var video = overlay ? overlay.querySelector('video') : null;
    var cancelBtn = overlay ? overlay.querySelector('.scan-cancel') : null;
    if (!barcodeInput || !nameInput) {
      return;
    }

    if (hasDetector && hasCamera && scanBtn) {
      scanBtn.hidden = false;
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
          } else {
            setHint('New barcode — type a name below to remember it.', 'new');
            nameInput.focus();
          }
        })
        .catch(function () {
          setHint('Could not check that barcode — type a name to be safe.', 'warn');
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
