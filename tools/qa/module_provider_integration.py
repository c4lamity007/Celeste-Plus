"""Offline real delivery/provider/runtime integration with server-personalized executable mini JARs."""
from pathlib import Path
import argparse,hashlib,json,subprocess,sys
ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'auth-service'),str(ROOT/'tools')]
from app.module_delivery import personalize,executable_root
from app.security import b64e,canonical_json
from module_release import entries_digest,zip_bytes

def main():
 p=argparse.ArgumentParser();p.add_argument('--java-home',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--classpath-args',type=Path,required=True);p.add_argument('--resident-classes',type=Path,required=True);a=p.parse_args()
 out=a.output.resolve();out.mkdir(parents=True,exist_ok=True);classes=out/'classes';classes.mkdir(exist_ok=True)
 args=[json.loads(line) for line in a.classpath_args.read_text().splitlines() if line.strip()]
 cp=str(a.resident_classes.resolve())+';'+str(ROOT/'build/classes/java/main')+';'+args[args.index('-cp')+1]
 delivery=ROOT/'runtime-overlay/src/main/java/dev/c4lam/celeste/module/delivery'
 sources=[delivery/name for name in ['ModuleEntrypoint.java','ModulePayloadLease.java','ModulePayloadProvider.java','ModulePayloadRuntime.java','ModuleScope.java','ModuleServices.java','ModuleHookRegistry.java','ModuleHookAbi.java','ModuleResourceAssets.java']]
 sources += list((ROOT/'beta-lab/delivery-client/src/dev/c4lam/celeste/beta/delivery').glob('*.java'))
 sources += [ROOT/'beta-lab/shared-auth/src/main/java/dev/c4lam/celeste/auth/DpopProofSigner.java',ROOT/'beta-lab/memory-runtime/src/main/java/dev/c4lam/celeste/beta/memory/ModuleMemoryClassLoader.java',ROOT/'runtime-overlay/src/main/java/dev/c4lam/celeste/auth/AuthenticatedModulePayloadProvider.java',ROOT/'beta-lab/delivery-client/test/dev/c4lam/celeste/beta/delivery/BetaDeliveryClientCheck.java',ROOT/'tools/qa/module-delivery/ProviderFixture.java',ROOT/'tools/qa/module-delivery/ProviderIntegrationCheck.java']
 def run_args(exe,args,name):
  argfile=out/(name+'.args');argfile.write_text('\n'.join(json.dumps(str(v).replace('\\','/')) for v in args),encoding='utf-8')
  r=subprocess.run([str(a.java_home/'bin'/exe),'@'+str(argfile)],cwd=ROOT,capture_output=True,text=True,timeout=90)
  (out/(name+'.log')).write_text(r.stdout+r.stderr,encoding='utf-8')
  if r.returncode:print(r.stdout+r.stderr);raise SystemExit(r.returncode)
  return r
 run_args('javac.exe',['--release','21','-proc:none','-encoding','UTF-8','-cp',cp,'-d',classes,*sources],'compile')
 child=out/'child';child.mkdir(exist_ok=True);source=out/'Entry.java'
 source.write_text("""package fixture.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.*;
public final class Entry implements ModuleEntrypoint,ModuleFeature {
 static {if(ModuleScope.current()==null || !java.util.Arrays.equals(ModuleResourceAssets.bytes("assets/celeste/provider-fixture.txt"),"verified fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new ExceptionInInitializerError("Scoped verified asset unavailable before static initialization");}
 public Entry(){} public String name(){return "fixture";}
 public ModuleFeature create(ModuleServices services){services.hook("fixture.value",args->"active");return new Entry();}
}
""",encoding='utf-8',newline='\n')
 run_args('javac.exe',['--release','21','-proc:none','-encoding','UTF-8','-cp',str(classes)+';'+cp,'-d',child,source],'child-compile')
 entries={'fixture/payload/Entry.class':(child/'fixture/payload/Entry.class').read_bytes(),'assets/celeste/provider-fixture.txt':b'verified fixture'}
 jar=zip_bytes(entries);inventory=entries_digest(entries);records=[]
 for index,identity in enumerate(['fixture_a','fixture_b','fixture_c']):
  artifact=f'{index+1:032x}';records.append({'id':identity,'version':'fixture-v1','artifact_id':artifact,'entrypoint':'fixture.payload.Entry','filename':artifact+'.jar','sha256':hashlib.sha256(jar).hexdigest(),'size':len(jar),'entries_sha256':inventory})
 catalog={'schema':'celeste-module-release-1','product':'celeste_plus','release_id':'fixture-provider-v1','host_abi':1,'runtime_sha256':'a'*64,'modules':records};catalog['executable_root_sha256']=executable_root(catalog)
 digest=hashlib.sha256(canonical_json(catalog)).hexdigest();golden={'catalog':catalog,'catalog_sha256':digest,'artifacts':[]}
 for record in records:
  key,ciphertext,manifest=personalize(catalog,digest,record,jar)
  golden['artifacts'].append({'manifest':manifest,'key_base64url':b64e(key),'ciphertext_base64url':b64e(ciphertext),'approved_jar_base64url':b64e(jar)})
 fixture=out/'synthetic-fixture.json';fixture.write_bytes(canonical_json(golden))
 result=run_args('java.exe',['-ea','-cp',str(classes)+';'+cp,'dev.c4lam.celeste.auth.ProviderIntegrationCheck',fixture],'behavior');print(result.stdout)
if __name__=='__main__':main()
