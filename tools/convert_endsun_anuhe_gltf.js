const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const inputPath = path.join(root, 'modle', 'endsun', 'source', 'Golden Royal Knight.gltf');
const outputPaths = [
  path.join(root, 'modle', 'endsun', 'bettermodel', 'endsun_anuhe.bbmodel'),
  path.join(root, 'packs', 'bettermodel', 'models', 'endsun_anuhe.bbmodel')
];
const gltf = JSON.parse(fs.readFileSync(inputPath, 'utf8'));
if (gltf.asset?.version !== '2.0') throw new Error('Only glTF 2.0 is supported');
if (!gltf.buffers?.[0]?.uri?.startsWith('data:')) throw new Error('Expected an embedded glTF buffer');

const rawBuffer = Buffer.from(gltf.buffers[0].uri.split(',')[1], 'base64');
const componentSize = new Map([[5120, 1], [5121, 1], [5122, 2], [5123, 2], [5125, 4], [5126, 4]]);
const componentCount = new Map([['SCALAR', 1], ['VEC2', 2], ['VEC3', 3], ['VEC4', 4]]);

function readComponent(offset, type) {
  switch (type) {
    case 5120: return rawBuffer.readInt8(offset);
    case 5121: return rawBuffer.readUInt8(offset);
    case 5122: return rawBuffer.readInt16LE(offset);
    case 5123: return rawBuffer.readUInt16LE(offset);
    case 5125: return rawBuffer.readUInt32LE(offset);
    case 5126: return rawBuffer.readFloatLE(offset);
    default: throw new Error(`Unsupported glTF component type ${type}`);
  }
}

function readAccessor(index) {
  const accessor = gltf.accessors[index];
  const view = gltf.bufferViews[accessor.bufferView];
  const size = componentSize.get(accessor.componentType);
  const count = componentCount.get(accessor.type);
  const stride = view.byteStride || size * count;
  const base = (view.byteOffset || 0) + (accessor.byteOffset || 0);
  return Array.from({ length: accessor.count }, (_, row) =>
    Array.from({ length: count }, (_, column) => readComponent(base + row * stride + column * size, accessor.componentType)));
}

function uuid() {
  return crypto.randomUUID();
}

function stableUuid(value) {
  const hex = crypto.createHash('sha256').update(value).digest('hex').slice(0, 32).split('');
  hex[12] = '4';
  hex[16] = ((parseInt(hex[16], 16) & 3) | 8).toString(16);
  const joined = hex.join('');
  return `${joined.slice(0, 8)}-${joined.slice(8, 12)}-${joined.slice(12, 16)}-${joined.slice(16, 20)}-${joined.slice(20)}`;
}

function rounded(value) {
  const result = Math.round(value * 1000000) / 1000000;
  return Object.is(result, -0) ? 0 : result;
}

function vector(values) {
  return values.map(rounded);
}

function quaternionToEulerZYX(q = [0, 0, 0, 1]) {
  const [x, y, z, w] = q;
  const yy = y * y;
  const m11 = 1 - 2 * (yy + z * z);
  const m12 = 2 * (x * y - z * w);
  const m21 = 2 * (x * y + z * w);
  const m22 = 1 - 2 * (x * x + z * z);
  const m31 = 2 * (x * z - y * w);
  const m32 = 2 * (y * z + x * w);
  const m33 = 1 - 2 * (x * x + yy);
  const ry = Math.asin(-Math.max(-1, Math.min(1, m31)));
  let rx;
  let rz;
  if (Math.abs(m31) < 0.9999999) {
    rx = Math.atan2(m32, m33);
    rz = Math.atan2(m21, m11);
  } else {
    rx = 0;
    rz = Math.atan2(-m12, m22);
  }
  const degrees = 180 / Math.PI;
  return vector([rx * degrees, ry * degrees, rz * degrees]);
}

const parentByNode = new Map();
gltf.nodes.forEach((node, parent) => (node.children || []).forEach(child => parentByNode.set(child, parent)));

function absoluteOrigin(index) {
  const result = [0, 0, 0];
  let current = index;
  while (current != null) {
    const translation = gltf.nodes[current].translation || [0, 0, 0];
    for (let axis = 0; axis < 3; axis++) result[axis] += translation[axis] * 16;
    current = parentByNode.get(current);
  }
  return vector(result);
}

