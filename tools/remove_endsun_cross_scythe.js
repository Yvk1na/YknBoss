const fs = require('fs');
const path = require('path');

const files = [
  path.resolve(__dirname, '../modle/endsun/bettermodel/endsun_anuhe.bbmodel'),
  path.resolve(__dirname, '../packs/bettermodel/models/endsun_anuhe.bbmodel'),
];

function removeFromOutliner(nodes, uuid) {
  if (!Array.isArray(nodes)) return;
  for (let index = nodes.length - 1; index >= 0; index--) {
    if (nodes[index] === uuid) {
      nodes.splice(index, 1);
      continue;
    }
    if (nodes[index] && typeof nodes[index] === 'object') {
      removeFromOutliner(nodes[index].children, uuid);
    }
  }
}

for (const file of files) {
  const model = JSON.parse(fs.readFileSync(file, 'utf8'));
  const duplicate = model.elements.find(element => element.name === 'scythe_cross_14');
  if (!duplicate) continue;
  model.elements = model.elements.filter(element => element.uuid !== duplicate.uuid);
  removeFromOutliner(model.outliner, duplicate.uuid);
  fs.writeFileSync(file, JSON.stringify(model));
}
