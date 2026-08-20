package team.holder.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import team.holder.android.ui.LoadState

class LoadStateTest {
    @Test
    fun loading_isSingletonState() {
        assertEquals(LoadState.Loading, LoadState.Loading)
    }

    @Test
    fun success_preservesValueAndUsesValueEquality() {
        assertEquals(LoadState.Success(listOf("one", "two")), LoadState.Success(listOf("one", "two")))
        assertNotEquals(LoadState.Success(listOf("one")), LoadState.Success(listOf("two")))
    }

    @Test
    fun error_preservesMessageAndUsesMessageEquality() {
        assertEquals(LoadState.Error("network failed"), LoadState.Error("network failed"))
        assertNotEquals(LoadState.Error("network failed"), LoadState.Error("permission denied"))
    }
}
