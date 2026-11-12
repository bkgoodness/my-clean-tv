"""Keep the Watch Next workers alive while a runtime permission dialog pauses Home."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "launcher/app/src/main/java/com/gathin/tvhome"

class ContinueWatchingLifecycleTest(unittest.TestCase):
    def test_pause_keeps_workers_and_destroy_closes_them(self):
        source = (JAVA / "MainActivity.java").read_text()
        paused = source.split("protected void onPause()", 1)[1].split("\n    @Override", 1)[0]
        destroyed = source.split("protected void onDestroy()", 1)[1].split("\n    /**", 1)[0]
        self.assertNotIn("continueWatching.close()", paused)
        self.assertIn("continueWatching.close()", destroyed)

    def test_callbacks_after_close_do_not_submit_work(self):
        source = (JAVA / "ContinueWatching.java").read_text()
        refresh = source.split("void refresh()", 1)[1].split("loader.execute(", 1)[0]
        artwork = source.split("private void loadArtwork(", 1)[1].split("artwork.execute(", 1)[0]
        self.assertIn("if (closed) return;", refresh)
        self.assertIn("closed || address == null", artwork)

if __name__ == "__main__":
    unittest.main()
