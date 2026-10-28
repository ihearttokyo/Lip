"""Pre-score contract integrity is not product or feature acceptance."""
import hashlib
import json
from pathlib import Path
import unittest

class NextMajorScopeTests(unittest.TestCase):
    def test_prescore_scope_lock_and_release_order(self):
        lock=json.loads(Path('loop/NEXT-MAJOR-CONTRACT-LOCK.json').read_text())
        for path,digest in lock['sha256'].items():
            self.assertEqual(digest,hashlib.sha256(Path(path).read_bytes()).hexdigest(),path)
        contract=json.loads(Path('loop/NEXT-MAJOR-ACCEPTANCE.json').read_text())
        self.assertEqual(9.2,contract['threshold'])
        self.assertEqual(8.5,contract['dimension_minimum'])
        self.assertTrue(contract['phone_hold'])
        self.assertTrue(contract['no_rounding'])
        self.assertEqual(19,len(contract['criteria']))
        self.assertTrue(all(c['status']=='OPEN' and c['verifier']=='NOT_IMPLEMENTED' for c in contract['criteria']))
        self.assertEqual('future release only',contract['baseline_translation'])
        self.assertEqual('required in current parity release',contract['baseline_basic_gif_clipboard'])
        self.assertIn('optional ChatGPT online',contract['translation_backends'])

    def test_media_history_is_additive_without_capture_authority(self):
        before=json.loads(Path('loop/NEXT-MAJOR-CONTRACT-LOCK-BEFORE-MEDIA-HISTORY.json').read_text())
        self.assertEqual(3,before['revision'])
        contract=json.loads(Path('loop/NEXT-MAJOR-ACCEPTANCE.json').read_text())
        self.assertTrue({'NEXT-WECHAT-ASSETS','NEXT-STICKER-PACKS','NEXT-STICKER-RECEIVE','NEXT-HISTORY-LOCAL','NEXT-HISTORY-SYNC'}.issubset({c['id'] for c in contract['criteria']}))
        self.assertIn('no activation',contract['history_authority'])
        self.assertIn('current parity release and site first',contract['release_order'])
        self.assertEqual('docs/MAJOR-MEDIA-HISTORY-2026-10-27.md',contract['media_history_scope'])
