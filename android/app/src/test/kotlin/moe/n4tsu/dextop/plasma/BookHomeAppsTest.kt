package moe.n4tsu.dextop.plasma

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookHomeAppsTest {
    @Test fun diffReportsAddedAndRemovedPackages() {
        val (added, removed) = BookStore.homeAppsDiff(
            listOf("com.android.settings", "com.android.chrome"),
            listOf("com.android.chrome", "com.google.android.gm"),
        )
        assertEquals(listOf("com.google.android.gm"), added)
        assertEquals(listOf("com.android.settings"), removed)
    }

    @Test fun diffOfIdenticalSelectionsIsEmpty() {
        val (added, removed) = BookStore.homeAppsDiff(listOf("a", "b"), listOf("b", "a"))
        assertTrue(added.isEmpty())
        assertTrue(removed.isEmpty())
    }

    @Test fun freeSpotKeepsAFittingWantedSpot() {
        val taken = listOf(GridSpot(0, 0))
        val spot = BookStore.freeSpot(GridSpot(2, 1), taken, columns = 4, rows = 3)
        assertEquals(GridSpot(2, 1), spot)
    }

    @Test fun freeSpotFindsTheNearestFreeCell() {
        val taken = listOf(GridSpot(0, 0), GridSpot(1, 0), GridSpot(0, 1))
        val spot = BookStore.freeSpot(GridSpot(0, 0), taken, columns = 4, rows = 3)
        assertEquals(GridSpot(1, 1), spot)
    }

    @Test fun freeSpotReturnsNullWhenTheGridIsFull() {
        val taken = (0..3).flatMap { c -> (0..2).map { r -> GridSpot(c, r) } }
        assertNull(BookStore.freeSpot(GridSpot(0, 0), taken, columns = 4, rows = 3))
    }

    @Test fun defaultHomeAppsAreUniquePackageNames() {
        assertEquals(bookDefaultHomeApps.size, bookDefaultHomeApps.toSet().size)
        assertTrue(bookDefaultHomeApps.all { it.startsWith("com.") || it.startsWith("org.") })
    }
}
