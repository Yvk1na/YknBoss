const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const projectRoot = path.resolve(__dirname, '..');
const modelPaths = [
  path.join(projectRoot, 'packs', 'bettermodel', 'models', 'skeleton_dragon.bbmodel'),
  path.join(projectRoot, 'modle', 'skeleton-drgaon', 'bettermodel', 'skeleton_dragon.bbmodel')
];

function stableUuid(value) {
  const hex = crypto.createHash('sha256').update(value).digest('hex').slice(0, 32).split('');
  hex[12] = '4';
  hex[16] = ((parseInt(hex[16], 16) & 3) | 8).toString(16);
  const joined = hex.join('');
  return `${joined.slice(0, 8)}-${joined.slice(8, 12)}-${joined.slice(12, 16)}-${joined.slice(16, 20)}-${joined.slice(20)}`;
}

function keyframe(bone, channel, time, value) {
  return {
    channel,
    data_points: [{ x: String(value[0]), y: String(value[1]), z: String(value[2]) }],
    uuid: stableUuid(`ground_walk:${bone}:${channel}:${time}`),
    time,
    color: -1,
    interpolation: 'bezier',
    bezier_linked: true,
    bezier_left_time: [-0.08, -0.08, -0.08],
    bezier_left_value: [0, 0, 0],
    bezier_right_time: [0.08, 0.08, 0.08],
    bezier_right_value: [0, 0, 0]
  };
}

function animator(bone, rotations, positions = []) {
  return {
    name: bone,
    type: 'bone',
    rotation_global: false,
    quaternion_interpolation: false,
    keyframes: [
      ...rotations.map(([time, value]) => keyframe(bone, 'rotation', time, value)),
      ...positions.map(([time, value]) => keyframe(bone, 'position', time, value))
    ]
  };
}

function addGroundWalk(model) {
  const ids = Object.fromEntries(model.groups.map(group => [group.name, group.uuid]));
  const required = [
    'body', 'hium', 'neck', 'head', 'tail1', 'tail2',
    'leftHumerus', 'rightHumerus', 'leftRadiusulna', 'rightRadiusulna',
    'leftFemur', 'rightFemur', 'leftTibia', 'rightTibia',
    'Foot1', 'Foot2', 'Foot3', 'Foot4'
  ];
  for (const bone of required) {
    if (!ids[bone]) throw new Error(`Missing bone: ${bone}`);
  }

  const times = [0, 0.225, 0.45, 0.675, 0.9];
  const frames = values => times.map((time, index) => [time, values[index]]);
  const animators = {};
  const add = (bone, rotations, positions) => {
    animators[ids[bone]] = animator(bone, frames(rotations), positions ? frames(positions) : []);
  };

  add('body',
    [[-8, 0, -1.5], [-10, 0, 0], [-8, 0, 1.5], [-10, 0, 0], [-8, 0, -1.5]],
    [[0, -0.2, 0], [0, 0.38, 0], [0, -0.2, 0], [0, 0.38, 0], [0, -0.2, 0]]);
  add('hium', [[0, 4, 2], [1, 0, 0], [0, -4, -2], [1, 0, 0], [0, 4, 2]]);
  add('neck', [[-10, -2, 1], [-6, 0, 0], [-10, 2, -1], [-6, 0, 0], [-10, -2, 1]]);
  add('head', [[-6, 2, -1], [-10, 0, 0], [-6, -2, 1], [-10, 0, 0], [-6, 2, -1]]);
  add('tail1', [[0, 9, 2], [0, 0, 0], [0, -9, -2], [0, 0, 0], [0, 9, 2]]);
  add('tail2', [[0, 13, 1], [0, 0, 0], [0, -13, -1], [0, 0, 0], [0, 13, 1]]);

  // Diagonal dog-trot: left-front + right-rear, then right-front + left-rear.
  add('leftHumerus', [[-28, 0, -3], [0, 0, 0], [28, 0, 3], [0, 0, 0], [-28, 0, -3]]);
  add('rightHumerus', [[28, 0, 3], [0, 0, 0], [-28, 0, -3], [0, 0, 0], [28, 0, 3]]);
  add('leftFemur', [[32, 0, 3], [0, 0, 0], [-32, 0, -3], [0, 0, 0], [32, 0, 3]]);
  add('rightFemur', [[-32, 0, -3], [0, 0, 0], [32, 0, 3], [0, 0, 0], [-32, 0, -3]]);

  add('leftRadiusulna', [[18, 0, 0], [-6, 0, 0], [-16, 0, 0], [8, 0, 0], [18, 0, 0]]);
  add('rightRadiusulna', [[-16, 0, 0], [8, 0, 0], [18, 0, 0], [-6, 0, 0], [-16, 0, 0]]);
  add('leftTibia', [[-18, 0, 0], [4, 0, 0], [22, 0, 0], [-5, 0, 0], [-18, 0, 0]]);
  add('rightTibia', [[22, 0, 0], [-5, 0, 0], [-18, 0, 0], [4, 0, 0], [22, 0, 0]]);

  add('Foot1', [[-12, 0, 0], [8, 0, 0], [16, 0, 0], [-6, 0, 0], [-12, 0, 0]]);
  add('Foot2', [[16, 0, 0], [-6, 0, 0], [-12, 0, 0], [8, 0, 0], [16, 0, 0]]);
  add('Foot3', [[16, 0, 0], [-5, 0, 0], [-14, 0, 0], [7, 0, 0], [16, 0, 0]]);
  add('Foot4', [[-14, 0, 0], [7, 0, 0], [16, 0, 0], [-5, 0, 0], [-14, 0, 0]]);

  const animation = {
    uuid: stableUuid('animation:ground_walk'),
    name: 'ground_walk',
    loop: 'loop',
    override: false,
    length: 0.9,
    snapping: 20,
    selected: false,
    group_name: '',
    scope: 0,
    anim_time_update: '',
    blend_weight: '',
    start_delay: '',
    loop_delay: '',
    animators
  };

  model.animations = model.animations.filter(existing => existing.name !== 'ground_walk');
  const idleIndex = model.animations.findIndex(existing => existing.name === 'ground_idle');
  model.animations.splice(idleIndex < 0 ? model.animations.length : idleIndex + 1, 0, animation);
}

for (const modelPath of modelPaths) {
  const model = JSON.parse(fs.readFileSync(modelPath, 'utf8'));
  addGroundWalk(model);
  fs.writeFileSync(modelPath, JSON.stringify(model));
  console.log(`Updated ${modelPath}`);
}