const groupEntries = gltf.nodes
  .map((node, index) => ({ node, index }))
  .filter(({ node }) => node.mesh == null && node.name);
const groupUuid = new Map(groupEntries.map(({ index }) => [index, uuid()]));
const groups = groupEntries.map(({ node, index }) => ({
  name: node.name,
  uuid: groupUuid.get(index),
  export: true,
  locked: false,
  scope: 0,
  selected: false,
  _static: { properties: {}, temp_data: {} },
  origin: absoluteOrigin(index),
  rotation: quaternionToEulerZYX(node.rotation),
  color: index % 8,
  children: [],
  reset: false,
  shade: true,
  mirror_uv: false,
  visibility: true,
  autouv: 0,
  isOpen: false,
  primary_selected: false
}));
const groupByIndex = new Map(groupEntries.map(({ index }, position) => [index, groups[position]]));

const FACE_ORDER = ['east', 'west', 'up', 'down', 'south', 'north'];
const elements = [];
const elementUuidsByNode = new Map();
for (let index = 0; index < gltf.nodes.length; index++) {
  const node = gltf.nodes[index];
  if (node.mesh == null) continue;
  const primitive = gltf.meshes[node.mesh].primitives[0];
  const positionAccessor = gltf.accessors[primitive.attributes.POSITION];
  const positions = readAccessor(primitive.attributes.POSITION);
  const uvs = readAccessor(primitive.attributes.TEXCOORD_0);
  if (positions.length !== 24 || uvs.length !== 24) {
    throw new Error(`Mesh ${node.mesh} is not a Blockbench cuboid`);
  }
  const id = uuid();
  const nodeElementIds = [id];
  elementUuidsByNode.set(index, nodeElementIds);
  const faces = {};
  FACE_ORDER.forEach((faceName, faceIndex) => {
    const first = uvs[faceIndex * 4];
    const last = uvs[faceIndex * 4 + 3];
    faces[faceName] = { uv: vector([first[0] * 128, first[1] * 128, last[0] * 128, last[1] * 128]), texture: 0 };
  });
  if (index === 14) {
    // The source scythe is an almost zero-width plane. BetterModel can cull it while the arm moves
    // or when a player looks straight at its edge, so give every visible side a complete blade UV.
    faces.north = { uv: [...faces.east.uv], texture: 0 };
    faces.south = { uv: [...faces.west.uv], texture: 0 };
    faces.up = { uv: [...faces.east.uv], texture: 0 };
    faces.down = { uv: [...faces.west.uv], texture: 0 };
  }
  const parent = groupByIndex.get(parentByNode.get(index));
  // Blockbench's glTF exporter stores a cube pivot as child-translation = pivot - parent-pivot.
  // Reconstructing that pivot is required for the rotated crown, cape and scythe planes to stay attached.
  const nodeTranslation = node.translation || [0, 0, 0];
  const elementOrigin = parent
    ? vector(parent.origin.map((value, axis) => value + nodeTranslation[axis] * 16))
    : vector(nodeTranslation.map(value => value * 16));
  const from = vector(positionAccessor.min.map((value, axis) => value * 16 + elementOrigin[axis]));
  const to = vector(positionAccessor.max.map((value, axis) => value * 16 + elementOrigin[axis]));
  if (index === 14 && to[0] - from[0] < 0.25) {
    const center = (from[0] + to[0]) / 2;
    from[0] = rounded(center - 0.25);
    to[0] = rounded(center + 0.25);
  }
  const element = {
    name: `${node.name || 'cube'}_${index}`,
    box_uv: false,
    render_order: 'default',
    locked: false,
    export: true,
    scope: 0,
    allow_mirror_modeling: true,
    from,
    to,
    autouv: 0,
    color: index % 8,
    origin: elementOrigin,
    rotation: quaternionToEulerZYX(node.rotation),
    faces,
    type: 'cube',
    uuid: id
  };
  elements.push(element);
}

function outlineForNode(index) {
  const node = gltf.nodes[index];
  if (node.mesh != null) return elementUuidsByNode.get(index) || [];
  const children = (node.children || []).map(outlineForNode).flat().filter(Boolean);
  if (!node.name) return children;
  return { uuid: groupUuid.get(index), isOpen: false, children };
}

