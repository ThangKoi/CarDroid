package com.cardroid.runner

import com.cardroid.lib.airplay.RemoteTouch
import kotlin.test.*

class RemoteTouchTest {
    private fun report(x: Int,y: Int,down: Boolean=true) = byteArrayOf(0,if(down) 1 else 0,
        x.toByte(),(x ushr 8).toByte(),y.toByte(),(y ushr 8).toByte(),1,0,0,0,0,0)
    @Test fun receiverCenterMapsToLandscapePhoneCenter() {
        val point=RemoteTouch.decode(report(960,540),1920,1080,1280,720,3040,1440).first()
        assertEquals(1520f,point.x,0.1f);assertEquals(720f,point.y,0.1f);assertTrue(point.down)
    }
    @Test fun letterboxBarsDoNotTriggerTouchesAndUpRemainsUp() {
        assertFalse(RemoteTouch.decode(report(960,0),1920,1080,1280,720,3040,1440).first().down)
        assertFalse(RemoteTouch.decode(report(960,540,false),1920,1080,1280,720,3040,1440).first().down)
    }
    @Test fun portraitPhoneInLandscapeCanvasUsesInverseFit() {
        val point=RemoteTouch.decode(report(640,360),1280,720,1280,720,1440,3040).first()
        assertEquals(720f,point.x,0.1f);assertEquals(1520f,point.y,0.1f)
        assertFalse(RemoteTouch.decode(report(10,360),1280,720,1280,720,1440,3040).first().down)
    }
    @Test fun malformedReportsAreRejected() {
        assertFails { RemoteTouch.decode(byteArrayOf(),1,1,1,1,1,1) }
        assertFails { RemoteTouch.decode(report(0,0),0,1,1,1,1,1) }
    }
}

class RemoteTouchDirectTest {
    private fun slot(id: Int,x: Int,y: Int,down: Boolean) = byteArrayOf(id.toByte(),if(down) 1 else 0,x.toByte(),(x ushr 8).toByte(),y.toByte(),(y ushr 8).toByte())
    private fun report(x: Int,y: Int,down: Boolean=true) = slot(0,x,y,down)+slot(1,0,0,false)
    @Test fun identityWhenReceiverEqualsDisplay() {
        val c=RemoteTouch.decodeDirect(report(640,360),1280,720,1280,720)
        assertEquals(2,c.size);assertEquals(640f,c[0].x);assertEquals(360f,c[0].y);assertTrue(c[0].down);assertFalse(c[1].down)
    }
    @Test fun scalesWhenReceiverDiffersFromDisplay() {
        val c=RemoteTouch.decodeDirect(report(640,336),1280,672,1280,720).first()
        assertEquals(640f,c.x);assertEquals(360f,c.y)
        assertEquals(0f,RemoteTouch.decodeDirect(report(0,0),1280,672,1280,720).first().x)
    }
    @Test fun clampsToDisplayEdges() {
        val c=RemoteTouch.decodeDirect(report(65535,65535),1280,720,1280,720).first()
        assertEquals(1279f,c.x);assertEquals(719f,c.y)
    }
    @Test fun touchBitUpAndSecondSlotDecoded() {
        assertFalse(RemoteTouch.decodeDirect(report(10,10,false),1280,720,1280,720).first().down)
        val two=RemoteTouch.decodeDirect(slot(0,100,100,true)+slot(1,200,300,true),1280,720,1280,720)
        assertEquals(1,two[1].id);assertEquals(200f,two[1].x);assertEquals(300f,two[1].y);assertTrue(two[1].down)
    }
    @Test fun malformedReportsAndZeroSizesRejected() {
        assertFails { RemoteTouch.decodeDirect(byteArrayOf(),1,1,1,1) }
        assertFails { RemoteTouch.decodeDirect(ByteArray(11),1280,720,1280,720) }
        assertFails { RemoteTouch.decodeDirect(report(0,0),0,720,1280,720) }
        assertFails { RemoteTouch.decodeDirect(report(0,0),1280,720,1280,0) }
    }
}
