/* Local gallery only. No speech demo, clipboard, storage, account or network API. */
(() => {
  if (typeof document === 'undefined') {
    if (process.argv.includes('--self-test')) {
      const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
      const html = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');
      const readme = fs.readFileSync(path.join(__dirname, '..', 'README.md'), 'utf8');
      const apk = 'https://github.com/ihearttokyo/Lip/releases/download/v0.5.0-preview.3-offline/Lip-0.5.0-preview.3-offline-universal.apk';
      assert(html.includes(apk) && readme.includes(apk));
      assert(html.includes('50003') && readme.includes('50003'));
      for (const key of ['keyboard', 'emoji', 'gifs']) {
        assert(html.includes(`id="capture-${key}"`));
        assert(fs.statSync(path.join(__dirname, `assets/screens/${key}.png`)).size > 0);
      }
      assert(!html.includes('No qualified release download is offered here'));
      assert(html.includes('not verified or enabled') && html.includes('small installer'));
      console.log('PASS: release identity, download links, gallery assets and honest status');
    }
    return;
  }
  const captures = {
    keyboard: { title: 'English keyboard', detail: 'Actual Android emulator capture · development build · synthetic editor. The page shows a keyboard detail; this is the unmodified full capture.', src: 'assets/screens/keyboard.png', alt: 'Full native Android development screenshot of Lip’s English keyboard and synthetic editor.' },
    emoji: { title: 'Emoji skin-tone choices', detail: 'Actual Android emulator capture · development build · synthetic editor. Unicode glyphs are drawn by Android’s font.', src: 'assets/screens/emoji.png', alt: 'Full native Android development screenshot of six thumbs-up skin-tone choices in Lip.' },
    gifs: { title: 'Local GIF search', detail: 'Actual Android emulator capture · development build · synthetic editor. Bundled Noto Emoji GIFs by Google, CC BY 4.0; small local catalog.', src: 'assets/screens/gifs.png', alt: 'Full native Android development screenshot of local GIF search in Lip.' }
  };
  const choices = document.querySelector('.gallery-choices');
  if (choices) {
    function select(value) {
      if (!Object.hasOwn(captures, value)) return;
      Object.keys(captures).forEach(key => { document.getElementById(`capture-${key}`).hidden = key !== value; });
      document.getElementById('gallery-status').textContent = `Showing ${captures[value].title}. Actual Android emulator capture.`;
    }
    choices.addEventListener('change', event => { if (event.target.matches('input[name="capture"]')) select(event.target.value); });
    select(choices.querySelector('input:checked').value);
    choices.disabled = false;
    document.documentElement.classList.add('js');
  }
  const dialog = document.getElementById('capture-dialog');
  if (!dialog || typeof dialog.showModal !== 'function') return; // Plain image links remain functional.
  let opener;
  document.querySelectorAll('[data-capture]').forEach(link => {
    link.addEventListener('click', event => {
      if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      const capture = captures[link.dataset.capture];
      if (!capture) return;
      event.preventDefault(); opener = link;
      document.getElementById('capture-title').textContent = capture.title;
      document.getElementById('capture-detail').textContent = capture.detail;
      const image = document.getElementById('full-capture'); image.src = capture.src; image.alt = capture.alt;
      dialog.showModal(); dialog.scrollTop = 0;
      document.getElementById('close-capture').focus();
    });
  });
  document.getElementById('close-capture').addEventListener('click', () => dialog.close());
  // The only focusable control in this image dialog is Close. Keep Tab/Shift-Tab
  // on it; Escape and browser shortcuts retain their native behavior.
  dialog.addEventListener('keydown', event => {
    if (event.key === 'Tab') { event.preventDefault(); document.getElementById('close-capture').focus(); }
  });
  dialog.addEventListener('close', () => { if (opener && opener.isConnected) opener.focus(); });
})();
