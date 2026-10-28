import json
import tempfile
from pathlib import Path
import unittest
from tools.learned_words.codec import *

class PortableVocabularyTests(unittest.TestCase):
    def test_unicode_phrase_roundtrip_and_stable_export(self):
        entries = (Entry("Sampleton"), Entry("Ada Rivers"), Entry("東京", "ja"), Entry("中文", "zh"), Entry("mañana", "es"))
        data = encode(entries)
        self.assertEqual(encode(decode(data)), data)
        self.assertEqual(5, len(decode(data)))

    def test_merge_is_idempotent_preserves_spelling_and_max_weight(self):
        old = (Entry("Lip", weight=2), Entry("Straße"), Entry("café"))
        incoming = (Entry("LIP", weight=8, source="gmail"), Entry("STRASSE"), Entry("cafe\u0301"))
        merged = merge(old, incoming)
        self.assertEqual(3, len(merged))
        self.assertEqual("Lip", next(e for e in merged if e.identity[1] == "lip").term)
        self.assertEqual(8, next(e for e in merged if e.identity[1] == "lip").weight)
        self.assertEqual(merged, merge(merged, incoming))

    def test_unknown_versions_keys_and_numeric_coercion_rejected(self):
        obj = json.loads(encode((Entry("hello"),)))
        for bad in ({**obj, "version": True}, {**obj, "version": 2}, {**obj, "messages": []}, {**obj, "entries": "bad"}):
            with self.assertRaises(VocabularyError): decode(json.dumps(bad).encode())
        for weight in (True, 0, 1.5, 1_000_001):
            with self.assertRaises(VocabularyError): Entry("hello", weight=weight)

    def test_duplicate_json_keys_and_normalized_terms_rejected(self):
        with self.assertRaises(VocabularyError): decode(b'{"version":1,"version":1}')
        obj = json.loads(encode((Entry("Lip"),)))
        obj["entries"].append({**obj["entries"][0], "term": "LIP"})
        with self.assertRaises(VocabularyError): decode(json.dumps(obj).encode())

    def test_private_or_unsafe_tokens_and_controls_rejected_without_echo(self):
        for token in ("a@example.com", "https://example.com", "www.example.com", "abc\nSECRET", "abc\u202e", "123456", "x" * 129, "x\ud800"):
            with self.assertRaises(VocabularyError) as error: Entry(token)
            self.assertNotIn(token, str(error.exception))

    def test_size_utf8_nonfinite_and_deep_input_fail_closed(self):
        for data in (b"x" * (MAX_BYTES + 1), b"\xff", b'{"entries":NaN}', b"[" * 1500 + b"]" * 1500, b"9" * 5000):
            with self.assertRaises(VocabularyError): decode(data)

    def test_unbounded_duplicate_iterator_is_stopped(self):
        from itertools import repeat
        with self.assertRaises(VocabularyError): merge((), repeat(Entry("hello")))

    def test_cli_does_not_echo_private_filename_or_payload(self):
        import subprocess, sys
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/"SECRET-PERSON.json"
            path.write_text('{"private":"SECRET-PAYLOAD"}')
            result=subprocess.run([sys.executable,"-m","tools.learned_words","verify",str(path)],capture_output=True,text=True)
            self.assertEqual(2,result.returncode)
            self.assertNotIn("SECRET",result.stdout+result.stderr)
            self.assertNotIn("Traceback",result.stderr)

    def test_unicode16_golden_and_runtime_mismatch_fail_without_mutation(self):
        from unittest.mock import patch
        self.assertEqual("16.0.0", UNICODE_VERSION)
        self.assertEqual("\U00010597", Entry("\U00010570").identity[1])
        data=encode((Entry("hello"),))
        with patch("tools.learned_words.codec.unicodedata.unidata_version", "13.0.0"):
            for action in (lambda: Entry("hello"), lambda: decode(data), lambda: encode(()), lambda: merge((),())):
                with self.assertRaises(VocabularyError): action()
        obj=json.loads(data);obj["unicode_version"]="13.0.0"
        with self.assertRaises(VocabularyError): decode(json.dumps(obj).encode())

    def test_malformed_cli_never_echoes_argument_values(self):
        import subprocess, sys
        cases=[['verify','PRIVATE-FILE.json','PRIVATE-EXTRA'],['PRIVATE-COMMAND'],['merge','PRIVATE-EXISTING'],['verify','--PRIVATE-OPTION']]
        for args in cases:
            result=subprocess.run([sys.executable,'-m','tools.learned_words',*args],capture_output=True,text=True)
            self.assertEqual(2,result.returncode)
            self.assertNotIn('PRIVATE',result.stdout+result.stderr)
            self.assertNotIn('Traceback',result.stderr)

    def test_shared_golden_file(self):
        vectors=json.loads(Path("docs/contracts/learned-words/vectors-v1.json").read_text())
        self.assertEqual(UNICODE_VERSION,vectors['unicode_version'])
        for row in vectors['accepted']:
            entry=Entry(row['input'])
            self.assertEqual(row['display'],entry.term);self.assertEqual(row['identity'],entry.identity[1])
        for value in vectors['refused']:
            with self.assertRaises(VocabularyError): Entry(value)

    def test_new_backup_is_private_and_never_overwrites_original_or_symlink(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "words.json"
            write_new(path, (Entry("hello"),))
            original = path.read_bytes()
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            with self.assertRaises(FileExistsError): write_new(path, (Entry("changed"),))
            link = Path(folder) / "link.json"
            link.symlink_to(path)
            with self.assertRaises(FileExistsError): write_new(link, (Entry("changed"),))
            self.assertEqual(original, path.read_bytes())
            self.assertEqual(["link.json", "words.json"], sorted(p.name for p in Path(folder).iterdir()))

if __name__ == "__main__": unittest.main()
