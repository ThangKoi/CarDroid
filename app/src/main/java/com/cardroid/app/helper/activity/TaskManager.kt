package com.cardroid.app.helper.activity

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.IBinder
import com.cardroid.app.helper.HelperProtocol
import com.cardroid.app.helper.display.FakeContext
import java.lang.reflect.Method

/**
 * Lists and reorders tasks as the shell UID through IActivityTaskManager (reflection; AOSP ActivityTaskManagerService.getTasks :2406-2428
 * needs REAL_GET_TASKS, moveTaskToFront needs REORDER_TASKS - both held by shell). Every failure throws; the caller reports ERROR(TASKS_FAILED).
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
class TaskManager(private val log: (String) -> Unit) {
    private val atm: Any by lazy {
        val binder = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String::class.java).invoke(null, "activity_task") as? IBinder
            ?: error("activity_task service unavailable")
        Class.forName("android.app.IActivityTaskManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }
    private val getTasksMethod: Method by lazy {
        val i = Int::class.javaPrimitiveType; val z = Boolean::class.javaPrimitiveType
        atm.javaClass.getMethod("getTasks", i, z, z, i)
    }
    private val moveMethod: Method by lazy {
        atm.javaClass.methods.firstOrNull { it.name == "moveTaskToFront" && it.parameterTypes.size == 5 } ?: error("moveTaskToFront(5 args) not found")
    }

    /** Running tasks of [displayId], most recent first (the system order), at most [max]. */
    fun list(displayId: Int, max: Int): List<HelperProtocol.TaskEntry> {
        val raw = getTasksMethod.invoke(atm, max, false, false, displayId) as? List<*> ?: error("getTasks returned null")
        val out = ArrayList<HelperProtocol.TaskEntry>()
        for (t in raw) {
            if (t == null) continue
            val c = t.javaClass
            val id = c.getField("taskId").getInt(t)
            val comp = (c.getField("topActivity").get(t) as? android.content.ComponentName)
                ?: (c.getField("baseActivity").get(t) as? android.content.ComponentName)
                ?: (c.getField("baseIntent").get(t) as? android.content.Intent)?.component
            out.add(HelperProtocol.TaskEntry(id, comp?.packageName ?: "", comp?.flattenToString() ?: ""))
            if (out.size >= max) break
        }
        log("Tasks: display $displayId -> ${out.size} task(s)")
        return out
    }

    fun moveToFront(taskId: Int) {
        moveMethod.invoke(atm, null, FakeContext.PACKAGE_NAME, taskId, 0, null as Bundle?)
        log("Tasks: moveTaskToFront $taskId")
    }
}
