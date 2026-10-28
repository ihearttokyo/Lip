"""Static entry-point checks only; not a network trace or device runtime pass."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]

class LocalBarkContractTest(unittest.TestCase):
    def test_production_ui_and_controller_have_no_cloud_client_entry_point(self):
        for name in ('MainActivity.kt', 'Dictation.kt'):
            source = (ROOT / 'app/src/main/java/dev/lip' / name).read_text()
            for forbidden in ('ChatGptClient', 'chatGpt.', 'beginSignIn', 'cleanSelected', 'listModels('):
                self.assertNotIn(forbidden, source)

    def test_production_opens_bark_without_runtime_whisper_fallback(self):
        source = (ROOT / 'app/src/main/java/dev/lip/Dictation.kt').read_text()
        self.assertIn('BarkModel.engine(', source)
        self.assertIn('BarkModel.file(', source)
        self.assertNotIn('existing ?: WhisperEngine(', source)
        self.assertIn('store.language != "en-US"', source)

    def test_native_adapter_keeps_abort_and_timestamp_contract(self):
        source = (ROOT / 'app/src/main/cpp/bark_jni.cpp').read_text()
        for required in ('CallAbortScope', 'aborted.load()', 'starling_ggml_transcribe_pcm_words', 'previousEnd', '4000'):
            self.assertIn(required, source)

if __name__ == '__main__':
    unittest.main()
