package com.cardroid.app.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperLauncherCommandTest {
    private val apk = "/data/app/~~abc==/com.cardroid.app-xyz==/base.apk"

    @Test fun exactDetachedCommandContract() {
        assertEquals(
            "CLASSPATH=$apk setsid nohup app_process /system/bin " +
                "--nice-name=cardroid-helper com.cardroid.app.helper.HelperMain " +
                "--port=27185 --app-uid=10123 >/data/local/tmp/cardroid-helper.log 2>&1 </dev/null &",
            HelperLauncherCommand.startShellCommand(apk, 10123, 27185),
        )
    }

    @Test fun rejectsMetacharactersWrongSuffixAndRanges() {
        assertFalse(HelperLauncherCommand.validate("/tmp/base.apk;id", 10123, 27185) == null)
        assertFalse(HelperLauncherCommand.validate("/tmp/helper.apk", 10123, 27185) == null)
        assertFalse(HelperLauncherCommand.validate(apk, 0, 27185) == null)
        assertFalse(HelperLauncherCommand.validate(apk, 10123, 1023) == null)
        assertFalse(HelperLauncherCommand.validate(apk, 10123, 65536) == null)
        assertTrue(HelperLauncherCommand.validate(apk, 10123, 27185) == null)
    }

    @Test fun parsesOnlyPositiveDecimalPidTokens() {
        assertEquals(listOf(12, 34), HelperLauncherCommand.parsePids(" 12 34\n12\n"))
        assertEquals(emptyList<Int>(), HelperLauncherCommand.parsePids("cardroid-helper\n"))
        assertEquals(emptyList<Int>(), HelperLauncherCommand.parsePids("0 -1 2x"))
        assertEquals(emptyList<Int>(), HelperLauncherCommand.parsePids("12 malformed"))
    }
}
