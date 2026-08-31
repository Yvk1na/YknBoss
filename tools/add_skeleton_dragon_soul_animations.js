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

function keyframe(animation, bone, channel, time, value) {
  return {
    channel,
    data_points: [{ x: String(value[0]), y: String(value[1]), z: String(value[2]) }],
    uuid: stableUuid(`${animation}:${bone}:${channel}:${time}`),
    time,
    color: -1,
    interpolation: 'bezier',
    bezier_linked: true,
    bezier_left_time: [-0.10, -0.10, -0.10],
    bezier_left_value: [0, 0, 0],
    bezier_right_time: [0.10, 0.10, 0.10],
    bezier_right_value: [0, 0, 0]
  };
}

function animator(animation, bone, rotations, positions = []) {
  return {
    name: bone,
    type: 'bone',
    rotation_global: false,
    quaternion_interpolation: false,
    keyframes: [
      ...rotations.map(([time, value]) => keyframe(animation, bone, 'rotation', time, value)),
      ...positions.map(([time, value]) => keyframe(animation, bone, 'position', time, value))
    ]
  };
}

function createAnimation(model, name, definitions) {
  const ids = Object.fromEntries(model.groups.map(group => [group.name, group.uuid]));
  const animators = {};
  for (const [bone, data] of Object.entries(definitions)) {
    if (!ids[bone]) throw new Error(`Missing bone: ${bone}`);
    animators[ids[bone]] = animator(name, bone, data.rotation || [], data.position || []);
  }
  return {
    uuid: stableUuid(`animation:${name}`),
    name,
    loop: 'once',
    override: false,
    length: 2.4,
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
}

function deathSummon(model) {
  const t = [0, 0.42, 0.95, 1.40, 1.82, 2.40];
  const frames = values => t.map((time, index) => [time, values[index]]);
  return createAnimation(model, 'death_summon', {
    body: {
      rotation: frames([[0, 0, 0], [-15, 0, 0], [-7, 0, 0], [9, 0, 0], [-4, 0, 0], [0, 0, 0]]),
      position: frames([[0, 0, 0], [0, -1.0, 0], [0, -0.3, 0], [0, 1.5, 0], [0, 0.35, 0], [0, 0, 0]])
    },
    hium: { rotation: frames([[0, 0, 0], [9, 0, 0], [-5, 0, 0], [-11, 0, 0], [5, 0, 0], [0, 0, 0]]) },
    neck: { rotation: frames([[0, 0, 0], [19, 0, 0], [7, -3, 0], [-24, 0, 0], [-8, 3, 0], [0, 0, 0]]) },
    head: { rotation: frames([[0, 0, 0], [12, 0, 0], [2, 4, 0], [-28, 0, 0], [-7, -4, 0], [0, 0, 0]]) },
    jaw: { rotation: frames([[0, 0, 0], [8, 0, 0], [23, 0, 0], [39, 0, 0], [18, 0, 0], [0, 0, 0]]) },
    leftWing: { rotation: frames([[0, 0, 0], [18, -36, -16], [10, -54, -25], [-22, 27, 18], [-7, 11, 7], [0, 0, 0]]) },
    rightWing: { rotation: frames([[0, 0, 0], [18, 36, 16], [10, 54, 25], [-22, -27, -18], [-7, -11, -7], [0, 0, 0]]) },
    leftHumerus: { rotation: frames([[0, 0, 0], [-15, 0, -10], [-29, 0, -17], [15, 0, 10], [5, 0, 3], [0, 0, 0]]) },
    rightHumerus: { rotation: frames([[0, 0, 0], [-15, 0, 10], [-29, 0, 17], [15, 0, -10], [5, 0, -3], [0, 0, 0]]) },
    tail1: { rotation: frames([[0, 0, 0], [7, 10, 0], [11, -12, 0], [-6, 14, 0], [2, -7, 0], [0, 0, 0]]) },
    tail2: { rotation: frames([[0, 0, 0], [4, 15, 0], [8, -18, 0], [-4, 20, 0], [1, -10, 0], [0, 0, 0]]) }
  });
}

function skeletonAbsorb(model) {
  const t = [0, 0.35, 0.80, 1.20, 1.60, 2.00, 2.40];
  const frames = values => t.map((time, index) => [time, values[index]]);
  return createAnimation(model, 'skeleton_absorb', {
    body: {
      rotation: frames([[0, 0, 0], [-12, 0, 0], [-18, 0, 0], [-8, 0, 0], [-16, 0, 0], [10, 0, 0], [0, 0, 0]]),
      position: frames([[0, 0, 0], [0, -0.8, 0], [0, -1.3, 0], [0, -0.6, 0], [0, -1.1, 0], [0, 1.1, 0], [0, 0, 0]])
    },
    hium: { rotation: frames([[0, 0, 0], [8, 0, 0], [14, 0, 0], [5, 0, 0], [12, 0, 0], [-8, 0, 0], [0, 0, 0]]) },
    neck: { rotation: frames([[0, 0, 0], [18, 0, 0], [28, 0, 0], [15, -4, 0], [26, 4, 0], [-18, 0, 0], [0, 0, 0]]) },
    head: { rotation: frames([[0, 0, 0], [12, 0, 0], [22, 0, 0], [8, 5, 0], [20, -5, 0], [-24, 0, 0], [0, 0, 0]]) },
    jaw: { rotation: frames([[0, 0, 0], [15, 0, 0], [35, 0, 0], [43, 0, 0], [34, 0, 0], [12, 0, 0], [0, 0, 0]]) },
    leftWing: { rotation: frames([[0, 0, 0], [12, -28, -16], [25, -48, -30], [14, -35, -20], [28, -52, -32], [-18, 22, 15], [0, 0, 0]]) },
    rightWing: { rotation: frames([[0, 0, 0], [12, 28, 16], [25, 48, 30], [14, 35, 20], [28, 52, 32], [-18, -22, -15], [0, 0, 0]]) },
    leftHumerus: { rotation: frames([[0, 0, 0], [-12, 8, -10], [-35, 18, -24], [-18, 10, -15], [-38, 21, -27], [20, -8, 12], [0, 0, 0]]) },
    rightHumerus: { rotation: frames([[0, 0, 0], [-12, -8, 10], [-35, -18, 24], [-18, -10, 15], [-38, -21, 27], [20, 8, -12], [0, 0, 0]]) },
    leftRadiusulna: { rotation: frames([[0, 0, 0], [18, 0, -5], [38, 0, -12], [24, 0, -7], [42, 0, -14], [-12, 0, 5], [0, 0, 0]]) },
    rightRadiusulna: { rotation: frames([[0, 0, 0], [18, 0, 5], [38, 0, 12], [24, 0, 7], [42, 0, 14], [-12, 0, -5], [0, 0, 0]]) },
    tail1: { rotation: frames([[0, 0, 0], [4, 13, 0], [8, -16, 0], [5, 18, 0], [9, -20, 0], [-5, 11, 0], [0, 0, 0]]) },
    tail2: { rotation: frames([[0, 0, 0], [3, 18, 0], [6, -22, 0], [4, 25, 0], [7, -28, 0], [-4, 15, 0], [0, 0, 0]]) }
  });
}

for (const modelPath of modelPaths) {
  const model = JSON.parse(fs.readFileSync(modelPath, 'utf8'));
  model.animations = model.animations.filter(animation =>
    animation.name !== 'death_summon' && animation.name !== 'skeleton_absorb');
  const summonIndex = model.animations.findIndex(animation => animation.name === 'summon');
  const insertion = summonIndex < 0 ? model.animations.length : summonIndex + 1;
  model.animations.splice(insertion, 0, deathSummon(model), skeletonAbsorb(model));
  fs.writeFileSync(modelPath, JSON.stringify(model));
  console.log(`Updated ${modelPath}`);
}
