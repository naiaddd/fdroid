package dev.jim.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateCacheTest {
    @Test
    fun cacheMatchesCohort_rejectsDifferentTailoredBuild() {
        assertTrue(StateCache.cacheMatchesCohort("beta", "beta"))
        assertFalse(StateCache.cacheMatchesCohort("beta", "glendel"))
        assertFalse(StateCache.cacheMatchesCohort("all", "beta"))
    }

    @Test
    fun cacheMatchesCohort_allowsLegacyCacheOnlyForAllBuild() {
        assertTrue(StateCache.cacheMatchesCohort(null, COHORT_ALL))
        assertFalse(StateCache.cacheMatchesCohort(null, "beta"))
    }

    @Test
    fun decode_returnsEmptyForMalformedCache() {
        assertEquals(emptyList<ResolvedApp>(), StateCache.decode("not-json"))
        assertEquals(emptyList<ResolvedApp>(), StateCache.decode("[{}]"))
    }
}
