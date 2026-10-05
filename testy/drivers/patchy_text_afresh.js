// Run by Testy through `patchy.exe --run-script` (drivers/patchy.py render_text_afresh):
// make Patchy lay out every type layer with its own text engine, then export.
//
// Like Photoshop, Patchy shows the pixels saved in the file for a type layer until the
// layer is edited. layer.rerenderText() replaces them with Patchy's own render and
// changes nothing else. The layer names reached are printed as one JSON line.
const testyDocument = app.activeDocument;
const testyDone = [];
const testyFailed = [];
function testyWalk(layers) {
  for (const layer of layers) {
    if (layer.isGroup) {
      testyWalk(layer.children);
      continue;
    }
    if (!layer.isText) continue;
    try {
      layer.rerenderText();
      testyDone.push(layer.name);
    } catch (error) {
      testyFailed.push(layer.name);
    }
  }
}
testyWalk(testyDocument.layers);
const testyExported = testyDocument.exportAs(patchy.args.out);
console.log(JSON.stringify({testyTextAfresh: true, exported: testyExported, done: testyDone, failed: testyFailed}));
