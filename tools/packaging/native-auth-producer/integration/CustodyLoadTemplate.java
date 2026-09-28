import java.io.*;
import java.util.Arrays;
import dev.celestebeta.host.AuthNativeBridge;
/** Compile-time template only; this class is never shipped or loaded. */
final class CustodyLoadTemplate {
 private static volatile String loadedResource;
 private static void load(Class<?> owner,String resourceName) {
  byte[] bytes=null;
  try(InputStream input=owner.getResourceAsStream("/"+resourceName)) {
   if(input==null)throw new IOException("Native resource is absent");
   bytes=input.readNBytes(64*1024*1024+1);
   if(bytes.length==0||bytes.length>64*1024*1024)throw new IOException("Native resource exceeds its bound");
   String path=AuthNativeBridge.stage(owner.getClassLoader(),bytes,"EXPECTED_APPROVED_NATIVE_SHA256");
   System.load(path);
   loadedResource=resourceName;
  } catch(IOException|UnsatisfiedLinkError failure) {throw new IllegalStateException(failure);}
  finally {if(bytes!=null)Arrays.fill(bytes,(byte)0);}
 }
}