const rootNodeIndex = gltf.scenes[gltf.scene || 0].nodes[0];
const outliner = [outlineForNode(rootNodeIndex)].flat().filter(Boolean);

function addHitbox(parentName, name, from, to) {
  const parent = groups.find(group => group.name === parentName);
  if (!parent) throw new Error(`Missing parent bone ${parentName}`);
  const groupId = uuid();
  const elementId = uuid();
  groups.push({
    name, uuid: groupId, export: true, locked: false, scope: 0, selected: false,
    _static: { properties: {}, temp_data: {} }, origin: parent.origin, rotation: [0, 0, 0],
    color: 1, children: [], reset: false, shade: false, mirror_uv: false, visibility: true,
    autouv: 0, isOpen: false, primary_selected: false
  });
  elements.push({
    name: `${name}_shape`, box_uv: false, render_order: 'default', locked: true, export: true,
    scope: 0, allow_mirror_modeling: false, from, to, autouv: 0, color: 1, origin: parent.origin,
    visibility: false,
    faces: Object.fromEntries(FACE_ORDER.map(face => [face, { uv: [0, 0, 1, 1], texture: null }])),
    type: 'cube', uuid: elementId
  });
  function attach(entries) {
    for (const entry of entries) {
      if (typeof entry === 'object' && entry.uuid === parent.uuid) {
        entry.children.push({ uuid: groupId, isOpen: false, children: [elementId] });
        return true;
      }
      if (typeof entry === 'object' && attach(entry.children || [])) return true;
    }
    return false;
  }
  if (!attach(outliner)) throw new Error(`Unable to attach hitbox ${name}`);
}
addHitbox('Body', 'b_body_hitbox', [-6, 0, -5], [6, 25, 5]);

