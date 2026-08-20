/**
 * "Copy" buttons — progressive enhancement for anything with
 * data-copy-target="<id of an input/textarea>".
 *
 * Without JavaScript the textarea is still there to select by hand, so the
 * button starts hidden-in-spirit (it just does nothing useful) and this
 * script is what makes it work. navigator.clipboard needs a secure context
 * (https, or localhost), so on plain http:// over a LAN we fall back to
 * selecting the text and telling the person to press Ctrl/Cmd+C.
 */
(function () {
  var canWrite = !!(navigator.clipboard && navigator.clipboard.writeText && window.isSecureContext);

  function status(btn, message, ok) {
    var wrap = btn.closest('.import-prompt') || btn.parentNode;
    var el = wrap ? wrap.querySelector('.copy-status') : null;
    if (!el) {
      return;
    }
    el.textContent = message;
    el.classList.toggle('is-ok', !!ok);
    window.setTimeout(function () {
      if (el.textContent === message) {
        el.textContent = '';
        el.classList.remove('is-ok');
      }
    }, 4000);
  }

  document.querySelectorAll('.copy-btn').forEach(function (btn) {
    var target = document.getElementById(btn.getAttribute('data-copy-target') || '');
    if (!target) {
      return;
    }
    btn.addEventListener('click', function () {
      var label = btn.querySelector('.copy-btn-label');
      if (!canWrite) {
        target.focus();
        target.select();
        status(btn, 'Selected — press Ctrl/Cmd+C to copy.', false);
        return;
      }
      navigator.clipboard.writeText(target.value).then(function () {
        status(btn, 'Copied. Paste it into your LLM with a photo attached.', true);
        if (label) {
          var was = label.textContent;
          label.textContent = 'Copied';
          window.setTimeout(function () { label.textContent = was; }, 2000);
        }
      }).catch(function () {
        target.focus();
        target.select();
        status(btn, 'Couldn\'t copy automatically — press Ctrl/Cmd+C.', false);
      });
    });
  });
})();
