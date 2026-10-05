package dev.c4lam.celeste.auth;
/** Injected self-debugger result only; this fixture never loads native APIs. */
final class WindowsSelfDebuggerProbe {
    enum Result { CLEAR, CONFIRMED, UNAVAILABLE }
    static volatile Result result=Result.CLEAR;
    static Result read(){return result;}
    private WindowsSelfDebuggerProbe(){ }
}
