const fs = require('fs');
const path = require('path');

const files = [
  path.resolve(__dirname, '../modle/endsun/bettermodel/endsun_anuhe.bbmodel'),
  path.resolve(__dirname, '../packs/bettermodel/models/endsun_anuhe.bbmodel'),
];

for (const file of files) {
  const model = JSON.parse(fs.readFileSync(file, 'utf8'));
  const animation = model.animations.find(value => value.name.endsWith('solar_shield'));
  if (!animation) throw new Error(`solar_shield missing from ${file}`);
  const leftArm = Object.values(animation.animators)
    .find(animator => animator.name === 'LeftArm');
  if (!leftArm) throw new Error(`LeftArm track missing from ${file}`);
  for (const keyframe of leftArm.keyframes) {
    if (keyframe.channel !== 'rotation') continue;
    keyframe.data_points = [{ x: '0', y: '0', z: '0' }];
    keyframe.interpolation = 'bezier';
  }
  fs.writeFileSync(file, JSON.stringify(model));
}
