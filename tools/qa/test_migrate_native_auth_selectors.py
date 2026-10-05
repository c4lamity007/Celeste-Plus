"""Receipt continuity fixtures; Java compilation is stubbed, no native protection claim."""
import argparse
import contextlib
import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import migrate_native_auth_selectors as subject


class SelectorMigrationChecks(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.project=self.root/'project';self.bundle=self.root/'prior'
        self.bundle.mkdir();(self.project/'tools/qa').mkdir(parents=True)
        (self.project/'tools/qa/AuthNativeSelectionCheck.java').write_text('fixture semantic checker')
        self.selectors=self.project/'tools/packaging/native-auth-producer/selectors';self.selectors.mkdir(parents=True)
        self.policy=self.project/'beta-lab/obfuscation/native-auth-producer.json';self.policy.parent.mkdir(parents=True)
        self.previous_receipt=b'{"historical":"never rewrite"}\n'
        files={'selector-update-20261002.json':self.previous_receipt,'provenance.json':b'{}','tool.jar':b'fixture producer','template.class':b'fixture template'}
        selections={}
        for kind,owner in subject.release.OWNERS.items():
            name=kind+'.methods';files[name]=(owner+'#old()V\n').encode();selections[kind]={'owner':owner,'methods':name}
            (self.selectors/name).write_text(owner+'#current()V\n')
        for name,value in files.items():(self.bundle/name).write_bytes(value)
        doc={'schema':'celeste-auth-native-tool-bundle-1','files':{n:subject.release.sha(v) for n,v in files.items()},
             'selections':selections,'producer_classpath':['tool.jar'],'reembed_classpath':['tool.jar'],'template':'template.class','provenance':'provenance.json'}
        subject.release.write_json(self.bundle/'native-tool-bundle.json',doc)
        self.pin=subject.release.sha((self.bundle/'native-tool-bundle.json').read_bytes())
        self.historical={'selected_full_bodies':16,'actual_protocol_checks':956}
        subject.release.write_json(self.policy,{'native_tool_bundle_sha256':self.pin,'previous_known_checks':self.historical,
             'method_selections':{k:{'path':v['methods'],'sha256':doc['files'][v['methods']]} for k,v in selections.items()}})

    def run_migration(self,index,previous,pin):
        runtime=self.root/f'runtime-{index}.jar'
        subject.release.write_archive(runtime,{owner+'.class':f'fixture class {index} {kind}'.encode() for kind,owner in subject.release.OWNERS.items()})
        args=argparse.Namespace(previous_bundle=previous/'native-tool-bundle.json',previous_sha256=pin,
            runtime=runtime,output=self.root/f'migration-{index}',java_home=self.root/'fixture-java',named_runtime=False)
        with patch.object(subject,'ROOT',self.project),patch.object(subject.release,'command_step'),contextlib.redirect_stdout(io.StringIO()):
            subject.migrate(args)
        return args.output

    def test_repeated_migration_preserves_all_prior_receipts_and_historical_checks(self):
        first=self.run_migration(1,self.bundle,self.pin)
        first_doc=subject.release.document(first/'bundle/native-tool-bundle.json')
        first_pin=subject.release.sha((first/'bundle/native-tool-bundle.json').read_bytes())
        candidate=subject.release.document(first/'producer-policy-candidate.json')
        self.assertEqual(candidate['previous_known_checks'],self.historical)
        self.assertFalse(candidate['selector_preflight']['production_approved'])
        subject.release.write_json(self.policy,candidate)
        second=self.run_migration(2,first/'bundle',first_pin)
        second_doc=subject.release.document(second/'bundle/native-tool-bundle.json')
        receipts={n:h for n,h in first_doc['files'].items() if n.startswith('selector-update-')}
        self.assertEqual(len(receipts),2)
        for name,digest in receipts.items():
            self.assertEqual(second_doc['files'][name],digest)
            self.assertEqual(subject.release.sha((second/'bundle'/name).read_bytes()),digest)
        self.assertEqual((second/'bundle/selector-update-20261002.json').read_bytes(),self.previous_receipt)
        added=set(second_doc['files'])-set(first_doc['files']);self.assertEqual(len(added),1)
        latest=subject.release.document(second/'bundle'/added.pop())
        self.assertEqual(latest['retained_selector_receipts'],receipts)
        self.assertFalse(latest['producer_implementation_changed'])
        self.assertEqual(subject.release.document(second/'producer-policy-candidate.json')['previous_known_checks'],self.historical)

    def test_same_receipt_name_collision_refuses_overwrite(self):
        target=self.root/'collision';target.mkdir();updated={'files':{}}
        receipt={'runtime_sha256':'a'*64};name=subject.append_selector_receipt(target,updated,receipt)
        original=(target/name).read_bytes()
        with self.assertRaisesRegex(ValueError,'receipt name collision'):
            subject.append_selector_receipt(target,updated,receipt)
        self.assertEqual((target/name).read_bytes(),original)
        with self.assertRaisesRegex(ValueError,'receipt name collision'):
            subject.append_selector_receipt(target,{'files':{}},receipt)


if __name__=='__main__':unittest.main()
