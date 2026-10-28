"""Layout regression guards; source checks are not Android pixel evidence."""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class BubbleLayoutTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = (ROOT / "app/src/main/java/dev/lip/LipAccessibilityService.kt").read_text()

    def test_header_measures_action_text_and_remaining_caption_width(self):
        self.assertIn("content.addView(center, LinearLayout.LayoutParams(0, -2, 1f))", self.source)
        self.assertIn("center.addView(it, LinearLayout.LayoutParams(-1, dp(28)))", self.source)
        action = self.source.split('confirm = label("Lip", 16f)', 1)[1].split("container.addView(content)", 1)[0]
        for required in ("minimumWidth = dp(48)", "minimumHeight = dp(48)", "LinearLayout.LayoutParams(-2, -2)"):
            self.assertIn(required, action)

    def test_choices_and_copy_measure_content_height(self):
        self.assertIn("choices.addView(rawChoice, LinearLayout.LayoutParams(-2, -2, 1f))", self.source)
        self.assertIn("choices.addView(cleanedChoice, LinearLayout.LayoutParams(-2, -2, 1f)", self.source)
        self.assertIn('action("Copy") { copy(this@LipAccessibilityService, dictation.text) }, LinearLayout.LayoutParams(-1, -2)', self.source)

    def test_full_failure_feedback_remains_scrollable_and_nonfocusable(self):
        footer = self.source.split('reviewFeedback = label("", 12f)', 1)[1].split("params =", 1)[0]
        self.assertNotIn("maxLines", footer)
        for required in ("ScrollView(this).apply", "isFocusable = false", "addView(reviewFeedback)"):
            self.assertIn(required, footer)
        self.assertIn("WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE", self.source)

    def test_width_and_drag_bounds_use_actual_window_and_bubble_dimensions(self):
        for required in ("minOf(dp(280), bubbleBounds().width() - dp(24))",
                         "container.addOnLayoutChangeListener", "screen.width() - content.width",
                         "screen.height() - content.height"):
            self.assertIn(required, self.source)
        self.assertNotIn("widthPixels - dp(210)", self.source)


if __name__ == "__main__":
    unittest.main()
