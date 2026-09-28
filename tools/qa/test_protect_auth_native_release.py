"""Focused sealed-checkpoint and preservation regressions; no VMP claims."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import protect_auth_native_release as subject

class PipelineChecks(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.original={subject.BRIDGE:b'canonical-bridge','StringDecoder.class':b'already-protected-strings',
            'assets/keep.txt':b'unchanged'}
        self.original.update({owner+'.class':b'Java-protected-'+kind.encode() for kind,owner in subject.OWNERS.items()})
        self.parts=[(owner,{owner+'.class':b'native-'+kind.encode(),'generated/'+kind+'.class':b'generated-'+kind.encode(),
                  'META-INF/'+kind+'.bin':b'mapped-'+kind.encode()}) for kind,owner in subject.OWNERS.items()]
    def tearDown(self):self.temp.cleanup()
    def test_all_unselected_bytes_and_canonical_bridge_remain_identical(self):
        merged=subject.merge_entries(self.original,self.parts)
        for name in (subject.BRIDGE,'StringDecoder.class','assets/keep.txt'):
            self.assertEqual(merged[name],self.original[name])
        for _,entries in self.parts:
            for name,value in entries.items():self.assertEqual(merged[name],value)
    def test_raw_selected_class_restoration_is_rejected(self):
        owner,entries=self.parts[0];entries[owner+'.class']=self.original[owner+'.class']
        with self.assertRaises(ValueError):subject.merge_entries(self.original,self.parts)
    def test_missing_one_native_class_is_rejected(self):
        with self.assertRaises(ValueError):subject.merge_entries(self.original,self.parts[:2])
    def test_unselected_string_decoder_replacement_is_rejected(self):
        self.parts[0][1]['StringDecoder.class']=b'raw-decoder'
        with self.assertRaises(ValueError):subject.merge_entries(self.original,self.parts)
    def test_bridge_change_is_rejected(self):
        self.parts[0][1][subject.BRIDGE]=b'changed'
        with self.assertRaises(ValueError):subject.merge_entries(self.original,self.parts)
    def test_missing_canonical_bridge_refused(self):
        del self.original[subject.BRIDGE]
        with self.assertRaises(ValueError):subject.merge_entries(self.original,self.parts)
    def test_generated_helper_collision_is_rejected(self):
        self.parts[1][1]['generated/auth.class']=b'collision'
        with self.assertRaises(ValueError):subject.merge_entries(self.original,self.parts)
    def test_archive_duplicate_and_traversal_rejected(self):
        for names in (['a','a'],['../escape']):
            jar=self.root/('x'+str(len(names))+'.jar')
            with zipfile.ZipFile(jar,'w') as archive:
                for name in names:archive.writestr(name,b'x')
            with self.assertRaises(ValueError):subject.archive(jar)
    def test_archive_bound_preserved(self):
        jar=self.root/'large.jar'
        with zipfile.ZipFile(jar,'w',compression=zipfile.ZIP_DEFLATED) as archive:archive.writestr('large.bin',b'x'*(subject.MAX_ENTRY+1))
        with self.assertRaises(ValueError):subject.archive(jar)
    def test_archive_content_round_trip(self):
        jar=self.root/'input.jar';subject.write_archive(jar,self.original)
        self.assertEqual(subject.archive(jar),self.original)
    def test_sealed_archive_mutation_is_rejected(self):
        (self.root/'payload.jar').write_bytes(b'protected')
        hashes=subject.inventory(self.root)
        subject.verify_files(self.root,hashes)
        (self.root/'payload.jar').write_bytes(b'changed')
        with self.assertRaises(ValueError):subject.verify_files(self.root,hashes)
    def test_seal_traversal_is_rejected(self):
        with self.assertRaises(ValueError):subject.verify_files(self.root,{'../elsewhere':'0'*64})
    def checkpoint(self):
        folder=self.root/'candidate';(folder/'java-checkpoint').mkdir(parents=True)
        payload=folder/'java-checkpoint/payload-protected.jar';payload.write_bytes(b'protected-exact')
        for name in ('runtime','descriptor','parent'):(self.root/name).write_bytes(name.encode())
        value={'schema':'celeste-auth-native-checkpoint-1','status':'awaiting-independent-native-protection-review',
          'files':subject.inventory(folder),'source_inputs':{'source':'known'},'payload_sha256':subject.sha(payload.read_bytes()),
          'parents':{str(self.root/'parent'):subject.sha(b'parent')}}
        for name in ('runtime','descriptor'):
            value[name+'_path']=str(self.root/name);value[name+'_sha256']=subject.sha(name.encode())
        path=folder/'checkpoint.json';path.write_text(json.dumps(value));return path,subject.sha(path.read_bytes())
    def test_sealed_checkpoint_accepts_exact_inputs(self):
        path,pin=self.checkpoint()
        with patch.object(subject,'sources',return_value={'source':'known'}):subject.load_checkpoint(path,pin)
    def test_checkpoint_rejects_changed_current_source(self):
        path,pin=self.checkpoint()
        with patch.object(subject,'sources',return_value={'source':'changed'}):
            with self.assertRaises(ValueError):subject.load_checkpoint(path,pin)
    def test_checkpoint_rejects_changed_external_parent(self):
        path,pin=self.checkpoint();(self.root/'parent').write_bytes(b'new')
        with patch.object(subject,'sources',return_value={'source':'known'}):
            with self.assertRaises(ValueError):subject.load_checkpoint(path,pin)
    def test_checkpoint_rejects_changed_index(self):
        path,pin=self.checkpoint();path.write_text(path.read_text()+' ')
        with self.assertRaises(ValueError):subject.load_checkpoint(path,pin)
    def test_native_profile_never_reruns_string_renaming_pass(self):
        profile=subject.native_profile('source','target',[],subject.OWNERS['auth'])
        self.assertTrue(profile['nativeTranspile'])
        for flag in ('encryptStrings','renameClasses','renamePrivateMembers','stripDebugInfo','doubleStringProtection','relocateAssets'):
            self.assertFalse(profile[flag])
    def test_partial_native_method_body_rejected(self):
        symbol={'owner':'owner','method':'x','descriptor':'()V','constructor_tail':False,'actual_mode':'NATIVE_DIRECT_CFG'}
        subject.selected_functions({'functions':[symbol]},['owner#x()V'])
        for field,value in [('constructor_tail',True),('actual_mode','JAVA_FALLBACK')]:
            bad=dict(symbol);bad[field]=value
            with self.assertRaises(ValueError):subject.selected_functions({'functions':[bad]},['owner#x()V'])
    def test_missing_or_duplicate_selected_methods_rejected(self):
        symbol={'owner':'owner','method':'x','descriptor':'()V','constructor_tail':False,'actual_mode':'NATIVE_DIRECT_CFG'}
        for values in ([],[symbol,symbol]):
            with self.assertRaises(ValueError):subject.selected_functions({'functions':values},['owner#x()V'])
    def test_native_flags_still_refused_by_standard_profile(self):
        root=self.root/'profiles';root.mkdir()
        profile={'schemaVersion':1,'encryptStrings':True,'doubleStringProtection':True,'compositeStringModeOnly':True,'nativeTranspile':True}
        (root/'04-test.json').write_text(json.dumps(profile))
        with patch.object(subject.java_release,'PROFILES',root):
            with self.assertRaises(ValueError):subject.java_release.checked_profile('04-test.json','a','b',[])

if __name__=='__main__':unittest.main()
