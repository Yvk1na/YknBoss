const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const manualPath = path.join(root, 'modle', 'endsun', 'bettermodel', 'endsun_anuhe.bbmodel');
const packPath = path.join(root, 'packs', 'bettermodel', 'models', 'endsun_anuhe.bbmodel');
const requestedNames = process.argv.slice(2);

const manual = JSON.parse(fs.readFileSync(manualPath, 'utf8'));
const current = JSON.parse(fs.readFileSync(packPath, 'utf8'));
const groupIds = new Map(current.groups.map(group => [group.name, group.uuid]));
const selected = requestedNames.length === 0
  ? manual.animations
  : requestedNames.map(name => {
      const animation = manual.animations.find(candidate => candidate.name === name
        || candidate.name === `animation.${name}`);
      if (!animation) throw new Error(`Manual model is missing animation ${name}`);
      return animation;
    });
const mergedNames = [];

for (const manualAnimation of selected) {
  const canonicalName = manualAnimation.name.startsWith('animation.')
    ? manualAnimation.name.substring('animation.'.length)
    : manualAnimation.name;
  const currentIndex = current.animations.findIndex(animation => animation.name === canonicalName
    || animation.name === `animation.${canonicalName}`);
  const currentAnimation = currentIndex >= 0 ? current.animations[currentIndex] : null;
  const animators = {};
  for (const [sourceId, animator] of Object.entries(manualAnimation.animators || {})) {
    if (animator.type !== 'bone') {
      animators[sourceId] = animator;
      continue;
    }
    const targetId = groupIds.get(animator.name);
    if (!targetId) throw new Error(`Current model is missing animated bone ${animator.name}`);
    animators[targetId] = animator;
  }
  const merged = {
    ...manualAnimation,
    name: canonicalName,
    uuid: currentAnimation?.uuid || manualAnimation.uuid,
    animators
  };
  if (currentIndex >= 0) current.animations[currentIndex] = merged;
  else current.animations.push(merged);
  mergedNames.push(canonicalName);
}

const serialized = JSON.stringify(current);
fs.writeFileSync(manualPath, serialized);
fs.writeFileSync(packPath, serialized);
console.log(JSON.stringify({
  animations: mergedNames,
  elements: current.elements.length,
  output: [manualPath, packPath]
}));
