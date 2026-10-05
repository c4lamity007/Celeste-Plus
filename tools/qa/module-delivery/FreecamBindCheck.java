package fixture;

import dev.c4lam.celeste.gui.model.GuiCatalog;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.ModuleManager;
import dev.c4lam.celeste.module.delivery.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual production manager and independent lifecycle; the child only records flight intent. */
public final class FreecamBindCheck {
    static int assertions,cases;
    static byte[] child;
    static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        child=Files.readAllBytes(Path.of(args[0]));
        for(int taps=1;taps<=6;taps++)try(Harness h=new Harness()){
            for(int n=0;n<taps;n++)h.press(66);
            check(GuiCatalog.FREECAM.enabled()&&h.requests.size()==1,"Pending presses preserve one enabled module generation");
            Lease lease=h.deliver();
            check(lease.flight()==(taps%2==1),"Loading press parity survives asynchronous completion: "+taps);
            check(lease.number("starts")==1,"Payload behavior starts once");
            h.press(66);check(lease.flight()==(taps%2==0),"Active bind toggles only flight");
            check(GuiCatalog.FREECAM.enabled()&&!lease.closed&&h.requests.size()==1,"Active bind keeps original payload loaded without another authorization");
            GuiCatalog.FREECAM.setEnabled(false);h.sync();h.client.drain();
            check(lease.closed&&h.manager.features().isEmpty()&&lease.number("stops")==1,"GUI disable retires and unloads the original payload");cases++;
        }
        try(Harness h=new Harness()){
            h.press(66);h.press(66);GuiCatalog.FREECAM.setEnabled(false);h.sync();
            Lease stale=h.deliver();check(stale.closed&&stale.feature==null,"GUI disable during load rejects the outdated generation");
            GuiCatalog.FREECAM.setEnabled(true);h.sync();Lease next=h.deliver();
            check(next.flight(),"GUI disable clears pending parity before subsequent GUI enable");cases++;
        }
        try(Harness h=new Harness()){
            h.press(66);h.client.currentScreen=new Object();InputUtil.pressed=66;h.bind();
            h.client.currentScreen=null;h.bind();InputUtil.pressed=-1;h.bind();Lease lease=h.deliver();
            check(lease.flight(),"Closing a screen with B held cannot manufacture a pending flight toggle");
            h.press(66);check(!lease.flight()&&!lease.closed,"Next fresh physical edge toggles flight");cases++;
        }
        try(Harness h=new Harness()){
            h.press(66);h.press(66);GuiCatalog.BIND.setKeyCode(-1);Lease lease=h.deliver();
            check(!lease.flight(),"Changing the keybind does not discard already requested flight state");cases++;
        }
        try(Harness h=new Harness()){
            h.press(67);check(GuiCatalog.OTHER.enabled(),"Other module bind still enables the module");
            h.press(67);check(!GuiCatalog.OTHER.enabled(),"Other module bind still disables it normally");
            h.requests.getFirst().completeExceptionally(new CancellationException());h.client.drain();cases++;
        }
        System.out.println("{\"passed_cases\":"+cases+",\"assertions\":"+assertions+",\"failed\":0}");
    }
    static final class Harness implements AutoCloseable{
        final MinecraftClient client=MinecraftClient.getInstance();
        final ModuleManager manager;
        final List<CompletableFuture<ModulePayloadLease>> requests=new ArrayList<>();
        final Method bind,sync;
        Harness()throws Exception{
            GuiCatalog.FREECAM.setEnabled(false);GuiCatalog.OTHER.setEnabled(false);GuiCatalog.BIND.setKeyCode(66);InputUtil.pressed=-1;client.currentScreen=null;
            var constructor=ModuleManager.class.getDeclaredConstructor();constructor.setAccessible(true);manager=constructor.newInstance();
            manager.configurePayloads((id,generation)->{var result=new CompletableFuture<ModulePayloadLease>();requests.add(result);return result;});manager.registerPayloadCatalog();
            bind=ModuleManager.class.getDeclaredMethod("handleKeybinds",MinecraftClient.class);bind.setAccessible(true);
            sync=ModuleManager.class.getDeclaredMethod("syncActive",MinecraftClient.class);sync.setAccessible(true);
        }
        void bind()throws Exception{bind.invoke(manager,client);}
        void sync()throws Exception{sync.invoke(manager,client);}
        void press(int key)throws Exception{InputUtil.pressed=key;bind();InputUtil.pressed=-1;bind();sync();}
        Lease deliver()throws Exception{Lease lease=new Lease();requests.getLast().complete(lease);client.drain();sync();return lease;}
        public void close()throws Exception{manager.close();for(var future:requests)future.completeExceptionally(new CancellationException());client.drain();manager.payloadsStopped().toCompletableFuture().get(5,TimeUnit.SECONDS);}
    }
    static final class ChildLoader extends ClassLoader{
        ChildLoader(){super(ModuleFeature.class.getClassLoader());}
        Class<?> child(){return defineClass("fixture.ChildFreecamFeature",child,0,child.length);}
    }
    static final class Lease implements ModulePayloadLease{
        final ChildLoader loader=new ChildLoader();ModuleFeature feature;boolean closed;
        public ClassLoader definingLoader(){return loader;}public boolean authorized(){return !closed;}
        public ModuleFeature create(ModuleServices services)throws Exception{
            feature=(ModuleFeature)loader.child().getConstructor().newInstance();
            services.hook("freecam.toggle",args->feature.getClass().getMethod("toggle").invoke(feature));return feature;
        }
        boolean flight()throws Exception{return feature.getClass().getField("flight").getBoolean(feature);}
        int number(String field)throws Exception{return feature.getClass().getField(field).getInt(feature);}
        public void close(){closed=true;}
    }
}
