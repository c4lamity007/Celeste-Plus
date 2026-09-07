package dev.c4lam.celeste.compat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.NOPLogger;

/** Beta logging is local to this payload; never changes Minecraft's logger configuration. */
public final class RuntimeLogging {
    private static volatile boolean quiet;
    private RuntimeLogging() { }
    public static void quietBeta() { quiet = true; }
    public static Logger getLogger(Class<?> type) { return getLogger(type.getName()); }
    public static Logger getLogger(String name) {
        Logger ordinary = LoggerFactory.getLogger(name);
        return (Logger) Proxy.newProxyInstance(RuntimeLogging.class.getClassLoader(), new Class<?>[] {Logger.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("getName")) return name;
                if (method.getName().equals("equals")) return proxy == arguments[0];
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("toString")) return "Celeste logger: " + name;
                try { return method.invoke(quiet ? NOPLogger.NOP_LOGGER : ordinary, arguments); }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
    }
}
