package dev.c4lam.celeste.module.delivery;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Owner-thread lifecycle bindings for generic action/rotation/input arbiters. */
public final class ScopedActionOwners {
    private final Map<Object, Binding> bindings = new IdentityHashMap<>();
    public static boolean currentAllowed() {
        ModuleScope scope=ModuleScope.current();
        return scope==null || scope.allowed();
    }
    public boolean track(Object owner, Runnable retire) {
        ModuleScope scope=ModuleScope.current();
        if(scope==null)return allowed(owner);
        if(!scope.allowed())return false;
        Binding old=bindings.get(owner);
        if(old!=null && old.scope==scope)return true;
        if(old!=null)release(owner);
        Binding binding=new Binding(owner,scope,retire);
        bindings.put(owner,binding);
        try { scope.ownTransient(binding,binding.done); }
        catch(RuntimeException failure){bindings.remove(owner,binding);binding.discard();throw failure;}
        return true;
    }
    public boolean allowed(Object owner) {
        Binding binding=bindings.get(owner);
        return binding==null || binding.scope.allowed();
    }
    public ModuleScope scope(Object owner) {
        Binding binding=bindings.get(owner);return binding==null?null:binding.scope;
    }
    public void release(Object owner) {
        Binding binding=bindings.remove(owner);if(binding!=null)binding.discard();
    }
    public void clear() {
        var values=bindings.values().toArray(Binding[]::new);bindings.clear();
        for(Binding binding:values)binding.discard();
    }
    public int size(){return bindings.size();}
    private final class Binding implements AutoCloseable {
        private Object owner;
        private final ModuleScope scope;
        private Runnable retire;
        private final CompletableFuture<Void> done=new CompletableFuture<>();
        Binding(Object owner,ModuleScope scope,Runnable retire){this.owner=owner;this.scope=scope;this.retire=retire;}
        void discard(){owner=null;retire=null;done.complete(null);}
        @Override public void close() {
            Runnable action=retire;
            Object key=owner;
            if(key==null || !bindings.remove(key,this)){discard();return;}
            // Drop parent references before invoking cleanup, which can release this binding again.
            owner=null;retire=null;
            try{action.run();done.complete(null);}
            catch(RuntimeException | Error failure){done.completeExceptionally(failure);throw failure;}
        }
    }
}
