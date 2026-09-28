"""Rebuild one fixture class with an owner-queue publication fence; never changes payload."""
import argparse,difflib,hashlib,json,os,re,subprocess,zipfile
from pathlib import Path
ENTRY='dev/c4lam/celeste/auth/AuthBootstrapLifecycleTest.class'
INNER='dev/c4lam/celeste/auth/AuthBootstrapLifecycleTest$CheckedAction.class'
OLD='        await(() -> AuthBootstrap.locked(), 9_000, "bounded recovery exhaustion");\n        CLIENT.drain();'
NEW='        await(() -> AuthBootstrap.locked(), 9_000, "bounded recovery exhaustion");\n        // Revocation publishes before the supervisor queues owner-thread cleanup.\n        await(() -> CLIENT.queuedCount() > 0, 2_000, "queued exhaustion cleanup");\n        CLIENT.drain();'
def need(x,m):
 if not x:raise ValueError(m)
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def pin(path,digest):
 need(re.fullmatch('[0-9a-f]{64}',digest or '') and sha(path)==digest,'Input pin mismatch: '+str(path))
def main():
 p=argparse.ArgumentParser(description=__doc__)
 for n in ('plan','source','payload','java-home','output'):p.add_argument('--'+n,type=Path,required=True)
 for n in ('plan','source','payload'):p.add_argument('--'+n+'-sha256',required=True)
 a=p.parse_args();pin(a.plan,a.plan_sha256);pin(a.source,a.source_sha256);pin(a.payload,a.payload_sha256)
 plan=json.loads(a.plan.read_text());need(plan['schema']=='celeste-auth-native-verification-plan-1' and len(plan['checks'])==41,'Wrong original plan')
 for name,d in plan['files'].items():
  need(not Path(name).is_absolute() and '..' not in Path(name).parts and '\\' not in name,'Unsafe fixture path');pin(a.plan.parent/name,d)
 source=a.source.read_text(encoding='utf-8');need(source.count(OLD)==1 and 'import net.minecraft.class_310;' in source,'Expected original remapped fixture source required')
 corrected=source.replace(OLD,NEW);out=a.output.resolve();need(not out.exists(),'Output must be fresh');out.mkdir(parents=True)
 for name in plan['files']:
  dst=out/name;dst.parent.mkdir(parents=True,exist_ok=True);dst.write_bytes((a.plan.parent/name).read_bytes())
 metadata=out/'queue-fix';metadata.mkdir();base=metadata/'base/AuthBootstrapLifecycleTest.java';base.parent.mkdir();base.write_text(source,encoding='utf-8',newline='\n')
 src=metadata/'corrected/AuthBootstrapLifecycleTest.java';src.parent.mkdir();src.write_text(corrected,encoding='utf-8',newline='\n')
 (metadata/'fixture.patch').write_text(''.join(difflib.unified_diff(source.splitlines(True),corrected.splitlines(True),fromfile='a/AuthBootstrapLifecycleTest.java',tofile='b/AuthBootstrapLifecycleTest.java')),encoding='utf-8',newline='\n')
 classes=out/'rebuilt-classes';classes.mkdir();javac=a.java_home/'bin/javac.exe'
 command=[str(javac),'--release','21','-Xlint:all','-Werror','-cp',os.pathsep.join([str(out/'lifecycle-fixtures.jar'),str(a.payload)]),'-d',str(classes),str(src)]
 result=subprocess.run(command,capture_output=True,text=True,timeout=30);(out/'compile.log').write_text(result.stdout+result.stderr,encoding='utf-8',newline='\n');need(result.returncode==0,'Fixture compile failed; read compile.log')
 generated={f.relative_to(classes).as_posix():f.read_bytes() for f in classes.rglob('*.class')};need(set(generated)=={ENTRY,INNER},'Unexpected compiled fixture classes')
 with zipfile.ZipFile(out/'lifecycle-fixtures.jar') as z: entries={i.filename:z.read(i) for i in z.infolist() if not i.is_dir()}
 need(generated[INNER]==entries[INNER],'Unchanged nested fixture ABI changed');need(generated[ENTRY]!=entries[ENTRY],'Fixture correction absent');old_entry_sha=hashlib.sha256(entries[ENTRY]).hexdigest();entries[ENTRY]=generated[ENTRY]
 with zipfile.ZipFile(out/'lifecycle-fixtures.jar','w',compression=zipfile.ZIP_DEFLATED) as z:
  for name,data in sorted(entries.items()):
   info=zipfile.ZipInfo(name,(2026,9,28,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,data)
 # Prove that every archive entry besides this fixture class remains byte-identical.
 with zipfile.ZipFile(a.plan.parent/'lifecycle-fixtures.jar') as old:
  oldentries={i.filename:old.read(i) for i in old.infolist() if not i.is_dir()}
 need(set(entries)==set(oldentries) and [n for n in entries if entries[n]!=oldentries[n]]==[ENTRY],'Unexpected fixture archive changes')
 (metadata/'rebuild.py').write_bytes(Path(__file__).read_bytes())
 receipt={'schema':'celeste-native-auth-lifecycle-queue-fix-1','old_plan_sha256':a.plan_sha256,'original_source_sha256':a.source_sha256,'payload_sha256':a.payload_sha256,'javac_sha256':sha(javac),'compile_exit':result.returncode,'changed_class':ENTRY,'old_class_sha256':old_entry_sha,'new_class_sha256':hashlib.sha256(entries[ENTRY]).hexdigest(),'old_lifecycle_jar_sha256':plan['files']['lifecycle-fixtures.jar'],'new_lifecycle_jar_sha256':sha(out/'lifecycle-fixtures.jar'),'scope':'Fixture-only bounded wait for owner queue; all closure assertions unchanged; production inputs untouched'}
 (metadata/'build-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8',newline='\n')
 plan['files']['lifecycle-fixtures.jar']=sha(out/'lifecycle-fixtures.jar')
 for f in metadata.rglob('*'):
  if f.is_file():plan['files'][f.relative_to(out).as_posix()]=sha(f)
 (out/'verification-plan.json').write_text(json.dumps(plan,indent=2)+'\n',encoding='utf-8',newline='\n')
 pin(a.plan,a.plan_sha256);pin(a.source,a.source_sha256);pin(a.payload,a.payload_sha256)
 print(json.dumps({'plan':str(out/'verification-plan.json'),'sha256':sha(out/'verification-plan.json'),'fixture_sha256':sha(out/'lifecycle-fixtures.jar'),'changed_classes':1,'checks':41}))
if __name__=='__main__':main()
