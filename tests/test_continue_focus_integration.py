"""Check that Home and the Watch Next shelf connect their explicit focus paths."""
from pathlib import Path
import unittest

JAVA = Path(__file__).resolve().parents[1] / 'launcher/app/src/main/java/com/gathin/tvhome'

class ContinueFocusIntegrationTest(unittest.TestCase):
    def test_home_routes_top_down_and_grid_up_to_shelf(self):
        main = (JAVA / 'MainActivity.java').read_text()
        self.assertIn('continueWatching.focusHeader()', main)
        self.assertIn('continueWatching.focus()', main)
        self.assertIn('ContinueNavigation.collapseShelf(', main)
    def test_cards_route_vertical_keys_and_stop_horizontal_edges(self):
        source = (JAVA / 'ContinueWatching.java').read_text()
        self.assertIn('navigation.top(); else navigation.apps();', source)
        self.assertIn('ContinueNavigation.horizontalEdge(', source)
        self.assertIn('ContinueNavigation.restoredIndex(', source)
        self.assertNotIn('result == null || !holder.card.isAttachedToWindow()', source)

if __name__ == '__main__': unittest.main()
