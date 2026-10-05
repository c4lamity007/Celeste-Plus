"""Separate, sealed Java-then-native authentication candidate workflow.

Preparation stops for external VMP transformation/review. Resume accepts explicit
reviewed resource/index pins and never re-runs randomized payload Java protection.
No signing, publication, server mutation, or automatic protection approval.
"""
from __future__ import annotations
import argparse
import copy
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import zipfile
import protect_java_release as java_release
import native_auth_resources as approval_api

ROOT=Path(__file__).resolve().parents[1]
OWNERS={
 'auth':'dev/c4lam/celeste/auth/AuthServiceClient',
 'signer':'dev/c4lam/celeste/auth/DpopProofSigner',
 'bootstrap':'dev/c4lam/celeste/auth/AuthBootstrap',
}
BRIDGE='dev/celestebeta/host/AuthNativeBridge.class'
MAX_JAR=128*1024*1024
MAX_ENTRY=8*1024*1024

def require(value,why):
    if not value:raise ValueError('Native auth pipeline: '+why)
def read(path,limit=MAX_JAR):return java_release.bounded(Path(path),limit)
def sha(data):return hashlib.sha256(data).hexdigest()
def canonical(value):return java_release.canonical(value)
def document(path,limit=1024*1024):return approval_api.document(read(path,limit))
def write_json(path,value):path.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')
def safe(name):
    require(approval_api.path_name(name),'Unsafe relative name')
    return name
def archive(path, native_approval=None, artifact="payload"):
    result={};total=0
    with zipfile.ZipFile(io.BytesIO(read(path))) as jar:
        for entry in jar.infolist():
            if entry.is_dir():continue
            name=safe(entry.filename)
            limit=approval_api.entry_limit(name,artifact,native_approval)
            require(name not in result and entry.file_size<=limit,'Duplicate/oversized archive entry')
            require(not re.fullmatch(r'META-INF/[^/]+\.(SF|RSA|DSA|EC)',name,re.I),'Archive is already signed')
            total+=entry.file_size
            require(total<=MAX_JAR,'Archive expanded size exceeds bound')
            with jar.open(entry) as stream:data=stream.read(limit+1)
            require(len(data)==entry.file_size,'Archive expanded entry size differs')
            approval_api.validate_entry(name,data,artifact,native_approval)
            result[name]=data
    return result
def write_archive(path,entries):
    with zipfile.ZipFile(path,'x',compression=zipfile.ZIP_DEFLATED) as jar:
        for name,data in sorted(entries.items()):
            item=zipfile.ZipInfo(safe(name),date_time=(1980,1,1,0,0,0));item.compress_type=zipfile.ZIP_DEFLATED
            jar.writestr(item,data)
def sources():
    values=java_release.source_inputs()
    for relative in ('tools/protect_auth_native_release.py','tools/qa/NativeMethodParity.java','tools/qa/AuthNativeSelectionCheck.java','beta-lab/obfuscation/native-auth-producer.json'):
        values[relative]=sha(read(ROOT/relative))
    return dict(sorted(values.items()))
def inventory(root):
    result={}
    for path in sorted(root.rglob('*')):
        require(not path.is_symlink(),'Linked candidate output')
        if path.is_file():result[path.relative_to(root).as_posix()]=sha(read(path))
    return result
def verify_files(root,files):
    for name,digest in files.items():
        path=root/safe(name)
        require(path.resolve(strict=True).is_relative_to(root.resolve()),'Input escapes root')
        require(sha(read(path))==digest,'Sealed input changed: '+name)
