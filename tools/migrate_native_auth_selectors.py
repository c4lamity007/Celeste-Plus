"""Create a new exact-selector producer bundle, preserving historical bytes and all VMP approval boundaries."""
from pathlib import Path
import argparse,copy,json,os,subprocess
import protect_auth_native_release as release
ROOT=Path(__file__).resolve().parents[1]
def migrate(a):
 previous=a.previous_bundle.resolve(strict=True)
 bundle=release.load_bundle(previous,a.previous_sha256)
 policy=release.document(ROOT/'beta-lab/obfuscation/native-auth-producer.json')
 release.require(policy['native_tool_bundle_sha256']==a.previous_sha256,'Previous producer is not the current reviewed source-policy input')
 runtime=release.archive(a.runtime)
 out=a.output.resolve();release.require(not out.exists(),'Use a fresh selector migration directory')
 out.mkdir(parents=True);check=out/'check';check.mkdir();classes=check/'classes';classes.mkdir()
 cp=release.tool_cp(bundle,previous.parent,'reembed_classpath')
 java=a.java_home/'bin/java.exe';javac=a.java_home/'bin/javac.exe'
 release.command_step(check,'compile-selector-check',[javac,'-Xlint:all','-Werror','-cp',cp,'-d',classes,ROOT/'tools/qa/AuthNativeSelectionCheck.java'])
 selectors={};class_pins={};changes={}
 for kind,owner in release.OWNERS.items():
  release.require(owner+'.class' in runtime,'Current runtime omits canonical selected owner')
  entry=runtime[owner+'.class'];classfile=check/(kind+'.class');classfile.write_bytes(entry);class_pins[owner]=release.sha(entry)
  source=ROOT/'tools/packaging/native-auth-producer/selectors'/(kind+'.methods');selectors[kind]=source.read_bytes()
  old=(previous.parent/bundle['selections'][kind]['methods']).read_bytes()
  changes[kind]={'before_sha256':release.sha(old),'after_sha256':release.sha(selectors[kind]),'before':old.decode().splitlines(),'after':selectors[kind].decode().splitlines()}
  command=[java,'-cp',str(classes)+os.pathsep+cp,'AuthNativeSelectionCheck',classfile,source]
  if a.named_runtime:command.append('--named-runtime')
  release.command_step(check,'check-'+kind,command,required_output=['PASS native-selector-bodies='])
 receipt={'schema':'celeste-auth-native-selector-update-2','scope':'Exact method selection adaptation only; no VMP/JNI/publication approval',
  'previous_bundle_sha256':a.previous_sha256,'runtime_sha256':release.sha(a.runtime.read_bytes()),'runtime_namespace':'named' if a.named_runtime else 'intermediary',
  'runtime_class_sha256':class_pins,'selections':changes,'semantic_checker_sha256':release.sha((ROOT/'tools/qa/AuthNativeSelectionCheck.java').read_bytes()),
  'producer_implementation_changed':False,'production_approved':False}
 target=out/'bundle';target.mkdir();updated=copy.deepcopy(bundle)
 for name,digest in bundle['files'].items():
  data=release.read(previous.parent/name);release.require(release.sha(data)==digest,'Producer input changed while copying')
  destination=target/name;destination.parent.mkdir(parents=True,exist_ok=True);destination.write_bytes(data)
 for kind,data in selectors.items():
  path=bundle['selections'][kind]['methods'];(target/path).write_bytes(data);updated['files'][path]=release.sha(data)
 provenance=release.document(target/bundle['provenance']);provenance['prior_selection_update']=provenance.pop('selection_update',None)
 provenance['selection_update']=receipt
 release.write_json(target/bundle['provenance'],provenance);updated['files'][bundle['provenance']]=release.sha((target/bundle['provenance']).read_bytes())
 receipt_name='selector-update-20261002.json';release.write_json(target/receipt_name,receipt);updated['files'][receipt_name]=release.sha((target/receipt_name).read_bytes())
 release.write_json(target/'native-tool-bundle.json',updated);pin=release.sha((target/'native-tool-bundle.json').read_bytes())
 release.load_bundle(target/'native-tool-bundle.json',pin)
 unchanged=set(bundle['files'])-set(value['methods'] for value in bundle['selections'].values())-{bundle['provenance']}
 release.require(all(updated['files'][name]==bundle['files'][name] for name in unchanged),'Non-selector producer input was changed')
 candidate=copy.deepcopy(policy);candidate['native_tool_bundle_sha256']=pin;candidate['adaptation_provenance_sha256']=updated['files'][bundle['provenance']]
 for kind,value in candidate['method_selections'].items():value['sha256']=updated['files'][bundle['selections'][kind]['methods']]
 candidate['scope']='Exact selector/source adaptation candidate only; requires source review before adoption; not native protection or publication approval.'
 candidate['previous_known_checks']=candidate.pop('known_checks',{})
 candidate['selector_preflight']={'selected_bodies':sum(len(data.decode().splitlines()) for data in selectors.values()),'input_namespace':receipt['runtime_namespace'],'production_approved':False}
 release.write_json(out/'producer-policy-candidate.json',candidate)
 release.write_json(out/'result.json',{'status':'selector-bundle-candidate-not-production-approved','bundle':str(target/'native-tool-bundle.json'),'sha256':pin,'unchanged_producer_files':len(unchanged),'policy_candidate':str(out/'producer-policy-candidate.json')})
 print((out/'result.json').read_text())
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--previous-bundle',type=Path,required=True);p.add_argument('--previous-sha256',required=True);p.add_argument('--runtime',type=Path,required=True);p.add_argument('--java-home',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--named-runtime',action='store_true');migrate(p.parse_args())
if __name__=='__main__':main()
