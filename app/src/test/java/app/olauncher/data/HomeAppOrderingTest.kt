package app.olauncher.data

import org.junit.Assert.*
import org.junit.Test

class HomeAppOrderingTest {
    private fun app(name: String, pkg: String = name) =
        HomeAppEntry(name, pkg, "UserHandle{0}")

    @Test fun emptyHomeStartsAutomaticButExistingOrderStaysManual() {
        assertTrue(HomeAppOrdering.defaultAutomatic(emptyList()))
        assertFalse(HomeAppOrdering.defaultAutomatic(listOf(app("Zulu"), app("Alpha"))))
        val manual = listOf(app("Zulu"), app("Alpha"))
        assertEquals(manual, HomeAppOrdering.display(manual, false))
    }

    @Test fun automaticAddRenameAndRestartHaveDeterministicOrder() {
        val added = HomeAppOrdering.display(listOf(app("beta"), app("Alpha"), app("alpha", "pkg.z")), true)
        assertEquals(listOf("Alpha", "alpha", "beta"), added.map { it.name })
        val renamed = HomeAppOrdering.display(added.map { if (it.name == "beta") it.copy(name = "Aardvark") else it }, true)
        assertEquals(listOf("Aardvark", "Alpha", "alpha"), renamed.map { it.name })
        assertEquals(renamed, HomeAppOrdering.display(renamed, true))
    }

    @Test fun caseInsensitiveTiesUseNameThenIdentity() {
        val rows = listOf(app("same", "z"), app("Same", "b"), app("same", "a"))
        assertEquals(listOf("b", "a", "z"), HomeAppOrdering.display(rows, true).map { it.pkg })
    }
}