def load_bundle(path,expected):
    require(java_release.HEX.fullmatch(expected or '') and sha(read(path))==expected,'Native producer index pin mismatch')
    value=document(path)
    require(value.get('schema')=='celeste-auth-native-tool-bundle-1','Wrong native producer schema')
    files=value.get('files');require(isinstance(files,dict) and 1<=len(files)<=4096,'Invalid producer file count')
    require(all(java_release.HEX.fullmatch(v or '') for v in files.values()),'Invalid producer hash')
    verify_files(path.parent,files)
    require(sum((path.parent/n).stat().st_size for n in files)<=256*1024*1024,'Native tool bundle too large')
    require(set(value['selections'])==set(OWNERS),'Wrong three-class selection')
    for kind,owner in OWNERS.items():
        selection=value['selections'][kind]
        require(selection['owner']==owner and selection['methods'] in files,'Invalid native selection')
        methods=read(path.parent/selection['methods'],65536).decode().splitlines()
        require(methods and len(methods)==len(set(methods)) and all(m.startswith(owner+'#') and '(' in m for m in methods),'Wrong method selectors')
    for key in ('producer_classpath','reembed_classpath'):
        require(isinstance(value[key],list) and 1<=len(value[key])<=32,'Invalid native tool classpath')
        for name in value[key]:
            safe(name);where=path.parent/name
            if where.is_dir():
                actual={p.relative_to(path.parent).as_posix() for p in where.rglob('*') if p.is_file()}
                declared={n for n in files if n.startswith(name+'/')}
                require(actual==declared and declared,'Unlisted producer classpath files')
            else:require(name in files and name.endswith('.jar'),'Unpinned producer classpath')
    require(value['template'] in files and value['provenance'] in files,'Missing producer provenance/template')
    return value
def command_step(root,name,command,timeout=600,required_output=None):
    with (root/(name+'.log')).open('xb') as log:
        result=subprocess.run(list(map(str,command)),cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,timeout=timeout)
    require(result.returncode==0,'Failed '+name+'; inspect candidate log')
    if required_output is not None:
        log_text=read(root/(name+'.log'),8*1024*1024).decode('utf-8','replace')
        require(all(marker in log_text for marker in required_output),'Missing actual check completion marker: '+name)
    print('PASS '+name,flush=True)
def tool_cp(bundle,tool,key):return os.pathsep.join(str(tool/safe(n)) for n in bundle[key])
def native_profile(source,target,deps,owner):
    # All string/helper transformations already ran in the first Java stage.
    return {'schemaVersion':1,'inputJar':str(source),'outputJar':str(target),'dependencyJars':list(map(str,deps)),
      'keepPatterns':['dev.c4lam.celeste.auth.**'],'renameClasses':False,'renamePrivateMembers':False,
      'encryptStrings':False,'stripDebugInfo':False,'doubleStringProtection':False,'compositeStringModeOnly':False,
      'autoDetectFrameworkClasses':False,'autoDetectAuthenticationClasses':False,'transformKeptApplicationClasses':True,
      'nativeTranspile':True,'nativeVirtualization':False,'priorityNativePatterns':[owner.replace('/','.')],
      'targetedNativeVirtualization':False,'virtualizeExistingNativeLibraries':False,'requiredAuthMethods':[],
      'crazyObfuscation':False,'relocateAssets':False,'relocateMetadataClasses':False,
      'runtimeStringConcealment':False,'flowObfuscateStrings':False,'hardenStringHelpers':False,
      'protectNonPublicStaticStrings':False,'protectGuiVisualStrings':False,'obfuscateGuiVisualClasses':False}
def selected_functions(symbols,methods):
    actual=[r['owner']+'#'+r['method']+r['descriptor'] for r in symbols['functions']]
    require(len(actual)==len(set(actual)) and set(actual)==set(methods),'Native method coverage mismatch')
    require(all(not r['constructor_tail'] and r['actual_mode'] in {'NATIVE_DIRECT_CFG','NATIVE_DISPATCH'} for r in symbols['functions']),'Incomplete native method body')
def merge_entries(original,parts):
    require(BRIDGE in original,'Canonical AuthNativeBridge is missing')
    merged=dict(original);allowed={owner+'.class' for owner in OWNERS.values()}
    changed=set()
    for owner,entries in parts:
        require(owner+'.class' in entries,'Selected output class missing')
        for name,data in entries.items():
            if name in original:
                require(name==owner+'.class' or data==original[name],'Native producer replaced unselected Java/resource: '+name)
                if data!=original[name]:changed.add(name)
            else:require(name not in merged,'Generated resource/helper collision')
            merged[name]=data
    require(changed==allowed,'All and only three selected class entries must change')
    require(merged[BRIDGE]==original[BRIDGE],'Canonical custody bridge changed')
    return merged

