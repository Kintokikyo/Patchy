"""Local Testy regression tests. No editors or desktop automation are launched."""
import copy
import hashlib
import http.client
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'testy'))
import rerun
import report
import testy
from drivers import photoshop


def cell(score):
    return dict(state='done', opens='ok', renderMetrics=dict(accuracy=score, badFraction=1-score,
        perceptual=dict(accuracy=score, badFraction=1-score)),
        native=dict(nativeScore=score, nativeKept=int(score*10), nativeTotal=10),
        artifacts=dict(render='files/image/patchy/render.png'))


class RerunTests(unittest.TestCase):
    def setUp(self):
        scratch = ROOT / 'build/test-output'
        scratch.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='testy-rerun-', dir=scratch)
        self.addCleanup(self.temp.cleanup)
        self.runs = Path(self.temp.name)
        self.parent = self.runs / '20261002-000001'
        self.child = self.runs / '20261002-000002'
        self.parent.mkdir()
        self.child.mkdir()
        source = self.runs / 'image 日本%20.psd'
        source.write_bytes(b'task-owned synthetic source')
        self.row = dict(source=str(source), name=source.name, sha1=hashlib.sha1(source.read_bytes()).hexdigest(),
            groundTruth=dict(state='done'), cells=dict(patchy=cell(.5), photoshop=cell(1)),
            scan=dict(flagged=True, reasons=['old reason']))
        other = copy.deepcopy(self.row)
        other['source'] += '.other.psd'
        other['name'] = 'other.psd'
        self.base = dict(state='done', run=dict(name=self.parent.name, startedAt='2026-10-02T00:00:01', patchyGit='old', patchyVersion='old',
            editorOrder=['patchy', 'photoshop'], compare='perceptual', scan=dict(thresholdPct=10)),
            editors=dict(patchy=dict(displayName='Patchy', version='old'),
                         photoshop=dict(displayName='Photoshop', version='27')),
            files=[self.row, other])
        rerun.write_json(self.parent / 'status.json', self.base)
        rerun.write_json(self.parent / 'results.json', self.base)
        rerun.write_text(self.runs / 'history.jsonl', json.dumps(testy.Runner.summarize_status(self.base)) + '\n')
        self.job = rerun.prepare(self.runs, self.parent.name, self.row['source'], ['patchy'])
        fresh_row = copy.deepcopy(self.row)
        fresh_row.pop('scan')
        fresh_row['cells'] = dict(patchy=cell(1))
        self.fresh = dict(state='done', run=dict(name=self.child.name, sourcesUntouched=True,
            patchyGit='new', finishedAt='2026-10-02T12:00:00', editorOrder=['patchy']),
            editors=dict(patchy=dict(displayName='Patchy', version='new')), files=[fresh_row])
        artifact = self.child / 'files/image/patchy/render.png'
        artifact.parent.mkdir(parents=True)
        artifact.write_bytes(b'new render')
        rerun.write_json(self.child / 'results.json', self.fresh)

    def apply(self):
        return rerun.apply(self.runs, self.child, self.job, summarize=testy.Runner.summarize_status,
                           scan_reasons=testy.Runner.scan_reasons_for_status)

    def test_updates_selected_cells_totals_flags_and_retains_previous_artifacts(self):
        updated = self.apply()
        self.assertEqual(updated['files'][1], self.base['files'][1])
        self.assertEqual(updated['files'][0]['cells']['photoshop'], self.row['cells']['photoshop'])
        self.assertEqual(updated['files'][0]['cells']['patchy']['renderMetrics']['accuracy'], 1)
        self.assertFalse(updated['files'][0]['scan']['flagged'])
        self.assertTrue(updated['files'][0]['scan']['artifactsKept'])
        snapshot = json.loads((self.parent / updated['files'][0]['reruns'][0]['previous']).read_text())
        self.assertEqual(snapshot, self.base)
        image = self.parent / updated['files'][0]['cells']['patchy']['artifacts']['render']
        self.assertEqual(image.read_bytes(), b'new render')
        self.assertEqual(json.loads((self.parent/'results.json').read_text()), updated)
        history = [json.loads(line) for line in (self.runs/'history.jsonl').read_text().splitlines()]
        self.assertEqual(len(history), 1)
        self.assertEqual(history[0]['editors']['patchy']['render'], .75)
        self.assertNotIn(self.row['source']+'\n', (self.parent/'flagged.txt').read_text())
        self.assertEqual(updated['run']['patchyGit'], 'old')
        self.assertEqual(updated['run']['reruns'][0]['patchyGit'], 'new')

    def test_all_editors_and_repeated_reruns(self):
        self.job['editors'] = ['patchy', 'photoshop']
        self.fresh['files'][0]['cells']['photoshop'] = cell(.8)
        self.fresh['editors']['photoshop'] = dict(displayName='Photoshop', version='28')
        rerun.write_json(self.child/'results.json', self.fresh)
        self.apply()
        self.child = self.runs / '20261002-000003'
        self.child.mkdir()
        self.fresh['run']['name'] = self.child.name
        # No images needed to validate the second revision's result update.
        self.fresh['files'][0]['cells']['patchy']['artifacts'] = {}
        self.fresh['files'][0]['cells']['photoshop']['artifacts'] = {}
        rerun.write_json(self.child/'results.json', self.fresh)
        self.job = rerun.prepare(self.runs, self.parent.name, self.row['source'], ['patchy', 'photoshop'])
        updated = self.apply()
        self.assertEqual(len(updated['files'][0]['reruns']), 2)
        self.assertEqual(updated['files'][0]['cells']['photoshop']['renderMetrics']['accuracy'], .8)

    def test_failed_or_incomplete_child_does_not_change_batch(self):
        original = (self.parent/'status.json').read_bytes()
        cases = [('state', 'running'), ('source-change', None), ('truth-failed', None), ('editor-failed', None)]
        for case, value in cases:
            bad = copy.deepcopy(self.fresh)
            if case == 'state': bad['state'] = value
            elif case == 'source-change': bad['files'][0]['sha1'] = 'different'
            elif case == 'truth-failed': bad['files'][0]['groundTruth']['state'] = 'failed'
            else: bad['files'][0]['cells']['patchy']['state'] = 'failed'
            rerun.write_json(self.child/'results.json', bad)
            with self.subTest(case=case), self.assertRaises(ValueError): self.apply()
            self.assertEqual((self.parent/'status.json').read_bytes(), original)
            self.assertEqual((self.parent/'results.json').read_bytes(), original)

    def test_rejects_changed_source_stale_row_and_traversal(self):
        with self.assertRaises(ValueError):
            rerun.prepare(self.runs, '../outside', self.row['source'], ['patchy'])
        with self.assertRaises(ValueError):
            rerun.prepare(self.runs, self.parent.name, self.row['source'], ['unknown'])
        Path(self.row['source']).write_bytes(b'changed')
        with self.assertRaises(ValueError):
            rerun.prepare(self.runs, self.parent.name, self.row['source'], ['patchy'])
        changed = copy.deepcopy(self.base)
        changed['files'][0]['cells']['patchy']['state'] = 'changed'
        rerun.write_json(self.parent/'status.json', changed)
        with self.assertRaises(ValueError): self.apply()

    def test_failed_atomic_replace_keeps_old_file(self):
        path = self.parent/'status.json'
        original = path.read_bytes()
        with mock.patch.object(rerun.os, 'replace', side_effect=PermissionError('busy')):
            with self.assertRaises(PermissionError): rerun.write_json(path, {'oops': True})
        self.assertEqual(path.read_bytes(), original)
        self.assertFalse(list(self.parent.glob('*.tmp')))

    def test_publish_failure_restores_results_and_history(self):
        paths = [self.parent/'status.json', self.parent/'results.json', self.runs/'history.jsonl']
        originals = {p: p.read_bytes() for p in paths}
        write = rerun.write_text
        def fail_status(path, value):
            if path == self.parent/'status.json':
                raise PermissionError('status busy')
            write(path, value)
        with mock.patch.object(rerun, 'write_text', side_effect=fail_status):
            with self.assertRaises(PermissionError): self.apply()
        self.assertEqual({p:p.read_bytes() for p in paths}, originals)

    def test_font_inventory_changes_cache_key_between_runs(self):
        first = photoshop.PhotoshopDriver()
        first._app = mock.Mock()
        first._app.DoJavaScript.return_value = 'Installed'
        before = first.font_cache_key()
        self.assertEqual(first.font_cache_key(), before)
        first._app.DoJavaScript.assert_called_once()
        second = photoshop.PhotoshopDriver()
        second._app = mock.Mock()
        second._app.DoJavaScript.return_value = 'Installed\nNewFace'
        self.assertNotEqual(second.font_cache_key(), before)

    def test_patchy_cache_key_follows_the_exe_not_the_commit(self):
        exe = self.runs / 'patchy.exe'
        exe.write_bytes(b'build one')
        first = testy.patchy_build_key(exe, 'abc123')
        self.assertTrue(first.startswith('exe-'))
        self.assertEqual(testy.patchy_build_key(exe, 'a-different-commit'), first)
        exe.write_bytes(b'build two!')
        self.assertNotEqual(testy.patchy_build_key(exe, 'abc123'), first)
        self.assertEqual(testy.patchy_build_key(None, 'abc123'), 'abc123')
        self.assertEqual(testy.patchy_build_key(self.runs / 'missing.exe', 'abc123'), 'abc123')

    def test_endpoint_spawns_fresh_row_rerun_and_serves_new_controls_for_old_report(self):
        (self.parent/'report.html').write_text('old report')
        handler = lambda *args, **kwargs: testy.TestyRequestHandler(*args, directory=str(self.runs.parent), **kwargs)
        server = testy._ExclusiveHTTPServer(('127.0.0.1', 0), handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        with mock.patch.object(testy.config, 'RUNS_DIR', self.runs), \
             mock.patch.object(testy, '_run_in_progress', return_value=False), \
             mock.patch.object(testy.TestyRequestHandler, '_spawn_child') as spawn:
            conn = http.client.HTTPConnection('127.0.0.1', server.server_port)
            self.addCleanup(conn.close)
            conn.request('POST', '/testy-rerun-file', json.dumps(dict(run=self.parent.name, source=self.row['source'], scope='patchy')),
                         {'Content-Type': 'application/json'})
            response = conn.getresponse()
            self.assertEqual(response.status, 200, response.read())
            command = spawn.call_args.args[0]
            self.assertIn('--fresh', command)
            self.assertIn('--apply-to-run', command)
            self.assertEqual(command[command.index('--editors')+1], 'patchy')
            self.assertNotIn('--scan', command)
            conn.request('GET', '/' + self.runs.name + '/' + self.parent.name + '/report.html')
            response = conn.getresponse()
            self.assertEqual(response.status, 200)
            self.assertIn(b'rerunRowControls(f, fi)', response.read())
            self.assertEqual((self.parent/'report.html').read_text(), 'old report')
        with mock.patch.object(testy, '_run_in_progress', return_value=True), \
             mock.patch.object(testy.TestyRequestHandler, '_spawn_child') as spawn:
            conn.request('POST', '/testy-rerun-file', '{}', {'Content-Type': 'application/json'})
            response = conn.getresponse()
            self.assertEqual(response.status, 409)
            response.read()
            spawn.assert_not_called()

    def test_colliding_stems_get_distinct_artifact_directories(self):
        corpus = [Path('a/x.psd'), Path('a/x.psb'), Path('b/X.psd'), Path('b/x.psb'), Path('y.psd')]
        names = testy.unique_artifact_dirs(corpus)
        self.assertEqual(names, [None, 'x~psb', 'X~psd', 'x~2', None])
        entries = [dict(name=p.name, **({'dir': n} if n else {})) for p, n in zip(corpus, names)]
        resolved = [testy.artifact_dir_name(e) for e in entries]
        self.assertEqual(resolved, ['x', 'x~psb', 'X~psd', 'x~2', 'y'])
        self.assertEqual(len({r.lower() for r in resolved}), len(resolved))

    def test_file_traits_read_depth_mode_and_artboards(self):
        header = b'8BPS' + (1).to_bytes(2, 'big') + bytes(6) + (3).to_bytes(2, 'big') + bytes(8)
        plain = self.runs / 'plain.psd'
        plain.write_bytes(header + (8).to_bytes(2, 'big') + (3).to_bytes(2, 'big') + bytes(32))
        deep = self.runs / 'deep.psb'
        deep.write_bytes(header + (32).to_bytes(2, 'big') + (1).to_bytes(2, 'big') + b'....8B64artb....')
        self.assertEqual(testy.file_traits(plain), {'depth': 8, 'mode': 3})
        self.assertEqual(testy.file_traits(deep), {'depth': 32, 'mode': 1, 'artboards': True})
        self.assertIsNone(testy.file_traits(self.runs / 'missing.psd'))
        (self.runs / 'not.psd').write_bytes(b'not a psd at all, but long enough')
        self.assertIsNone(testy.file_traits(self.runs / 'not.psd'))

    def test_psdtools_column_is_opt_in_and_names_missing_packages(self):
        from drivers import psdtools
        self.assertNotIn('psdtools', testy.DEFAULT_EDITORS)
        self.assertIn('psdtools', testy.OPT_IN_EDITORS)
        self.assertIn('psdtools', testy.KNOWN_CELL_DIRS)
        with mock.patch.object(psdtools, 'version', return_value='1.17.0'), \
                mock.patch.object(psdtools, 'missing_composite_modules', return_value=['scipy']):
            info = testy.config.discover_editors('hash')['psdtools']
        self.assertFalse(info.available)
        self.assertIn('psd-tools[composite]', info.notes[0])
        self.assertIn('scipy', info.notes[0])
        with mock.patch.object(psdtools, 'version', return_value='1.17.0'), \
                mock.patch.object(psdtools, 'missing_composite_modules', return_value=[]):
            info = testy.config.discover_editors('hash')['psdtools']
        self.assertTrue(info.available)
        self.assertEqual(info.version, '1.17.0')
        failed = mock.Mock(returncode=1, stdout='', stderr='Traceback\nValueError: bad file\n')
        with mock.patch.object(psdtools.subprocess, 'run', return_value=failed):
            result = psdtools.export(self.runs / 'in.psd', self.runs / 'missing.png')
        self.assertEqual((result['ok'], result['fileRejected'], result['stderr'], result['note']),
                         (False, True, 'ValueError: bad file', ''))
        rendered = self.runs / 'out.png'
        rendered.write_bytes(b'png')
        noted = mock.Mock(returncode=0, stderr='', stdout='x\n' + psdtools.NOTE_MARKER + 'fell back\n')
        with mock.patch.object(psdtools.subprocess, 'run', return_value=noted):
            result = psdtools.export(self.runs / 'in.psd', rendered)
        self.assertEqual((result['ok'], result['note']), (True, 'fell back'))

    def test_failed_build_is_not_success_even_with_compile_output(self):
        with mock.patch.object(testy.config, 'REPO_ROOT', self.runs), \
             mock.patch.object(testy.config, 'BUILD_COMMAND', 'cmake --build --preset release'), \
             mock.patch.object(testy.subprocess, 'run', return_value=mock.Mock(returncode=-1,
                 stdout='Building CXX object foo', stderr='link failed')) as process:
            self.assertFalse(testy.refresh_patchy_build())
            self.assertIn('run-throttled.bat', process.call_args.args[0][2])
            self.assertEqual(process.call_args.kwargs['env']['CMAKE_BUILD_PARALLEL_LEVEL'], '20')

    def test_missing_fonts_remove_cached_text_score_without_altering_other_metrics(self):
        cached = cell(.8)
        cached.update(textRender={'accuracy': .1}, mutateError='old failure')
        cached['artifacts'].update(mutated='old.png', mutatedThumb='thumb.png')
        testy.Runner._skip_unavailable_text_comparison(cached, {'mutateSkipped': 'Required fonts unavailable: Example'})
        self.assertNotIn('textRender', cached)
        self.assertNotIn('mutateError', cached)
        self.assertNotIn('mutated', cached['artifacts'])
        self.assertEqual(cached['renderMetrics']['accuracy'], .8)

    def test_report_javascript_and_photoshop_font_preflight(self):
        script = report._PAGE.split('<script>', 1)[1].split('</script>', 1)[0]
        js_file = self.runs/'report.js'
        js_file.write_text(script, encoding='utf-8')
        subprocess.run(['node', '--check', str(js_file)], check=True, capture_output=True)
        preflight = photoshop._PROBE_JSX.split('  function textFontProblems', 1)[1].split('  // Append the suffix', 1)[0]
        preflight = 'function textFontProblems' + preflight
        controls = script.split('function rerunRowControls', 1)[1].split('function render()', 1)[0]
        controls = 'function rerunRowControls' + controls
        mutation = photoshop._PROBE_JSX.split('    var mutateCount', 1)[1].split('    var result =', 1)[0]
        mutation = 'var mutateCount' + mutation
        test_js = """
const assert = require('node:assert/strict');
const stringIDToTypeID = x => x;
const app = { fonts: {getByName(face) { if (face !== 'Installed') throw Error('missing'); }} };
function desc(value) {return {hasKey:k=>k in value, getString:k=>value[k], getBoolean:k=>value[k], getObjectValue:k=>desc(value[k]), getList:k=>({count:value[k].length,getObjectValue:i=>desc(value[k][i])})};}
const styles = [{fontPostScriptName:'Installed'}, {fontPostScriptName:'MissingLaterRange'}];
const layerDescriptor = () => desc({textKey:{textStyleRange:styles.map(textStyle=>({textStyle}))}});
const layer = {id:1,name:'Mixed styles',kind:'LayerKind.TEXT',textItem:{font:'Installed'}};
""" + preflight + """
let missing=[]; textFontProblems([layer],missing); assert.deepEqual(missing,['MissingLaterRange']);
styles.pop(); missing=[]; textFontProblems([layer],missing); assert.deepEqual(missing,[]);
styles[0].fontAvailable=false; missing=[]; textFontProblems([layer],missing); assert.deepEqual(missing,['Installed']);
missing=[]; textFontProblems([{typename:'LayerSet',layers:[{...layer,allLocked:true}]}],missing); assert.deepEqual(missing,[]);
let RUN_ID='batch', runState={running:false}, S={state:'done',run:{editorOrder:['patchy','photoshop']}}, rowRerunState=null,rowRerunPending=null,rowRerunError='';
const rowRerunScopes={}; const esc=s=>String(s).replaceAll('<','&lt;').replaceAll('"','&quot;');
""" + controls + """
let markup=rerunRowControls({source:'one',name:'Image <one>'},0); assert(markup.includes('Rerun</button>')); assert(markup.includes('value="patchy" selected')); assert(!markup.includes(' disabled'));
runState.running=true; assert(rerunRowControls({source:'one',name:'Image'},0).includes(' disabled'));
rowRerunState={state:'running',source:'one'}; assert(rerunRowControls({source:'one',name:'Image'},0).includes('Rerunning...'));
runState=null; assert.equal(rerunRowControls({source:'one',name:'Image'},0),'');
"""
        test_js += """
let edits=0, renders=0, failMutation=false;
const MUTATE_SUFFIX='~TESTY~', MUTATED_PNG='output.png', opened={layers:[layer]};
const DialogModes={NO:0}; app.displayDialogs=2;
const q=JSON.stringify;
function mutateText(layers,suffix,counter) {edits++; counter.n++; if(failMutation)counter.errors++;}
function renderTo() {renders++;return 'ok';}
function runMutation() {
""" + mutation + """
return {mutateSkipped,missingFonts,mutatedStatus,mutateErrors};
}
let verdict=runMutation(); assert(verdict.mutateSkipped.includes('Installed')); assert.equal(edits,0); assert.equal(renders,0); assert.equal(app.displayDialogs,2);
styles[0].fontAvailable=true; verdict=runMutation(); assert.equal(verdict.mutateSkipped,null); assert.equal(edits,1); assert.equal(renders,1); assert.equal(app.displayDialogs,2);
failMutation=true; verdict=runMutation(); assert.equal(edits,2); assert.equal(renders,1); assert(verdict.mutateSkipped.includes('could not edit')); assert.equal(app.displayDialogs,2);
"""
        rollup = script.split('const TOP_GROUP', 1)[1].split('let groupFilter', 1)[0]
        test_js += 'const TOP_GROUP' + rollup + r"""
const groupFiles = [
  {source:'D:\\c\\psd_files\\a.psd', cells:{patchy:{state:'done',opens:'ok',bad:0.02,native:{nativeScore:1}}}},
  {source:'D:\\c\\psd_files\\fx\\b.psd', cells:{patchy:{state:'done',opens:'ok',bad:0.5,resaveRejected:true,native:{nativeScore:0.5}}}},
  {source:'D:/c/psd_files/fx/c.psb', cells:{patchy:{state:'failed',opens:'fail'}}},
  {source:'D:\\c\\psd_files\\fx\\deep\\d.psd', cells:{patchy:{state:'pending'}}},
];
const groupNames = fileGroups(groupFiles);
assert.deepEqual(groupNames, [TOP_GROUP,'fx','fx','fx']);
assert.deepEqual(fileGroups([{source:'D:\\c\\one.psd'},{source:'D:\\c\\two.psd'}]), [TOP_GROUP,TOP_GROUP]);
const rolled = groupRollup(groupFiles, groupNames, ['patchy'], c => c.bad == null ? null : c.bad, 0.10);
assert.deepEqual(rolled[TOP_GROUP].editors.patchy, {total:1,opened:1,matched:1,compared:1,badSaves:0,native:[1]});
assert.equal(rolled.fx.files, 3);
assert.deepEqual(rolled.fx.editors.patchy, {total:2,opened:1,matched:0,compared:1,badSaves:1,native:[0.5]});
"""
        known = script.split('function knownLimit', 1)[1].split('let skipKnown', 1)[0]
        test_js += 'function knownLimit' + known + """
assert.equal(knownLimit({}), '');
assert.equal(knownLimit({traits:{depth:8,mode:3}}), '');
assert.equal(knownLimit({traits:{depth:1,mode:0}}), '');
assert.equal(knownLimit({traits:{depth:16,mode:3}}), '16-bit');
assert.equal(knownLimit({traits:{depth:32,mode:3,artboards:true}}), '32-bit, artboards');
assert.equal(knownLimit({traits:{depth:8,mode:3,artboards:true}}), 'artboards');
"""
        standing = script.split('function standingRows', 1)[1].split('let groupFilter', 1)[0]
        test_js += 'function standingRows' + standing + """
assert.deepEqual(standingRows({photoshop:1, patchy:0.79, krita:0.37, photopea:0.88, gimp:null},
  ['photoshop','patchy','krita','gimp','photopea']).map(r => r.key), ['photopea','patchy','krita']);
"""
        js_file.write_text(test_js, encoding='utf-8')
        result = subprocess.run(['node', str(js_file)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main(verbosity=2)
