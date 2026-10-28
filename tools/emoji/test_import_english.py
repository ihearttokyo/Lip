import unittest,import_english as source

class ImportTest(unittest.TestCase):
    def test_rebuild_identity_and_full_qualification(self):
        data,meta=source.build();self.assertEqual(3944,meta['rows']);self.assertEqual(9,len(meta['categories']));self.assertEqual(meta['sha256'],source.sha(data))
        self.assertEqual(data,(source.ROOT/'app/src/main/assets/emoji/english.tsv').read_bytes())
    def test_variation_and_zwj_are_preserved(self):
        data,_=source.build();rows={r.split('\t')[0]:r.split('\t') for r in data.decode().splitlines()}
        self.assertEqual('❤️',rows['2764_fe0f'][1]);self.assertIn('👨‍👩‍👧‍👦',[r[1] for r in rows.values()])
        self.assertNotIn('1f3fb',rows)
    def test_indexed_protocol_preserves_all_original_columns(self):
        data,_=source.build();indexed=source.indexed(data)
        self.assertEqual(indexed,(source.ROOT/'app/src/main/assets/emoji/english-indexed.tsv').read_bytes())
        for raw,prepared in zip(data.decode().splitlines(),indexed.decode().splitlines()):
            self.assertEqual(raw.split('\t'),prepared.split('\t')[:6]);self.assertEqual(9,len(prepared.split('\t')))
    def test_entity_not_resolved(self):
        with self.assertRaises(ValueError):source.annotations('<!ENTITY x SYSTEM "file:///private">')
    def test_duplicate_normalized_annotation_rejected(self):
        with self.assertRaises(ValueError):source.annotations('<ldml><annotations><annotation cp="❤">heart</annotation><annotation cp="❤️">heart</annotation></annotations></ldml>')
    def test_unknown_annotation_rejected(self):
        with self.assertRaises(ValueError):source.annotations('<ldml><annotations><annotation cp="x" alt="a">name</annotation></annotations></ldml>')
    def test_only_qualified_and_category_provenance(self):
        rows,categories=source.qualified('# group: Symbols\n# subgroup: test\n2764 FE0F ; fully-qualified # ❤️ E0.6 red heart\n2764 ; unqualified # ❤ E0.6 red heart\n1F3FB ; component # 🏻 E1.0 tone')
        self.assertEqual([('❤️',0,'test')],rows);self.assertEqual(['Symbols'],categories)
    def test_duplicate_or_missing_groups_refused(self):
        for raw in ['2764 FE0F ; fully-qualified # ❤️ E0.6 heart','# group: Symbols\n# subgroup: test\n2764 FE0F ; fully-qualified # heart\n2764 ; fully-qualified # heart']:
            with self.assertRaises(ValueError):source.qualified(raw)

if __name__=='__main__':unittest.main()