def prepare(args):
    output=args.output.resolve();require(not output.exists(),'Use a fresh native candidate directory')
    bundle=load_bundle(args.native_tool_bundle.resolve(strict=True),args.native_tool_sha256)
    policy=document(ROOT/'beta-lab/obfuscation/native-auth-producer.json')
    require(policy.get('schema')=='celeste-auth-native-producer-policy-1'
            and policy['native_tool_bundle_sha256']==args.native_tool_sha256
            and policy['adaptation_provenance_sha256']==bundle['files'][bundle['provenance']]
            and policy['selected_owners']==OWNERS,'Native producer is outside reviewed source policy')
    before_sources=sources();output.mkdir(parents=True)
    checkpoint_args=copy.copy(args);checkpoint_args.output=output/'java-checkpoint'
    report=java_release.run(checkpoint_args,payload_mode='checkpoint')
    require(report['status']=='java-payload-checkpoint-not-published','Java protection checkpoint failed')
    payload=output/'java-checkpoint/payload-protected.jar'
    require(sha(read(payload))==report['payload_merged_sha256'],'Merged Java checkpoint changed')
    entries=archive(payload);require(BRIDGE in entries,'Native mode requires canonical parent bridge')
    tool=output/'native-tool';tool.mkdir()
    for name in bundle['files']:
        target=tool/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(read(args.native_tool_bundle.parent/name))
    (tool/'native-tool-bundle.json').write_bytes(read(args.native_tool_bundle))
    parents=java_release.checked_parents(args.parent_classpath)
    java=args.java_home.resolve()/'bin/java.exe';javac=args.java_home.resolve()/'bin/javac.exe'
    parity=output/'parity-classes';parity.mkdir()
    cp=tool_cp(bundle,tool,'reembed_classpath')
    command_step(output,'compile-method-parity',[javac,'-Xlint:all','-Werror','-cp',cp,'-d',parity,ROOT/'tools/qa/NativeMethodParity.java',ROOT/'tools/qa/AuthNativeSelectionCheck.java'])
    candidates={};parts=[]
    for kind,owner in OWNERS.items():
        stage=output/kind;stage.mkdir()
        source=stage/'input.jar';support=stage/'support.jar';target=stage/'native.jar';export=stage/'export'
        require(owner+'.class' in entries,'Selected canonical auth class missing')
        write_archive(source,{owner+'.class':entries[owner+'.class']})
        write_archive(support,{n:b for n,b in entries.items() if n!=owner+'.class'})
        methods_path=tool/bundle['selections'][kind]['methods'];methods=read(methods_path).decode().splitlines()
        before=stage/'before.class';before.write_bytes(entries[owner+'.class'])
        command_step(output,'selection-'+kind,[java,'-cp',str(parity)+os.pathsep+cp,'AuthNativeSelectionCheck',before,methods_path],required_output=['PASS native-selector-bodies='])
        profile=stage/'profile.json';write_json(profile,native_profile(source,target,[support,*parents],owner))
        command_step(output,'native-'+kind,[java,'-Xmx2g','-Dceleste.prototype.callLocalJni=true','-Dceleste.prototype.nativeOnly=true',
          '-Dceleste.prototype.methods='+str(methods_path),'-Dceleste.prototype.export='+str(export),
          '-cp',tool_cp(bundle,tool,'producer_classpath'),'dev.local.obfuscator.App','--config',profile])
        symbols=document(export/'native-symbols.json');selected_functions(symbols,methods)
        transformed=archive(target);require(transformed.get(symbols['resource'])==read(export/'native.dll',MAX_ENTRY),'Exported DLL differs from resource')
        require(symbols['loader']+'.class' in transformed,'Generated native loader absent')
        before=stage/'before.class';after=stage/'after.class';before.write_bytes(entries[owner+'.class']);after.write_bytes(transformed[owner+'.class'])
        command_step(output,'parity-'+kind,[java,'-cp',str(parity)+os.pathsep+cp,'NativeMethodParity',before,after,methods_path])
        candidates[kind]={'owner':owner,'methods':bundle['selections'][kind]['methods'],'resource':symbols['resource'],'loader':symbols['loader'],
          'input_sha256':sha(read(export/'native.dll')),'source_sha256':sha(read(export/'native.cpp')),
          'selection_sha256':sha(read(methods_path)),'producer_sha256':args.native_tool_sha256}
        parts.append((owner,transformed))
    write_archive(output/'native-unapproved.jar',merge_entries(entries,parts))
    require(sources()==before_sources,'Release source changed during preparation')
    require(sha(read(args.input))==report['input_sha256'],'Runtime changed during native preparation')
    require(sha(read(args.descriptor_module))==report['descriptor_sha256'],'Descriptor changed during native preparation')
    require(all(sha(read(path))==digest for path,digest in report['parent_dependencies'].items()),'Dependency changed during native preparation')
    require(sha(read(payload))==report['payload_merged_sha256'],'Java-protected input changed during native preparation')
    load_bundle(args.native_tool_bundle.resolve(strict=True),args.native_tool_sha256)
    sealed={'schema':'celeste-auth-native-checkpoint-1','status':'awaiting-independent-native-protection-review',
      'release_id':args.release_id,'source_inputs':before_sources,'native_tool_sha256':args.native_tool_sha256,
      'java_checkpoint_sha256':sha(read(output/'java-checkpoint/receipt.json')),'payload_sha256':sha(read(payload)),
      'runtime_path':str(args.input.resolve()),'runtime_sha256':sha(read(args.input)),
      'descriptor_path':str(args.descriptor_module.resolve()),'descriptor_sha256':sha(read(args.descriptor_module)),
      'java_home':str(args.java_home.resolve()),'candidates':candidates,
      'release_arguments':{k:getattr(args,k) for k in ('tool_sha256','release_id','client_version','base_url','server_key','offline')},
      'parents':report['parent_dependencies'],'files':inventory(output),'production_approved':False}
    if getattr(args, 'module_preparation', None) is not None:
        sealed['release_arguments']['module_preparation'] = str(Path(args.module_preparation).resolve(strict=True))
        sealed['release_arguments']['module_preparation_sha256'] = args.module_preparation_sha256
    write_json(output/'checkpoint.json',sealed)
    print(json.dumps({'checkpoint':str(output/'checkpoint.json'),'checkpoint_sha256':sha(read(output/'checkpoint.json')),'status':sealed['status']}))

