package app.olauncher.data

import app.olauncher.R
import app.olauncher.ui.PanelEmptyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanelEmptyStateTest {
    @Test fun onlyPinnedMediaDoesNotClaimShadeIsEmpty() {
        assertNull(PanelEmptyState.message(2, 0, true))
        assertNull(PanelEmptyState.message(0, 0, true))
    }

    @Test fun filteredModeStillDescribesItsOwnEmptyList() {
        assertEquals(R.string.panel_filtered_empty, PanelEmptyState.message(1, 0, true))
    }

    @Test fun noMediaKeepsExistingEmptyMessages() {
        assertEquals(R.string.nothing_in_the_shade, PanelEmptyState.message(2, 0, false))
        assertEquals(R.string.panel_focus_empty, PanelEmptyState.message(0, 0, false))
        assertNull(PanelEmptyState.message(2, 1, false))
    }
}
