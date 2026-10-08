import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'prepare-diplay-update.py'
PREFIX = 'shared/src/main/java/com/shilapi/xcertplay/media/'

class UpdateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name) / "repo"
        self.root.mkdir()
        self.git('init', '-q')
        self.git('config', 'user.email', 'test@example.invalid')
        self.git('config', 'user.name', 'Test')
        self.file = self.root / (PREFIX + 'Sample.kt')
        self.file.parent.mkdir(parents=True)
        self.file.write_text('base\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'upstream base')
        self.base = self.git('rev-parse', 'HEAD').strip()
        self.file.write_text('upstream\n')
        (self.root/'outside.txt').write_text('dependency review\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'upstream update')
        self.target = self.git('rev-parse', 'HEAD').strip()
        # Deliberately unrelated fork history, like the actual VoyahPlay repository.
        self.git('checkout', '--orphan', 'voyah')
        self.git('rm', '-rf', '.')
        self.file.parent.mkdir(parents=True)
        self.file.write_text('base\n')
        (self.root/'.github').mkdir()
        (self.root/'docs').mkdir()
        (self.root/'docs/keep.md').write_text('Voyah\n')
        self.config = self.root/'.github/diplay-upstream.json'
        self.config.write_text(json.dumps({'baseline':self.base, 'paths':[PREFIX]}))
        self.git('add', '.')
        self.git('commit', '-qm', 'independent fork')

    def tearDown(self):
        self.tmp.cleanup()

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, stderr=subprocess.DEVNULL).decode()

    def run_update(self, *args):
        return subprocess.run(['python3', str(SCRIPT), '--target', self.target, *args],
                              cwd=self.root, env=dict(os.environ, RUNNER_TEMP=str(self.root.parent)),
                              capture_output=True, text=True)

    def test_unrelated_history_and_scope(self):
        result=self.run_update()
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(self.file.read_text(),'upstream\n')
        self.assertFalse((self.root/'outside.txt').exists())
        self.assertEqual(json.loads(self.config.read_text())['baseline'],self.target)
        self.assertIn('outside.txt',(self.root/'docs/DIPLAY-UPSTREAM-REVIEW.md').read_text())

    def test_conflict_preserves_all_local_source_and_baseline(self):
        self.file.write_text('Voyah custom\n')
        self.git('add','.'); self.git('commit','-qm','custom')
        result=self.run_update()
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(self.file.read_text(),'Voyah custom\n')
        self.assertEqual(json.loads(self.config.read_text())['baseline'],self.base)
        self.assertIn('**conflict**',result.stdout)
        self.assertEqual(self.git('ls-files','-u'),'')

    def test_dry_run_never_changes_fork(self):
        result=self.run_update('--dry-run')
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(self.git('status','--porcelain'),'')
        self.assertEqual(self.file.read_text(),'base\n')

    def test_dirty_checkout_rejected(self):
        self.file.write_text('work in progress\n')
        result=self.run_update()
        self.assertNotEqual(result.returncode,0)
        self.assertEqual(self.file.read_text(),'work in progress\n')

    def test_already_applied_change_is_not_reverted(self):
        self.file.write_text('upstream\n')
        self.git('add','.'); self.git('commit','-qm','already applied')
        result=self.run_update()
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(self.file.read_text(),'upstream\n')
        self.assertEqual(json.loads(self.config.read_text())['baseline'],self.target)

    def test_reviewed_resolution_preserves_customization(self):
        self.file.write_text('upstream + Voyah customization\n')
        self.git('add','.'); self.git('commit','-qm','manual resolution')
        config=json.loads(self.config.read_text())
        path=PREFIX+'Sample.kt'
        config['reviewed_resolutions']={path:{
            'baseline':self.base,
            'upstream_blob':self.git('rev-parse',self.target+':'+path).strip(),
            'merged_blob':self.git('rev-parse','HEAD:'+path).strip()}}
        self.config.write_text(json.dumps(config))
        self.git('add','.'); self.git('commit','-qm','reviewed hashes')
        result=self.run_update()
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertIn('**ready**',result.stdout)
        self.assertEqual(self.file.read_text(),'upstream + Voyah customization\n')
        self.assertEqual(json.loads(self.config.read_text())['baseline'],self.target)

    def test_wrong_resolution_hash_does_not_bypass_conflict(self):
        self.file.write_text('different customization\n')
        config=json.loads(self.config.read_text())
        path=PREFIX+'Sample.kt'
        config['reviewed_resolutions']={path:{
            'baseline':self.base,
            'upstream_blob':self.git('rev-parse',self.target+':'+path).strip(),
            'merged_blob':'0'*40}}
        self.config.write_text(json.dumps(config))
        self.git('add','.'); self.git('commit','-qm','mismatched review')
        result=self.run_update()
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertIn('**conflict**',result.stdout)
        self.assertEqual(self.file.read_text(),'different customization\n')
        self.assertEqual(json.loads(self.config.read_text())['baseline'],self.base)

if __name__ == '__main__':
    unittest.main()
