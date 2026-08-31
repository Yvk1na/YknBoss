const fs = require('fs');
const path = require('path');

const source = path.resolve(__dirname, '../modle/endsun/bettermodel/endsun_anuhe.bbmodel');
const target = path.resolve(__dirname, '../packs/bettermodel/models/endsun_anuhe.bbmodel');
const model = JSON.parse(fs.readFileSync(source, 'utf8'));

if (model.elements.some(element => element.name === 'scythe_cross_14')) {
  throw new Error('Refusing to sync the removed duplicate scythe plane');
}
const hasAnimation = name => model.animations.some(animation =>
  animation.name === name || animation.name === `animation.${name}`);
const requiredAnimations = [
  'entrance', 'death', 'sand_funeral', 'scythe_echo', 'nether_boundary',
  'sunfall', 'pharaoh_edict', 'twin_eclipse',
];
const missingAnimations = requiredAnimations.filter(name => !hasAnimation(name));
if (missingAnimations.length > 0) {
  throw new Error(`Required animations are missing: ${missingAnimations.join(', ')}`);
}

fs.writeFileSync(target, JSON.stringify(model));
