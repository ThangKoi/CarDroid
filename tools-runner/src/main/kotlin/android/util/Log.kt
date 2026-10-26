// JVM-only stub of android.util.Log so files that only call Log.{i,w,e,d,v}
// from the libcardroid / mfi-bridge modules compile and run on a test host.
// This file is NEVER shipped into the Android app — only the tools-runner
// JVM build sees it.
package android.util

@Suppress("unused", "FunctionName")
object Log {
    private fun ts(): String = java.time.LocalTime.now().toString()
    fun v(tag: String, msg: String): Int { println("V/$tag: $msg"); return 0 }
    fun d(tag: String, msg: String): Int { println("D/$tag: $msg"); return 0 }
    fun i(tag: String, msg: String): Int { println("I/$tag: $msg"); return 0 }
    fun w(tag: String, msg: String): Int { println("W/$tag: $msg"); return 0 }
    fun w(tag: String, msg: String, t: Throwable): Int {
        println("W/$tag: $msg")
        t.printStackTrace()
        return 0
    }
    fun e(tag: String, msg: String): Int { println("E/$tag: $msg"); return 0 }
    fun e(tag: String, msg: String, t: Throwable): Int {
        println("E/$tag: $msg")
        t.printStackTrace()
        return 0
    }
}
