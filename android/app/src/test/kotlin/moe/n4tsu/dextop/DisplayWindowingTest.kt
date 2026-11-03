package moe.n4tsu.dextop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayWindowingTest {
    private val windowedSettings = linkedMapOf(
        "enable_freeform_support" to "1",
        "force_resizable_activities" to "1",
        "enable_non_resizable_multi_window" to "1"
    )

    @Test fun aDisplayThatAlreadyRunsFreeformIsLeftAlone() {
        // The DeX path: the platform already windows every task, so Dextop
        // must not write settings or set a mode on the display.
        val plan = DesktopWindowingPolicy.plan(
            observed = DisplayWindowing.FREEFORM,
            requiredSettings = windowedSettings,
            liveSettings = emptyMap(),
            perDisplayApi = true,
            shellCommand = false,
            ownWindowManager = true
        )
        assertEquals(listOf(DesktopWindowingStep.KEEP_PLATFORM_FREEFORM), plan)
    }

    @Test fun android15WithoutAShellCommandUsesTheBinderWindowMode() {
        // Android 15: no `wm set-display-windowing-mode`, but
        // IWindowManager#setWindowingMode is available.
        val plan = DesktopWindowingPolicy.plan(
            observed = DisplayWindowing.FULLSCREEN,
            requiredSettings = windowedSettings,
            liveSettings = emptyMap(),
            perDisplayApi = true,
            shellCommand = false,
            ownWindowManager = true
        )
        assertEquals(
            listOf(
                DesktopWindowingStep.ENABLE_FREEFORM_SUPPORT,
                DesktopWindowingStep.FORCE_RESIZABLE_ACTIVITIES,
                DesktopWindowingStep.ALLOW_NON_RESIZABLE_MULTI_WINDOW,
                DesktopWindowingStep.SET_DISPLAY_WINDOWING,
                DesktopWindowingStep.ENFORCE_PER_TASK
            ),
            plan
        )
    }

    @Test fun settingsThatAlreadyHaveTheWantedValueAreNotRewritten() {
        val plan = DesktopWindowingPolicy.plan(
            observed = DisplayWindowing.FULLSCREEN,
            requiredSettings = windowedSettings,
            liveSettings = windowedSettings.mapValues { it.value },
            perDisplayApi = true,
            shellCommand = false,
            ownWindowManager = false
        )
        assertEquals(listOf(DesktopWindowingStep.SET_DISPLAY_WINDOWING), plan)
    }

    @Test fun profilesThatAskForNoSettingsOnlySwitchTheDisplay() {
        // The Android 16 AOSP profile deliberately leaves the newer desktop
        // switches at their OS default and only needs the display mode.
        val plan = DesktopWindowingPolicy.plan(
            observed = DisplayWindowing.FULLSCREEN,
            requiredSettings = emptyMap(),
            liveSettings = mapOf("enable_freeform_support" to null),
            perDisplayApi = true,
            shellCommand = true,
            ownWindowManager = true
        )
        assertEquals(
            listOf(
                DesktopWindowingStep.SET_DISPLAY_WINDOWING,
                DesktopWindowingStep.SHELL_COMMAND_WINDOWING_MODE,
                DesktopWindowingStep.ENFORCE_PER_TASK
            ),
            plan
        )
    }

    @Test fun firmwareWithoutAnyWindowingInterfaceReportsUnsupported() {
        val plan = DesktopWindowingPolicy.plan(
            observed = DisplayWindowing.FULLSCREEN,
            requiredSettings = windowedSettings,
            liveSettings = emptyMap(),
            perDisplayApi = false,
            shellCommand = false,
            ownWindowManager = false
        )
        assertEquals(DesktopWindowingStep.UNSUPPORTED, plan.last())
    }

    @Test fun frameworkValuesAndDumpNamesMapBothWays() {
        assertEquals(DisplayWindowing.FREEFORM, DisplayWindowing.fromFramework(5))
        assertEquals(DisplayWindowing.FULLSCREEN, DisplayWindowing.fromFramework(1))
        assertEquals(DisplayWindowing.UNKNOWN, DisplayWindowing.fromFramework(42))
        assertEquals(DisplayWindowing.MULTI_WINDOW, DisplayWindowing.fromDump("multi-window"))
        assertEquals(DisplayWindowing.FREEFORM, DisplayWindowing.fromDump(" freeform "))
        assertEquals(DisplayWindowing.UNKNOWN, DisplayWindowing.fromDump("bogus"))
    }

    @Test fun taskDumpParsingKeepsOnlyTheRequestedDisplay() {
        val dump = """
            Display: mDisplayId=0 (activities from top to bottom):
              * Task{1111aaa #7 type=home A=10123:com.sec.android.app.launcher U=0 visible=true mode=fullscreen translucent=true sz=1}
                bounds=[[0,0][1920,1200]]
              * Task{2222bbb #8 type=standard A=10234:com.android.settings U=0 visible=true mode=fullscreen translucent=false sz=1}
                bounds=[[0,0][1920,1200]]
            Display: mDisplayId=2 (activities from top to bottom):
              * Task{3333ccc #12 type=standard A=10234:com.android.chrome U=0 visible=true mode=freeform translucent=false sz=1}
                bounds=[[120,80][1080,740]]
              * Task{4444ddd #13 type=standard A=10345:org.mozilla.firefox U=0 visible=true mode=freeform translucent=false sz=1}
                bounds=[[700,420][1500,1100]]
              * Task{5555eee #14 type=home A=10123:com.sec.android.app.launcher U=0 visible=true mode=fullscreen translucent=true sz=1}
                bounds=[[0,0][1920,1200]]
        """.trimIndent()
        val tasks = TaskWindowingDump.tasks(2, dump)
        assertEquals(3, tasks.size)
        assertEquals(listOf(12, 13, 14), tasks.map { it.taskId })
        assertEquals("com.android.chrome", tasks[0].packageName)
        assertEquals(120, tasks[0].bounds?.left)
        assertEquals(1500, tasks[1].bounds?.right)
        assertEquals("[700,420][1500,1100]", tasks[1].bounds?.flattenToShortString())
        assertTrue(tasks[0].windowingMode.isFreeform)
        assertEquals(DisplayWindowing.FREEFORM, TaskWindowingDump.applicationMode(tasks))
    }

    @Test fun aFullscreenApplicationTaskIsNotMistakenForAWindow() {
        val dump = """
            Display: mDisplayId=2 (activities from top to bottom):
              * Task{3333ccc #12 type=standard A=10234:com.android.chrome U=0 visible=true mode=fullscreen translucent=false sz=1}
                bounds=[[0,0][1920,1200]]
        """.trimIndent()
        val tasks = TaskWindowingDump.tasks(2, dump)
        assertEquals(DisplayWindowing.FULLSCREEN, TaskWindowingDump.applicationMode(tasks))
        assertFalse(TaskWindowingDump.applicationMode(tasks).isFreeform)
    }

    @Test fun homeTasksDoNotDecideTheApplicationMode() {
        val dump = """
            Display: mDisplayId=2 (activities from top to bottom):
              * Task{5555eee #14 type=home A=10123:com.sec.android.app.launcher U=0 visible=true mode=fullscreen translucent=true sz=1}
                bounds=[[0,0][1920,1200]]
              * Task{3333ccc #12 type=standard A=10234:com.android.chrome U=0 visible=true mode=freeform translucent=false sz=1}
                bounds=[[120,80][1080,740]]
        """.trimIndent()
        assertEquals(
            DisplayWindowing.FREEFORM,
            TaskWindowingDump.applicationMode(TaskWindowingDump.tasks(2, dump))
        )
    }
}
