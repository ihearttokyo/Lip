import unittest
from tools.swipe.build_english import convert
class DictionaryBuildTests(unittest.TestCase):
    def text(self,*rows):return 'dictionary=main:en_us,test=fixture\n'+''.join(' word='+r+'\n' for r in rows)
    def test_preserves_flags_case_highest_freq_and_excludes_nonwords(self):
        data,stats=convert(self.text('Hello,f=100,flags=,originalFreq=100','hello,f=90,flags=,originalFreq=90','iPhone,f=88,flags=technical,originalFreq=88','rude,f=1,flags=offensive,originalFreq=1','garbage,f=1,flags=nonword,originalFreq=1'))
        self.assertIn(b'hello\t100\tHello\t-',data);self.assertIn(b'iphone\t88\tiPhone\ttechnical',data);self.assertIn(b'rude\t1\trude\toffensive',data);self.assertNotIn(b'garbage',data);self.assertEqual(3,stats['entries'])
    def test_path_boundary_does_not_silently_truncate(self):
        data,stats=convert(self.text('ab'*20+',f=1,flags=,originalFreq=1','ab'*20+'a,f=1,flags=,originalFreq=1'))
        self.assertEqual(1,stats['entries']);self.assertEqual(1,stats['unsupported_path'])
    def test_unknown_format_frequency_and_flags_refuse(self):
        for value in ('bad\n',self.text('test,f=256,flags=,originalFreq=1'),self.text('test,f=1,flags=unknown,originalFreq=1'),self.text('broken')):
            with self.assertRaises(ValueError):convert(value)
    def test_generation_is_order_stable_for_noncolliding_entries(self):
        a='world,f=1,flags=,originalFreq=1';b='hello,f=2,flags=,originalFreq=2'
        self.assertEqual(convert(self.text(a,b))[0],convert(self.text(b,a))[0])
