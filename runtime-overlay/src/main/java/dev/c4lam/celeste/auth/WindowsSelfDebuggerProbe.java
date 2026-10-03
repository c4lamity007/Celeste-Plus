package dev.c4lam.celeste.auth;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

/** Current JVM only. No process inventory, timing heuristic, driver scan or persistence. */
final class WindowsSelfDebuggerProbe {
    enum Result { CLEAR, CONFIRMED, UNAVAILABLE }
    private interface Api extends StdCallLibrary {
        Api INSTANCE=Native.load("kernel32",Api.class);
        int IsDebuggerPresent();
        int CheckRemoteDebuggerPresent(Pointer process,IntByReference result);
    }
    static Result read() {
        if(!System.getProperty("os.name","").startsWith("Windows"))return Result.UNAVAILABLE;
        try {
            int direct=Api.INSTANCE.IsDebuggerPresent();
            IntByReference remote=new IntByReference();
            // The long overload preserves the all-bits-set pseudo-handle on x64.
            int ok=Api.INSTANCE.CheckRemoteDebuggerPresent(currentProcessHandle(),remote);
            return classify(direct!=0,ok!=0,remote.getValue()!=0);
        } catch(LinkageError | RuntimeException unavailable) { return Result.UNAVAILABLE; }
    }
    static Result classify(boolean direct,boolean callSucceeded,boolean attached) {
        if(direct || callSucceeded && attached)return Result.CONFIRMED;
        return callSucceeded?Result.CLEAR:Result.UNAVAILABLE;
    }
    static Pointer currentProcessHandle(){return Pointer.createConstant(-1L);}
    private WindowsSelfDebuggerProbe() { }
}
