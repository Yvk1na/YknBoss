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
    uuid: stableUuid(`bone_spikes:${bone}:${channel}:${time}`),
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

function addBoneSpikes(model) {
  const ids = Object.fromEntries(model.groups.map(group => [group.name, group.uuid]));
  const times = [0, 0.42, 0.78, 1.02, 1.28, 1.62, 2.15, 3.0];
  const frames = values => times.map((time, index) => [time, values[index]]);
  const definitions = {
    body: {
      rotation: frames([[0, 0, 0], [-10, 0, 0], [-16, 0, 0], [14, 0, 0], [5, 0, 0], [10, 0, 0], [-4, 0, 0], [0, 0, 0]]),
      position: frames([[0, 0, 0], [0, 0.55, 0], [0, 1.35, 0], [0, -1.5, 0], [0, -0.65, 0], [0, -0.95, 0], [0, 0.25, 0], [0, 0, 0]])
    },
    hium: { rotation: frames([[0, 0, 0], [7, 0, 0], [14, 0, 0], [-13, 0, 0], [-4, 0, 0], [-9, 0, 0], [3, 0, 0], [0, 0, 0]]) },
    neck: { rotation: frames([[0, 0, 0], [-8, 0, 0], [-18, 0, 0], [27, 0, 0], [12, -4, 0], [19, 4, 0], [-5, 0, 0], [0, 0, 0]]) },
    head: { rotation: frames([[0, 0, 0], [-6, 0, 0], [-15, 0, 0], [31, 0, 0], [16, 5, 0], [23, -5, 0], [-4, 0, 0], [0, 0, 0]]) },
    jaw: { rotation: frames([[0, 0, 0], [7, 0, 0], [16, 0, 0], [38, 0, 0], [29, 0, 0], [35, 0, 0], [10, 0, 0], [0, 0, 0]]) },
    leftHumerus: { rotation: frames([[0, 0, 0], [-16, 0, -5], [-38, 0, -12], [27, 0, 8], [13, 0, 4], [21, 0, 6], [-7, 0, -2], [0, 0, 0]]) },
    rightHumerus: { rotation: frames([[0, 0, 0], [-16, 0, 5], [-38, 0, 12], [27, 0, -8], [13, 0, -4], [21, 0, -6], [-7, 0, 2], [0, 0, 0]]) },
    leftRadiusulna: { rotation: frames([[0, 0, 0], [12, 0, 0], [29, 0, 0], [-24, 0, 0], [-10, 0, 0], [-18, 0, 0], [5, 0, 0], [0, 0, 0]]) },
    rightRadiusulna: { rotation: frames([[0, 0, 0], [12, 0, 0], [29, 0, 0], [-24, 0, 0], [-10, 0, 0], [-18, 0, 0], [5, 0, 0], [0, 0, 0]]) },
    leftFemur: { rotation: frames([[0, 0, 0], [12, 0, -3], [22, 0, -6], [-17, 0, 5], [-8, 0, 2], [-13, 0, 4], [4, 0, -1], [0, 0, 0]]) },
    rightFemur: { rotation: frames([[0, 0, 0], [12, 0, 3], [22, 0, 6], [-17, 0, -5], [-8, 0, -2], [-13, 0, -4], [4, 0, 1], [0, 0, 0]]) },
    leftTibia: { rotation: frames([[0, 0, 0], [-8, 0, 0], [-18, 0, 0], [15, 0, 0], [6, 0, 0], [11, 0, 0], [-3, 0, 0], [0, 0, 0]]) },
    rightTibia: { rotation: frames([[0, 0, 0], [-8, 0, 0], [-18, 0, 0], [15, 0, 0], [6, 0, 0], [11, 0, 0], [-3, 0, 0], [0, 0, 0]]) },
    tail1: { rotation: frames([[0, 0, 0], [3, 10, 0], [6, -15, 0], [-8, 18, 0], [4, -19, 0], [-5, 16, 0], [2, -7, 0], [0, 0, 0]]) },
    tail2: { rotation: frames([[0, 0, 0], [2, 15, 0], [5, -21, 0], [-6, 25, 0], [3, -27, 0], [-4, 23, 0], [1, -10, 0], [0, 0, 0]]) }
  };

  const animators = {};
  for (const [bone, data] of Object.entries(definitions)) {
    if (!ids[bone]) throw new Error(`Missing bone: ${bone}`);
    animators[ids[bone]] = animator(bone, data.rotation, data.position || []);
  }
  const animation = {
    uuid: stableUuid('animation:bone_spikes'),
    name: 'bone_spikes',
    loop: 'once',
    override: false,
    length: 3.0,
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

  model.animations = model.animations.filter(existing => existing.name !== 'bone_spikes');
  const prisonIndex = model.animations.findIndex(existing => existing.name === 'bone_prison');
  model.animations.splice(prisonIndex < 0 ? model.animations.length : prisonIndex + 1, 0, animation);
}

for (const modelPath of modelPaths) {
  const model = JSON.parse(fs.readFileSync(modelPath, 'utf8'));
  addBoneSpikes(model);
  fs.writeFileSync(modelPath, JSON.stringify(model));
  console.log(`Updated ${modelPath}`);
}
