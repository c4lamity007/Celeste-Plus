"""Offline workflow guard tests. All fixtures stay in temporary directories."""
import argparse
from contextlib import redirect_stdout
import io
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch
import zlib

import module_qa as qa

def chunk(kind, payload):
    return (struct.pack('>I', len(payload)) + kind + payload +
            struct.pack('>I', zlib.crc32(kind + payload) & 0xFFFFFFFF))


HEADER = chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 6, 0, 0, 0))
IMAGE_DATA = zlib.compress(b'\x00\xff\xff\xff\xff')
END = chunk(b'IEND', b'')
PNG = qa.PNG_SIGNATURE + HEADER + chunk(b'IDAT', IMAGE_DATA) + END


class WorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.project = self.root / 'project'
        self.project.mkdir()
        (self.project / 'gradlew.bat').write_text('fixture', encoding='utf-8')
        (self.project / 'src').mkdir()
        (self.project / 'src/source.java').write_text('fixture', encoding='utf-8')
        self.outputs = self.root / 'runs'
        self.console = io.StringIO()
        self.redirect = redirect_stdout(self.console)
        self.redirect.__enter__()
        self.addCleanup(self.redirect.__exit__, None, None, None)

    def check(self, status='pass'):
        args = argparse.Namespace(project=self.project, output=self.outputs, module=['Fixture'],
                                  java_home=None, suite='build', timeout=2)
        with patch.object(qa, 'run_step', return_value={'name': 'build', 'status': status}):
            code = qa.check(args)
        self.run = next(self.outputs.iterdir())
        return code

    def attach(self, kind='game', stale=False):
        path = self.root / 'fixture.png'
        path.write_bytes(PNG)
        if stale:
            os.utime(path, (1, 1))
        return qa.attach(argparse.Namespace(run=self.run, image=path, kind=kind,
                                            observation='Unit-test fixture, not real game evidence'))

    def finish(self, status='pass', build='Fixture build', cases=None):
        return qa.finish(argparse.Namespace(run=self.run, runtime=status, loaded_build=build,
                                            case=['Fixture observation'] if cases is None else cases,
                                            note='Fixture result'))

    def test_build_success_does_not_claim_runtime(self):
        self.assertEqual(self.check(), 0)
        data = qa.load(self.run)
        self.assertEqual(data['status'], 'checks-passed')
        self.assertEqual(data['runtime']['status'], 'pending')

    def planned_check(self, suite, failure=None):
        # Exercise the actual workflow/parser without launching Gradle, Java or a game.
        args = qa.parser().parse_args(['check', '--project', str(self.project),
                                      '--output', str(self.outputs), '--module', 'Block Highlight',
                                      '--module', 'Anchor Aura', '--suite', suite,
                                      '--java-home', str(self.root / 'jdk 21'), '--timeout', '7'])

        def result(run, name, command, cwd, env, timeout):
            return {'name': name, 'status': 'fail' if name == failure else 'pass', 'log': name + '.log'}

        with patch.object(qa, 'run_step', side_effect=result) as steps:
            code = args.func(args)
        self.run = steps.call_args_list[0].args[0]
        return code, steps.call_args_list, qa.load(self.run)

    def test_crystal_suite_inherits_full_player_plan_once(self):
        player_code, player_calls, _ = self.planned_check('player-visuals')
        code, calls, data = self.planned_check('crystal-visuals')
        self.assertEqual((player_code, code), (0, 0))
        player_names = [call.args[1] for call in player_calls]
        names = [call.args[1] for call in calls]
        self.assertEqual(player_names, ['build', 'visuals', 'chams', 'prism', 'unified-volume', 'local-damage'])
        self.assertEqual(names, player_names + ['block-highlight', 'auras'])
        self.assertEqual(len(names), len(set(names)))
        self.assertEqual([check['name'] for check in data['checks']], names)
        self.assertEqual(data['modules'], ['Block Highlight', 'Anchor Aura'])
        self.assertEqual(data['status'], 'checks-passed')
        self.assertEqual(data['runtime']['status'], 'pending')
        self.assertEqual(data['evidence'], [])

    def test_shield_break_suite_runs_behavior_and_neighbor_checks_once(self):
        code, calls, data = self.planned_check('shield-break')
        self.assertEqual(code, 0)
        self.assertEqual([call.args[1] for call in calls],
                         ['build', 'shield-break', 'sword-aura-range', 'mace-selection'])
        self.assertEqual([Path(call.args[2][call.args[2].index('-File') + 1]).name for call in calls[1:]],
                         ['run-shield-break.ps1', 'run-sword-aura-range.ps1', 'check_mace_selection.ps1'])
        self.assertEqual(data['status'], 'checks-passed')
        self.assertEqual(data['runtime']['status'], 'pending')
        self.assertEqual(data['evidence'], [])
        for call in calls[1:]:
            command = call.args[2]
            self.assertEqual(command[command.index('-ProjectRoot') + 1], str(self.project.resolve()))
            self.assertEqual(command[command.index('-JdkHome') + 1], str((self.root / 'jdk 21').resolve()))

    def test_shield_break_suite_stops_at_each_failure(self):
        names = ['build', 'shield-break', 'sword-aura-range', 'mace-selection']
        for failed in names:
            with self.subTest(failed=failed):
                code, calls, data = self.planned_check('shield-break', failure=failed)
                self.assertEqual(code, 1)
                self.assertEqual([call.args[1] for call in calls], names[:names.index(failed) + 1])
                self.assertEqual(data['status'], 'fail')
                self.assertEqual(data['runtime']['status'], 'pending')

    def test_shield_break_suite_requires_explicit_jdk_before_starting(self):
        args = qa.parser().parse_args(['check', '--project', str(self.project), '--output', str(self.outputs),
                                      '--module', 'Shield Break', '--suite', 'shield-break'])
        with patch.object(qa, 'run_step') as steps:
            with self.assertRaisesRegex(ValueError, 'shield-break suite requires --java-home'):
                args.func(args)
            steps.assert_not_called()
        self.assertFalse(self.outputs.exists())

    def test_crystal_suite_passes_selected_paths_and_jdk_to_every_check(self):
        _, calls, _ = self.planned_check('crystal-visuals')
        scripts = ['run-visuals.ps1', 'run-chams-aurora.ps1', 'run-prism-material.ps1',
                   'run-unified-volume.ps1', 'check_local_damage.ps1', 'run-block-highlight.ps1', 'check_auras.ps1']
        self.assertEqual(calls[0].args[2], [str((self.project / 'gradlew.bat').resolve()), '--no-daemon', '--console=plain', ':build'])
        for call, script in zip(calls[1:], scripts):
            run, name, command, cwd, env, timeout = call.args
            self.assertEqual(Path(command[command.index('-File') + 1]).name, script)
            self.assertIn('-NoProfile', command)
            self.assertEqual(command[command.index('-ProjectRoot') + 1], str(self.project.resolve()))
            self.assertEqual(command[command.index('-OutputRoot') + 1], str(run / name))
            self.assertEqual(command[command.index('-JdkHome') + 1], str((self.root / 'jdk 21').resolve()))
            self.assertEqual(cwd, self.project.resolve())
            self.assertEqual(env['JAVA_HOME'], str((self.root / 'jdk 21').resolve()))
            self.assertEqual(timeout, 7)
        self.assertEqual(calls[1].args[2][calls[1].args[2].index('-Suite') + 1], 'All')

    def test_crystal_suite_stops_at_first_failed_check(self):
        names = ['build', 'visuals', 'chams', 'prism', 'unified-volume', 'local-damage', 'block-highlight', 'auras']
        for failed in names:
            with self.subTest(failed=failed):
                code, calls, data = self.planned_check('crystal-visuals', failure=failed)
                self.assertEqual(code, 1)
                self.assertEqual([call.args[1] for call in calls], names[:names.index(failed) + 1])
                self.assertEqual(data['status'], 'fail')
                self.assertEqual(data['runtime']['status'], 'pending')
                self.assertEqual(data['checks'][-1]['name'], failed)
                self.assertEqual(data['checks'][-1]['status'], 'fail')

    def test_extended_visual_suites_require_explicit_jdk_before_starting(self):
        for suite in ('player-visuals', 'crystal-visuals'):
            with self.subTest(suite=suite):
                args = qa.parser().parse_args(['check', '--project', str(self.project), '--output', str(self.outputs),
                                              '--module', 'Fixture', '--suite', suite])
                with patch.object(qa, 'run_step') as steps:
                    with self.assertRaisesRegex(ValueError, suite + ' suite requires --java-home'):
                        args.func(args)
                    steps.assert_not_called()
                self.assertFalse(self.outputs.exists())

    def test_older_suites_do_not_gain_crystal_checks(self):
        for suite, expected in [('build', ['build']), ('jump', ['build', 'jump']), ('visuals', ['build', 'visuals'])]:
            with self.subTest(suite=suite):
                code, calls, _ = self.planned_check(suite)
                self.assertEqual(code, 0)
                self.assertEqual([call.args[1] for call in calls], expected)
                if suite == 'jump':
                    command = calls[1].args[2]
                    self.assertEqual(command[command.index('-Suite') + 1], 'Animation')

    def test_crystal_checks_and_gpu_preview_still_cannot_claim_runtime_pass(self):
        self.planned_check('crystal-visuals')
        self.attach('gpu-preview')
        with self.assertRaisesRegex(ValueError, 'in-game screenshot'):
            self.finish()
        self.assertEqual(self.finish('blocked'), 2)
        self.assertEqual(qa.load(self.run)['status'], 'partial')

    def test_build_failure_survives_blocked_runtime(self):
        self.assertEqual(self.check('fail'), 1)
        self.assertEqual(self.finish('blocked'), 1)
        self.assertEqual(qa.load(self.run)['status'], 'fail')

    def test_gpu_is_not_runtime_pass(self):
        self.check()
        self.attach('gpu-preview')
        with self.assertRaises(ValueError):
            self.finish()
        self.assertEqual(self.finish('blocked'), 2)
        self.assertEqual(qa.load(self.run)['status'], 'partial')

    def test_stale_image_rejected(self):
        self.check()
        with self.assertRaises(ValueError):
            self.attach(stale=True)
        self.assertEqual(qa.load(self.run)['evidence'], [])

    def test_fresh_game_evidence_is_copied_exactly(self):
        self.check()
        self.attach()
        image = self.run / qa.load(self.run)['evidence'][0]['file']
        self.assertEqual(image.read_bytes(), PNG)
        self.assertEqual(self.finish(), 0)
        self.assertEqual(qa.load(self.run)['status'], 'pass')

    def test_game_pass_requires_build_and_observations(self):
        self.check()
        self.attach()
        with self.assertRaises(ValueError):
            self.finish(build=None)
        with self.assertRaises(ValueError):
            self.finish(cases=[])

    def test_edit_after_build_invalidates_runtime(self):
        self.check()
        self.attach()
        (self.project / 'src/source.java').write_text('changed', encoding='utf-8')
        with self.assertRaises(ValueError):
            self.finish()

    def test_ignored_build_output_does_not_invalidate(self):
        self.check()
        (self.project / 'build').mkdir()
        (self.project / 'build/result.log').write_text('generated', encoding='utf-8')
        self.assertEqual(self.finish('blocked'), 2)

    def test_evidence_limit(self):
        self.check()
        for _ in range(3):
            self.attach()
        with self.assertRaises(ValueError):
            self.attach()

    def test_malformed_png_rejected_without_evidence(self):
        self.check()
        malformed = {
            'header-only': qa.PNG_SIGNATURE + HEADER,
            'no-image-data': qa.PNG_SIGNATURE + HEADER + END,
            'no-end': PNG[:-12],
            'bad-crc': PNG[:-1] + bytes([PNG[-1] ^ 1]),
            'trailing-data': PNG + b'extra',
            'split-idat': (qa.PNG_SIGNATURE + HEADER + chunk(b'IDAT', IMAGE_DATA[:2]) +
                           chunk(b'tEXt', b'fixture\x00test') + chunk(b'IDAT', IMAGE_DATA[2:]) + END),
        }
        for name, image in malformed.items():
            with self.subTest(name=name):
                path = self.root / (name + '.png')
                path.write_bytes(image)
                with self.assertRaises(ValueError):
                    qa.attach(argparse.Namespace(run=self.run, image=path, kind='gpu-preview',
                                                 observation='Deliberately malformed unit-test fixture'))
                self.assertEqual(qa.load(self.run)['evidence'], [])

    def test_evidence_read_has_size_limit(self):
        self.check()
        with patch.object(qa, 'MAX_EVIDENCE_BYTES', len(PNG) - 1):
            with self.assertRaisesRegex(ValueError, 'size limit'):
                self.attach()
        self.assertEqual(qa.load(self.run)['evidence'], [])

    def test_missing_evidence_invalidates_finish_and_status(self):
        self.check()
        self.attach('gpu-preview')
        evidence = self.run / qa.load(self.run)['evidence'][0]['file']
        evidence.unlink()
        with self.assertRaisesRegex(ValueError, 'missing'):
            self.finish('blocked')
        with self.assertRaisesRegex(ValueError, 'missing'):
            qa.status(argparse.Namespace(run=self.run))
        self.assertEqual(qa.load(self.run)['runtime']['status'], 'pending')

    def test_changed_evidence_invalidates_finish_and_status(self):
        self.check()
        self.attach()
        self.assertEqual(self.finish(), 0)
        evidence = self.run / qa.load(self.run)['evidence'][0]['file']
        # A different, structurally valid image must fail the saved hash check too.
        evidence.write_bytes(qa.PNG_SIGNATURE + HEADER +
                             chunk(b'IDAT', zlib.compress(b'\x00\x00\x00\x00\xff')) + END)
        with self.assertRaisesRegex(ValueError, 'changed'):
            self.finish()
        with self.assertRaisesRegex(ValueError, 'changed'):
            qa.status(argparse.Namespace(run=self.run))

    def test_valid_evidence_status_remains_compact(self):
        self.check()
        self.attach('gpu-preview')
        self.assertEqual(self.finish('blocked'), 2)
        args = qa.parser().parse_args(['status', '--run', str(self.run)])
        self.assertIsNone(args.func(args))
        self.assertIn('"runtime":"blocked"', self.console.getvalue().splitlines()[-1])
        self.assertIn('"kind":"gpu-preview"', self.console.getvalue().splitlines()[-1])

    def test_timeout_cleanup_failure_is_structured(self):
        self.check()
        process = Mock(pid=12345)
        process.wait.side_effect = [subprocess.TimeoutExpired('fixture', 2),
                                    subprocess.TimeoutExpired('fixture', 15)]
        with patch.object(qa.subprocess, 'Popen', return_value=process), \
             patch.object(qa.subprocess, 'run', return_value=Mock(returncode=1)):
            result = qa.run_step(self.run, 'timeout', ['not-executed'], self.project, {}, 2)
        self.assertEqual(result['status'], 'fail')
        self.assertEqual(result['cleanup'], 'unconfirmed')
        self.assertEqual(result['pid'], process.pid)
        self.assertIn('could not be confirmed', result['reason'])

    def test_timeout_cleanup_launch_error_is_structured(self):
        self.check()
        process = Mock(pid=12345)
        process.wait.side_effect = subprocess.TimeoutExpired('fixture', 2)
        process.kill.side_effect = OSError('fixture cleanup blocked')
        with patch.object(qa.subprocess, 'Popen', return_value=process), \
             patch.object(qa.subprocess, 'run', side_effect=OSError('fixture cleanup blocked')):
            result = qa.run_step(self.run, 'timeout-launch', ['not-executed'], self.project, {}, 2)
        self.assertEqual(result['status'], 'fail')
        self.assertEqual(result['cleanup'], 'unconfirmed')
        self.assertEqual(result['pid'], process.pid)

    def test_timeout_successful_cleanup_still_fails_check(self):
        self.check()
        process = Mock(pid=12345)
        process.wait.side_effect = [subprocess.TimeoutExpired('fixture', 2), 0]
        with patch.object(qa.subprocess, 'Popen', return_value=process), \
             patch.object(qa.subprocess, 'run', return_value=Mock(returncode=0)):
            result = qa.run_step(self.run, 'timeout-stopped', ['not-executed'], self.project, {}, 2)
        self.assertEqual(result['status'], 'fail')
        self.assertEqual(result['reason'], 'timeout')
        self.assertNotIn('cleanup', result)


if __name__ == '__main__':
    unittest.main()
