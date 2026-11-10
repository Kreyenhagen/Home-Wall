package com.kreyenhagen.homewall.ui.editor

import com.kreyenhagen.homewall.MainDispatcherRule
import com.kreyenhagen.homewall.data.layout.LayoutState
import com.kreyenhagen.homewall.data.layout.PlaceholderLayout
import com.kreyenhagen.homewall.data.photo.WallPhotoState
import com.kreyenhagen.homewall.data.route.FakeRouteDao
import com.kreyenhagen.homewall.data.route.RouteRepository
import com.kreyenhagen.homewall.data.settings.AppSettings
import com.kreyenhagen.homewall.domain.HoldRole
import com.kreyenhagen.homewall.domain.HoldRole.FINISH
import com.kreyenhagen.homewall.domain.HoldRole.MIDDLE
import com.kreyenhagen.homewall.domain.HoldRole.START
import com.kreyenhagen.homewall.domain.RouteHold
import com.kreyenhagen.homewall.led.ringColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RouteEditorViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val dao = FakeRouteDao()
    private var now = 1_000L
    private val repo = RouteRepository(dao, clock = { now })
    private val layout = MutableStateFlow<LayoutState>(LayoutState.Ready(PlaceholderLayout.create()))

    /** A ViewModel whose state is being collected, as the screen would. */
    private fun TestScope.editor(routeId: Long? = null): RouteEditorViewModel {
        val vm = RouteEditorViewModel(routeId, repo, layout, MutableStateFlow(WallPhotoState(loaded = true)), MutableStateFlow(AppSettings()))
        backgroundScope.launch(main.dispatcher) { vm.uiState.collect {} }
        return vm
    }

    private val RouteEditorViewModel.state get() = uiState.value

    private fun RouteEditorViewModel.tap(role: HoldRole, vararg holdIds: Int) {
        selectRole(role)
        holdIds.forEach(::onTapHold)
    }

    @Test
    fun newRouteStartsEmptyWithStartSelected() = runTest {
        val vm = editor()
        assertTrue(vm.state.loaded)
        assertTrue(vm.state.isNew)
        assertEquals(emptyMap<Int, HoldRole>(), vm.state.holds)
        assertEquals(START, vm.state.selectedRole)
        assertFalse(vm.state.canSave)
        assertFalse(vm.state.canUndo)
    }

    @Test
    fun tapsBuildTheRouteWithRingsInRoleColors() = runTest {
        val vm = editor()
        vm.tap(START, 5)
        vm.tap(MIDDLE, 27, 60)
        vm.tap(FINISH, 148)
        assertEquals(mapOf(5 to START, 27 to MIDDLE, 60 to MIDDLE, 148 to FINISH), vm.state.holds)
        assertEquals(MIDDLE.ringColor, vm.state.rings[60])
        assertEquals(1, vm.state.count(START))
        assertEquals(2, vm.state.count(MIDDLE))
        assertTrue(vm.state.canSave)
        assertTrue(vm.state.hasChanges)
    }

    @Test
    fun aThirdStartHoldIsRefusedWithAMessage() = runTest {
        val vm = editor()
        vm.tap(START, 1, 2, 3)
        assertEquals(mapOf(1 to START, 2 to START), vm.state.holds)
        val message = vm.state.message ?: error("no message")
        assertEquals("Max 2 start holds", message.text)
        assertTrue(message.haptic)

        // The same refusal again is a new message, so it shows (and buzzes) again.
        vm.messageShown(message.id)
        vm.onTapHold(3)
        assertTrue((vm.state.message?.id ?: 0) > message.id)
    }

    @Test
    fun undoAndRedoIncludingRedoClearedByANewChange() = runTest {
        val vm = editor()
        vm.tap(START, 1)
        vm.tap(MIDDLE, 2)
        vm.undo()
        assertEquals(mapOf(1 to START), vm.state.holds)
        assertTrue(vm.state.canRedo)
        vm.redo()
        assertEquals(mapOf(1 to START, 2 to MIDDLE), vm.state.holds)
        vm.undo()
        vm.tap(MIDDLE, 3)
        assertFalse(vm.state.canRedo)
        // Changing the selected role isn't a step.
        vm.selectRole(FINISH)
        vm.undo()
        assertEquals(mapOf(1 to START), vm.state.holds)
    }

    @Test
    fun savingANewRouteCreatesItAndExitsToIt() = runTest {
        val vm = editor()
        vm.tap(START, 5)
        vm.tap(MIDDLE, 27)
        vm.openSaveDialog()
        assertEquals(SaveDialogState("", null), vm.state.saveDialog)
        assertEquals("This route has no finish hold.", vm.state.saveWarning)
        assertFalse(vm.state.saveDialog?.canSave ?: true) // blank name

        vm.setName("  Warm-up  ")
        vm.save()

        val saved = vm.state.exit as EditorExit.Saved
        val route = repo.get(saved.routeId) ?: error("not saved")
        assertEquals("Warm-up", route.name)
        assertNull(route.grade) // Project by default
        assertEquals(listOf(RouteHold(5, START), RouteHold(27, MIDDLE)), route.holds)
        assertEquals(1_000L, route.createdAt)
        assertEquals(1_000L, route.updatedAt)
    }

    @Test
    fun namesAreCappedAt40Characters() = runTest {
        val vm = editor()
        vm.tap(MIDDLE, 1)
        vm.openSaveDialog()
        vm.setName("x".repeat(50))
        assertEquals(40, vm.state.saveDialog?.name?.length)
    }

    @Test
    fun cancellingTheDialogKeepsWhatWasTyped() = runTest {
        val vm = editor()
        vm.tap(MIDDLE, 1)
        vm.openSaveDialog()
        vm.setName("Half typed")
        vm.setGrade(4)
        vm.dismissSaveDialog()
        assertNull(vm.state.saveDialog)
        vm.openSaveDialog()
        assertEquals(SaveDialogState("Half typed", 4), vm.state.saveDialog)
    }

    @Test
    fun editingLoadsTheRouteAndDropsHoldsMissingFromTheLayout() = runTest {
        val id = repo.create("Old", 3, listOf(RouteHold(5, START), RouteHold(500, MIDDLE), RouteHold(148, FINISH)))
        val vm = editor(id)
        assertFalse(vm.state.isNew)
        assertEquals(mapOf(5 to START, 148 to FINISH), vm.state.holds)
        assertEquals("1 hold no longer exists. Saving removes it from the route.", vm.state.missingNote)
        assertFalse(vm.state.canUndo) // history starts at the saved route
        assertFalse(vm.state.hasChanges)

        vm.openSaveDialog()
        assertEquals(SaveDialogState("Old", 3), vm.state.saveDialog) // prefilled
        now = 9_000L
        vm.setGrade(4)
        vm.save()

        assertEquals(EditorExit.Saved(id), vm.state.exit)
        val route = repo.get(id) ?: error("missing")
        assertEquals(4, route.grade)
        assertEquals(listOf(RouteHold(5, START), RouteHold(148, FINISH)), route.holds)
        assertEquals(1_000L, route.createdAt)
        assertEquals(9_000L, route.updatedAt)
    }

    @Test
    fun backAsksFirstOnlyWhenThereAreUnsavedChanges() = runTest {
        val clean = editor()
        clean.onBack()
        assertEquals(EditorExit.Closed, clean.state.exit)

        val dirty = editor()
        dirty.tap(START, 1)
        dirty.onBack()
        assertEquals(EditorConfirm.DISCARD, dirty.state.confirm)
        assertNull(dirty.state.exit)
        dirty.dismissConfirm()
        assertNull(dirty.state.confirm)
        dirty.onBack()
        dirty.confirm()
        assertEquals(EditorExit.Closed, dirty.state.exit)

        // Undoing back to the start means nothing is lost.
        val undone = editor()
        undone.tap(START, 1)
        undone.undo()
        undone.onBack()
        assertEquals(EditorExit.Closed, undone.state.exit)
    }

    @Test
    fun deleteAsksThenDeletes() = runTest {
        val id = repo.create("Doomed", null, listOf(RouteHold(1, MIDDLE)))
        val vm = editor(id)
        vm.requestDelete()
        assertEquals(EditorConfirm.DELETE, vm.state.confirm)
        assertEquals("Doomed", vm.state.routeName)
        vm.confirm()
        assertEquals(EditorExit.Deleted, vm.state.exit)
        assertNull(repo.get(id))
    }

    @Test
    fun aRouteThatNoLongerExistsCantBeEdited() = runTest {
        val vm = editor(routeId = 42)
        assertTrue(vm.state.notFound)
        vm.onTapHold(1)
        assertEquals(emptyMap<Int, HoldRole>(), vm.state.holds)
    }
}
