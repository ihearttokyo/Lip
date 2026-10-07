'use strict';

// Prepared illustrations only: no microphone, account, or network request.
const examples = {
  en: {
    raw: 'so um i was thinking maybe we could grab coffee this afternoon and try the new bookshop in town',
    polished: 'Want to grab coffee this afternoon? We could try the new bookshop in town.',
    light: 'So, um, I was thinking maybe we could grab coffee this afternoon and try the new bookshop in town.'
  },
  ja: {
    raw: 'えっと週末に新しい本屋に行ってそのあとコーヒー飲みませんか',
    polished: '週末、新しい本屋に行ってからコーヒーを飲みませんか？',
    light: 'えっと、週末に新しい本屋に行って、そのあとコーヒー飲みませんか？'
  },
  zh: {
    raw: '那个我们下午三点见面去看看新开的书店然后喝杯咖啡吧',
    polished: '我们下午三点见面，去看看新开的书店，再喝杯咖啡吧。',
    light: '那个，我们下午三点见面，去看看新开的书店，然后喝杯咖啡吧。'
  }
};

function demoText(language, style) {
  const example = examples[language] || examples.en;
  return style === 'verbatim' ? example.raw : example[style] || example.polished;
}

if (typeof document !== 'undefined') {
  const language = document.getElementById('demo-language');
  const styles = document.getElementById('demo-styles');
  const copy = document.getElementById('copy-demo');
  const clean = document.getElementById('demo-clean');
  const feedback = document.getElementById('demo-feedback');
  const disclaimer = feedback.textContent;
  function render() {
    const style = styles.querySelector('input:checked').value;
    document.getElementById('demo-raw').textContent = examples[language.value].raw;
    clean.textContent = demoText(language.value, style);
    clean.lang = language.value;
    document.getElementById('demo-raw').lang = language.value;
    document.getElementById('demo-style-label').textContent = style[0].toUpperCase() + style.slice(1);
    feedback.textContent = disclaimer;
  }
  language.disabled = styles.disabled = copy.disabled = false;
  language.addEventListener('change', render);
  styles.addEventListener('change', render);
  render();
  copy.addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(clean.textContent);
      feedback.textContent = 'Example copied. This is prepared text, not a live transcription.';
    } catch {
      const range = document.createRange();
      range.selectNodeContents(clean);
      const selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
      feedback.textContent = 'Clipboard access is unavailable. The example is selected; use your browser’s Copy command.';
    }
  });
  document.querySelectorAll('.mobile-nav a').forEach(link => {
    link.addEventListener('click', () => { link.closest('details').open = false; });
  });
}

if (typeof process !== 'undefined' && process.argv.includes('--self-test')) {
  const assert = require('node:assert/strict');
  assert.equal(demoText('en', 'polished'), 'Want to grab coffee this afternoon? We could try the new bookshop in town.');
  const words = value => value.toLowerCase().replace(/[\p{P}\p{Z}\s]/gu, '');
  for (const language of ['en', 'ja', 'zh']) {
    assert.equal(demoText(language, 'verbatim'), examples[language].raw);
    assert.notEqual(demoText(language, 'light'), examples[language].raw);
    assert.equal(words(demoText(language, 'light')), words(examples[language].raw));
    assert.notEqual(demoText(language, 'polished'), examples[language].raw);
  }
  assert.equal(demoText('unknown', 'unknown'), examples.en.polished);
  console.log('PASS: prepared examples, verbatim/Light word preservation, language/style fallback');
}
