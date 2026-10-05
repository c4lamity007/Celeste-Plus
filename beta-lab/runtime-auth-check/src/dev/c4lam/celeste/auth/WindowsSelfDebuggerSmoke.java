package dev.c4lam.celeste.auth;

/** Explicit local ABI smoke only. No controller, transport, license, debugger attach or stop action. */
public final class WindowsSelfDebuggerSmoke {
    public static void main(String[] args) {
        WindowsSelfDebuggerProbe.Result result=WindowsSelfDebuggerProbe.read();
        System.out.println(result);
        if(result!=WindowsSelfDebuggerProbe.Result.CLEAR)
            throw new AssertionError("Unable to verify the clear current-process probe baseline");
    }
    private WindowsSelfDebuggerSmoke(){ }
}