function keyframe(animation, bone, channel, time, value) {
  return {
    channel,
    data_points: [{ x: String(value[0]), y: String(value[1]), z: String(value[2]) }],
    uuid: stableUuid(`${animation}:${bone}:${channel}:${time}`),
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

function makeAnimation(name, length, loop, bones) {
  const ids = Object.fromEntries(groups.map(group => [group.name, group.uuid]));
  const animators = {};
  for (const [bone, channels] of Object.entries(bones)) {
    if (!ids[bone]) throw new Error(`Animation ${name} references missing bone ${bone}`);
    const keyframes = [];
    for (const [channel, frames] of Object.entries(channels)) {
      for (const [time, value] of frames) keyframes.push(keyframe(name, bone, channel, time, value));
    }
    animators[ids[bone]] = {
      name: bone,
      type: 'bone',
      rotation_global: false,
      quaternion_interpolation: false,
      keyframes
    };
  }
  return {
    uuid: stableUuid(`animation:${name}`), name, loop: loop ? 'loop' : 'once', override: false,
    length, snapping: 20, selected: false, group_name: '', scope: 0,
    anim_time_update: '', blend_weight: '', start_delay: '', loop_delay: '', animators
  };
}

const idle = makeAnimation('idle', 1.6, true, {
  Body: {
    position: [[0, [0, 0, 0]], [0.8, [0, 0.28, 0]], [1.6, [0, 0, 0]]],
    rotation: [[0, [0, -1.5, 0]], [0.8, [-1.5, 1.5, 0]], [1.6, [0, -1.5, 0]]]
  },
  Head: { rotation: [[0, [0, 2, 0]], [0.8, [1.5, -2, 0]], [1.6, [0, 2, 0]]] },
  LeftArm: { rotation: [[0, [1.5, -1, -1]], [0.8, [-1.5, 1, 1]], [1.6, [1.5, -1, -1]]] },
  RightArm: { rotation: [[0, [-1, 0, 1]], [0.8, [1, 0, -1]], [1.6, [-1, 0, 1]]] }
});

const walkTimes = [0, 0.2, 0.4, 0.6, 0.8];
const frames = values => walkTimes.map((time, index) => [time, values[index]]);
const walk = makeAnimation('walk', 0.8, true, {
  Body: {
    position: frames([[0, 0, 0], [0, 0.42, 0], [0, 0, 0], [0, 0.42, 0], [0, 0, 0]]),
    rotation: frames([[0, -3, -1], [-2, 0, 0], [0, 3, 1], [-2, 0, 0], [0, -3, -1]])
  },
  Head: { rotation: frames([[1, 3, 1], [2, 0, 0], [1, -3, -1], [2, 0, 0], [1, 3, 1]]) },
  RightLeg: { rotation: frames([[-32, 0, 0], [0, 0, 0], [32, 0, 0], [0, 0, 0], [-32, 0, 0]]) },
  LeftLeg: { rotation: frames([[32, 0, 0], [0, 0, 0], [-32, 0, 0], [0, 0, 0], [32, 0, 0]]) },
  RightArm: { rotation: frames([[27, 0, 3], [0, 0, 0], [-27, 0, -3], [0, 0, 0], [27, 0, 3]]) },
  LeftArm: { rotation: frames([[-14, 0, -2], [0, 0, 0], [14, 0, 2], [0, 0, 0], [-14, 0, -2]]) }
});

const slash = makeAnimation('scythe_slash', 0.9, false, {
  Body: {
    position: [[0, [0, 0, 0]], [0.16, [0, -0.16, 0]], [0.52, [0, 0.22, -0.12]], [0.9, [0, 0, 0]]],
    rotation: [[0, [0, 0, 0]], [0.16, [0, -30, -3]], [0.3, [0, -38, -4]], [0.48, [0, 38, 5]], [0.66, [0, 24, 3]], [0.9, [0, 0, 0]]]
  },
  Head: { rotation: [[0, [0, 0, 0]], [0.16, [0, 18, 1]], [0.48, [0, -22, -2]], [0.9, [0, 0, 0]]] },
  // BetterModel applies this imported arm pivot with the opposite forward pitch from the assumption
  // used by the previous animation. Negative X is the wind-up behind the shoulder; positive X drives
  // the hand and scythe through the space in front of the boss.
  LeftArm: { rotation: [[0, [0, 0, 0]], [0.16, [-24, -10, -14]], [0.3, [-58, -24, -32]], [0.42, [12, 14, 8]], [0.54, [78, 48, 32]], [0.7, [42, 20, 14]], [0.9, [0, 0, 0]]] },
  RightArm: { rotation: [[0, [0, 0, 0]], [0.16, [18, 6, 16]], [0.54, [-28, -18, -20]], [0.9, [0, 0, 0]]] },
  RightLeg: { rotation: [[0, [0, 0, 0]], [0.16, [10, 0, 3]], [0.54, [-9, 0, -4]], [0.9, [0, 0, 0]]] },
  LeftLeg: { rotation: [[0, [0, 0, 0]], [0.16, [-12, 0, -3]], [0.54, [8, 0, 4]], [0.9, [0, 0, 0]]] }
});

const imageUri = gltf.images[0].uri;
const imageBytes = Buffer.from(imageUri.split(',')[1], 'base64');
const textureWidth = imageBytes.readUInt32BE(16);
const textureHeight = imageBytes.readUInt32BE(20);
const project = {
  meta: { format_version: '5.0', model_format: 'free', box_uv: false },
  name: 'endsun_anuhe',
  model_identifier: 'yknboss:endsun_anuhe',
  visible_box: [4, 5, 0],
  variable_placeholders: '', multi_file_ruleset: '', variable_placeholder_buttons: [],
  timeline_setups: [], unhandled_root_fields: {},
  resolution: { width: textureWidth, height: textureHeight },
  elements, groups, outliner,
  textures: [{
    name: 'endsun_anuhe', path: '', folder: '', namespace: 'yknboss', id: '0', group: '', scope: 0,
    width: textureWidth, height: textureHeight, uv_width: textureWidth, uv_height: textureHeight,
    particle: false, use_as_default: true, layers_enabled: false, sync_to_project: '', file_format: 'png',
    render_mode: 'default', render_sides: 'double', wrap_mode: 'limited', pbr_channel: 'color', fps: 7,
    frame_time: 1, frame_order_type: 'loop', frame_order: '', frame_interpolate: false, visible: true,
    internal: true, saved: false, uuid: uuid(), source: imageUri
  }],
  animations: [idle, walk, slash]
};

for (const outputPath of outputPaths) {
  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, JSON.stringify(project));
  console.log(`Wrote ${outputPath}`);
}
console.log(JSON.stringify({ cubes: elements.length, bones: groups.length, animations: project.animations.map(value => value.name), texture: `${textureWidth}x${textureHeight}` }));
