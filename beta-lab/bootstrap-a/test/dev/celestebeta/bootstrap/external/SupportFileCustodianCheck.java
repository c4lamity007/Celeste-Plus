package dev.celestebeta.bootstrap.external;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

public final class SupportFileCustodianCheck {
    private static int checks;
    private SupportFileCustodianCheck() { }
    public static void main(String[] args)throws Exception {
        if(args.length==1&&args[0].equals("--owner")){System.in.read();return;}
        throw new IllegalArgumentException("Run through bootstrap fixture");
    }
    static void run(Path root)throws Exception {
        Path scope=Files.createDirectory(root.resolve("custodian-fixture"));
        Path current=Files.createDirectory(scope.resolve("100-200")), older=Files.createDirectory(scope.resolve("300-400"));
        String executable=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),"-cp",System.getProperty("java.class.path"),SupportFileCustodianCheck.class.getName(),"--owner")
            .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        long pid=child.pid(), started=child.toHandle().info().startInstant().orElseThrow().toEpochMilli();
        try(SupportFileCustodian custodian=new SupportFileCustodian(current)) {
            Path live=prepared(custodian,older,pid,started);
            check(custodian.sweep()==0&&Files.exists(live.resolve("one.jar")),"active owner never deleted across sibling process roots");
            child.getOutputStream().close();check(child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS),"controlled child exits");
            check(custodian.sweep()==1&&!Files.exists(live),"dead owner recovered from authenticated receipt in old process root");
            for(String failure:new String[]{"foreign","changed","forged","replace","path","directory"}) {
                Path owned=prepared(custodian,older,pid,started);
                switch(failure) {
                    case "foreign"->Files.writeString(owned.resolve("keep.txt"),"unowned");
                    case "changed"->Files.write(owned.resolve("one.jar"),new byte[]{7});
                    case "forged"->{byte[] marker=Files.readAllBytes(owned.resolve(SupportFileCustodian.RECEIPT));marker[marker.length-3]^=1;Files.write(owned.resolve(SupportFileCustodian.RECEIPT),marker);}
                    case "replace"->{Path file=owned.resolve("one.jar");Files.delete(file);Files.write(file,new byte[]{9,8,7});}
                    case "path"->{Path moved=owned.resolveSibling(owned.getFileName()+"-moved");Files.move(owned,moved);owned=moved;}
                    case "directory"->{Files.delete(owned.resolve("one.jar"));Files.createDirectory(owned.resolve("one.jar"));}
                    default->throw new AssertionError(failure);
                }
                check(custodian.sweep()==0&&Files.exists(owned.resolve("two.jar")),"preserve all bytes before deletion on "+failure);
                erase(owned);
            }
            Path partial=prepared(custodian,older,pid,started);Files.delete(partial.resolve("one.jar"));
            check(custodian.sweep()==1&&!Files.exists(partial),"authenticated partially completed cleanup can resume");
            Path active=Files.createTempDirectory(older,"celeste-beta-support-");SupportFileCustodian.restrict(active,true);
            Files.write(active.resolve("one.jar"),new byte[]{1});
            Files.write(active.resolve(SupportFileCustodian.RECEIPT),custodian.receipt(active,Map.of("one.jar",VerifiedBootstrapLoader.hash(new byte[]{1}))));
            check(custodian.sweep()==0&&Files.exists(active.resolve("one.jar")),"current JVM owns its files through reload");erase(active);
            Path noReceipt=Files.createTempDirectory(older,"celeste-beta-support-");SupportFileCustodian.restrict(noReceipt,true);Files.writeString(noReceipt.resolve("do-not-touch"),"unowned");
            check(custodian.sweep()==0&&Files.exists(noReceipt.resolve("do-not-touch")),"matching prefix alone never authorizes cleanup");erase(noReceipt);
        }finally{if(child.isAlive())child.destroyForcibly();erase(scope);}
        System.out.println("PASS support custody: "+checks+" checks; real private permissions, ended fixture child, forged/changed/active ownership preserved");
    }
    private static Path prepared(SupportFileCustodian custodian,Path root,long pid,long started)throws Exception {
        Path directory=Files.createTempDirectory(root,"celeste-beta-support-");SupportFileCustodian.restrict(directory,true);
        byte[] first={1,2,3},second={4,5,6};Files.write(directory.resolve("one.jar"),first);Files.write(directory.resolve("two.jar"),second);
        Files.write(directory.resolve(SupportFileCustodian.RECEIPT),custodian.receipt(directory,Map.of("one.jar",VerifiedBootstrapLoader.hash(first),"two.jar",VerifiedBootstrapLoader.hash(second)),pid,started));
        return directory;
    }
    private static void erase(Path root)throws Exception {
        try(var entries=Files.walk(root)){for(Path file:entries.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(file);}
    }
    private static void check(boolean result,String message){if(!result)throw new AssertionError(message);checks++;}
}