def load_checkpoint(path,expected):
    require(java_release.HEX.fullmatch(expected or '') and sha(read(path))==expected,'Checkpoint pin mismatch')
    value=document(path,4*1024*1024);root=path.parent
    require(value.get('schema')=='celeste-auth-native-checkpoint-1' and value.get('status')=='awaiting-independent-native-protection-review','Wrong checkpoint state')
    verify_files(root,value['files'])
    require(sources()==value['source_inputs'],'Current Java/release sources differ from preparation')
    for path,digest in value['parents'].items():require(sha(read(path))==digest,'Parent library changed')
    for key in ('runtime','descriptor'):require(sha(read(value[key+'_path']))==value[key+'_sha256'],'Original '+key+' changed')
    require(sha(read(root/'java-checkpoint/payload-protected.jar'))==value['payload_sha256'],'Java checkpoint changed')
    return value

def run_verification(plan_path,expected,payload,output,java_home,parents,checkpoint_root,native_approval=None):
    require(java_release.HEX.fullmatch(expected or '') and sha(read(plan_path))==expected,'Verification plan pin mismatch')
    plan=document(plan_path)
    require(plan.get('schema')=='celeste-auth-native-verification-plan-1','Unsupported verification plan')
    verify_files(plan_path.parent,plan['files'])
    # Execute only an exact file snapshot, so an unlisted class in a caller's
    # classes directory cannot precede a reviewed fixture on its classpath.
    fixture_root=output/'native-verification';fixture_root.mkdir()
    for name,digest in plan['files'].items():
        data=read(plan_path.parent/safe(name));require(sha(data)==digest,'Fixture changed during snapshot')
        target=fixture_root/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
    checks=plan['checks'];require(isinstance(checks,list) and 2<=len(checks)<=64,'Bounded actual protocol/lifecycle checks required')
    names=[check.get('name') for check in checks]
    require(all(isinstance(n,str) and re.fullmatch(r'(protocol|lifecycle)(-[A-Za-z0-9_-]+)?',n) for n in names)
            and len(set(names))==len(names) and {n.split('-')[0] for n in names}=={'protocol','lifecycle'},'Both actual check groups and unique safe names required')
    entries=archive(payload,native_approval)
    bridge_entries={n:b for n,b in entries.items() if n==BRIDGE or n.startswith('dev/celestebeta/host/AuthNativeBridge$') and n.endswith('.class')}
    require(BRIDGE in bridge_entries,'Final native fixture lacks canonical custody bridge')
    bridge=output/'native-fixture-bridge.jar';write_archive(bridge,bridge_entries)
    custody=output/'native-fixture-custody';require(not custody.exists(),'Native fixture custody must be fresh');custody.mkdir()
    values={'payload':str(payload),'java':str(Path(java_home)/'bin/java.exe'),'plan_root':str(fixture_root),
            'output':str(output),'parent_classpath':os.pathsep.join(parents),'bridge_jar':str(bridge),'custody_root':str(custody)}
    values.update({kind+'_symbols':str(checkpoint_root/kind/'export/native-symbols.json') for kind in OWNERS})
    for check in checks:
        require(set(check)=={'name','argv','timeout_seconds','required_output'},'Unknown check configuration')
        name,args=check['name'],check['argv']
        require(isinstance(args,list) and args and args[0]=='{java}' and all(isinstance(arg,str) for arg in args),'Only pinned Java checks allowed')
        markers=check['required_output']
        require(type(check['timeout_seconds']) is int and 10<=check['timeout_seconds']<=120
                and isinstance(markers,list) and 1<=len(markers)<=8
                and all(isinstance(marker,str) and 1<=len(marker)<=256 for marker in markers),'Invalid fixture bound or result marker')
        command_step(output,'native-final-'+name,[arg.format_map(values) for arg in args],check['timeout_seconds'],check['required_output'])
    verify_files(plan_path.parent,plan['files'])
    verify_files(fixture_root,plan['files'])
    return {'plan_sha256':expected,'checks':{'protocol':'passed','lifecycle':'passed'},'passed_checks':names}

