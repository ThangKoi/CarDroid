package com.cardroid.app.shizuku;

/** Narrow contract exposed only to CarDroid's Shizuku UserService caller. */
interface IHelperLauncher {
    /** Reserved Shizuku UserService removal transaction. */
    void destroy() = 16777114;

    /** Starts the detached shell helper and returns its verified PID, or -1. */
    int startHelper(String apkPath, int appUid, int port) = 1;

    /** Sends TERM to the verified helper PID(s) and returns whether they exited. */
    boolean stopHelper() = 2;

    /** Returns true when pidof found a numeric cardroid-helper PID. */
    boolean isHelperRunning() = 3;
}
