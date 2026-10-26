package com.cardroid.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelDisplayInfoTest {
    private fun info(id: Int = 19, uid: Int = 10250, owner: String = "com.cardroid.app", name: String = "CarDroid Panel: com.google.android.youtube", flags: String = "FLAG_PRIVATE") =
        "    mBaseDisplayInfo=DisplayInfo{\"$name\", displayId $id, displayGroupId 0, $flags, real 1120 x 920, owner $owner (uid $uid), removeMode 1}"

    @Test fun findsPrivatePanelEvenWithoutPublicDisplayLookup() {
        assertEquals(mapOf(19 to "com.google.android.youtube"), PanelDisplayInfo.ownedPanels(info(), 10250))
    }
    @Test fun rejectsOtherOwnersAndUids() {
        assertTrue(PanelDisplayInfo.ownedPanels(info(uid = 10249), 10250).isEmpty())
        assertTrue(PanelDisplayInfo.ownedPanels(info(owner = "other.app"), 10250).isEmpty())
    }
    @Test fun rejectsMainDisplayAndNonPanelDisplays() {
        assertTrue(PanelDisplayInfo.ownedPanels(info(id = 0), 10250).isEmpty())
        assertTrue(PanelDisplayInfo.ownedPanels(info(name = "CarDroid Car"), 10250).isEmpty())
        assertTrue(PanelDisplayInfo.ownedPanels(info(flags = "FLAG_PRESENTATION"), 10250).isEmpty())
    }
    @Test fun ignoresOverrideAndHistoricalDisplayDetails() {
        assertTrue(PanelDisplayInfo.ownedPanels(info().replace("mBaseDisplayInfo", "mOverrideDisplayInfo"), 10250).isEmpty())
    }
    @Test fun keepsIndependentCells() {
        assertEquals(2, PanelDisplayInfo.ownedPanels(info() + "\n" + info(id = 20, name = "CarDroid Panel: com.google.android.apps.maps"), 10250).size)
    }
}