def resume(args):
    checkpoint=load_checkpoint(args.checkpoint.resolve(strict=True),args.checkpoint_sha256)
    root=args.checkpoint.resolve().parent;output=args.output.resolve();require(not output.exists(),'Resume output must be fresh')
    approval=approval_api.from_args(args,checkpoint['release_id']);require(approval is not None,'Reviewed approval index required')
    require(len(approval.inventory['resources'])==3 and all(i['artifact']=='payload' for i in approval.inventory['resources']),'Exactly three payload native resources required')
    bundle=load_bundle(root/'native-tool/native-tool-bundle.json',checkpoint['native_tool_sha256']);tool=root/'native-tool'
    original=archive(root/'java-checkpoint/payload-protected.jar');parts=[];output.mkdir(parents=True)
    java=Path(checkpoint['java_home'])/'bin/java.exe'
    for kind,record in checkpoint['candidates'].items():
        matches=[i for i in approval.inventory['resources'] if i['resource']==record['resource']]
        require(len(matches)==1,'Candidate lacks exact resource approval');item=matches[0]
        require(all(item[key]==record[key] for key in ('input_sha256','source_sha256','selection_sha256','producer_sha256')),'Approved DLL provenance differs from candidate')
        index=document(args.native_auth_evidence)
        approved=next(a for a in index['approvals'] if a['resource']==item['resource'])
        location=Path(approved['evidence']['output']['path'])
        if not location.is_absolute():location=args.native_auth_evidence.resolve().parent/location
        final=output/(kind+'-custody.jar')
        command_step(output,'custody-'+kind,[java,'-cp',tool_cp(bundle,tool,'reembed_classpath'),'ReembedWithCustody',
          root/kind/'native.jar',root/kind/'export/native-symbols.json',root/kind/'export/native.dll',location,item['sha256'],final,tool/bundle['template']])
        require(sha(read(Path(str(final)+'.custody.json')))==item['custody_sha256'],'Reproduced custody report differs from approval')
        entries=archive(final,approval);require(sha(entries[item['loader']])==item['loader_sha256'],'Reproduced loader differs')
        parts.append((record['owner'],entries))
    merged=merge_entries(original,parts);approval_api.validate_entries(merged,'payload',approval)
    final=output/'payload-native-protected.jar';write_archive(final,merged)
    checks=run_verification(args.verification_plan.resolve(strict=True),args.verification_plan_sha256,final,output,
                            checkpoint['java_home'],list(checkpoint['parents']),root,approval)
    load_checkpoint(args.checkpoint.resolve(strict=True),args.checkpoint_sha256)
    require(approval_api.from_args(args,checkpoint['release_id'])==approval,'Native approval changed')
    tail=argparse.Namespace(**checkpoint['release_arguments'])
    tail.input=Path(checkpoint['runtime_path']);tail.descriptor_module=Path(checkpoint['descriptor_path'])
    tail.java_home=Path(checkpoint['java_home']);tail.output=output/'verified-java-release'
    prior=document(root/'java-checkpoint/receipt.json');tail.obfuscator=root/'java-checkpoint/toolchain'/prior['producer']['entry']
    tail.parent_classpath=root/'java-checkpoint/parent-classpath.txt'
    for name in ('native_auth_inventory','native_auth_inventory_sha256','native_auth_evidence','native_auth_evidence_sha256'):setattr(tail,name,getattr(args,name))
    java_release.run(tail,payload_mode='resume-native',final_payload=final,final_payload_sha256=sha(read(final)),
                     native_checkpoint={'checkpoint_sha256':args.checkpoint_sha256,**checks})
    load_checkpoint(args.checkpoint.resolve(strict=True),args.checkpoint_sha256)
    require(approval_api.from_args(args,checkpoint['release_id'])==approval,'Native approval changed during tail')
    write_json(output/'result.json',{'schema':'celeste-auth-native-release-result-1','status':'protected-verified-candidate-not-published',
      'checkpoint_sha256':args.checkpoint_sha256,'payload_sha256':sha(read(final)),'native_approval':approval.summary(),'verification':checks,
      'java_receipt_sha256':sha(read(tail.output/'receipt.json')),'published':False})

def main():
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest='command',required=True)
    p=sub.add_parser('prepare-native-candidate')
    for name in ('input','obfuscator','parent-classpath','java-home','output','descriptor-module','native-tool-bundle'):p.add_argument('--'+name,type=Path,required=True)
    for name in ('tool-sha256','release-id','client-version','base-url','server-key','native-tool-sha256'):p.add_argument('--'+name,required=True)
    p.add_argument('--module-preparation',type=Path)
    p.add_argument('--module-preparation-sha256')
    p.add_argument('--offline',action='store_true')
    r=sub.add_parser('resume-with-approved-index')
    for name in ('checkpoint','output','verification-plan'):r.add_argument('--'+name,type=Path,required=True)
    for name in ('checkpoint-sha256','verification-plan-sha256'):r.add_argument('--'+name,required=True)
    approval_api.add_arguments(r)
    args=parser.parse_args()
    try:
        if args.command=='prepare-native-candidate':prepare(args)
        else:resume(args)
    except (ValueError,OSError,KeyError,zipfile.BadZipFile) as error:parser.exit(1,str(error)+'\n')
if __name__=='__main__':main()
