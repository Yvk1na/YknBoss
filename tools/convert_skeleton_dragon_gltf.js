const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const root = path.resolve(__dirname, '..');
const inputPath = path.join(root, 'modle', 'skeleton-drgaon', 'source', 'model.gltf');
const outputDir = path.join(root, 'modle', 'skeleton-drgaon', 'bettermodel');
const outputPath = path.join(outputDir, 'skeleton_dragon.bbmodel');

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
  if (!size || !count) throw new Error(`Unsupported accessor ${index}`);
  const stride = view.byteStride || size * count;
  const base = (view.byteOffset || 0) + (accessor.byteOffset || 0);
  return Array.from({ length: accessor.count }, (_, row) =>
    Array.from({ length: count }, (_, column) => readComponent(base + row * stride + column * size, accessor.componentType))
  );
}

function uuid() {
  return crypto.randomUUID();
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
  const xx = x * x, xy = x * y, xz = x * z, xw = x * w;
  const yy = y * y, yz = y * z, yw = y * w;
  const zz = z * z, zw = z * w;
  const m11 = 1 - 2 * (yy + zz);
  const m12 = 2 * (xy - zw);
  const m21 = 2 * (xy + zw);
  const m22 = 1 - 2 * (xx + zz);
  const m31 = 2 * (xz - yw);
  const m32 = 2 * (yz + xw);
  const m33 = 1 - 2 * (xx + yy);
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

let minZ = Infinity;
let maxZ = -Infinity;
for (const mesh of gltf.meshes) {
  const accessor = gltf.accessors[mesh.primitives[0].attributes.POSITION];
  minZ = Math.min(minZ, accessor.min[2]);
  maxZ = Math.max(maxZ, accessor.max[2]);
}
const originalLength = (maxZ - minZ) * 16;
const scale = 160 / originalLength;

const groupNodeIndices = gltf.nodes
  .map((node, index) => ({ node, index }))
  .filter(entry => entry.node.mesh == null && entry.node.name);
const groupUuid = new Map(groupNodeIndices.map(entry => [entry.index, uuid()]));

function absoluteUnscaledOrigin(index) {
  const result = [0, 0, 0];
  let current = index;
  while (current != null) {
    const translation = gltf.nodes[current].translation || [0, 0, 0];
    for (let axis = 0; axis < 3; axis++) result[axis] += translation[axis] * 16;
    current = parentByNode.get(current);
  }
  return result;
}

const bodyIndex = gltf.nodes.findIndex(node => node.mesh == null && node.name === 'body');
if (bodyIndex < 0) throw new Error('The source model has no body bone');
const bodyOffset = absoluteUnscaledOrigin(bodyIndex).map(value => value * scale);

function modelPoint(point) {
  return vector(point.map((value, axis) => value * 16 * scale - bodyOffset[axis]));
}

const groups = groupNodeIndices.map(({ node, index }) => ({
  name: node.name,
  uuid: groupUuid.get(index),
  export: true,
  locked: false,
  scope: 0,
  selected: false,
  _static: { properties: {}, temp_data: {} },
  origin: vector(absoluteUnscaledOrigin(index).map((value, axis) => value * scale - bodyOffset[axis])),
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

const FACE_ORDER = ['east', 'west', 'up', 'down', 'south', 'north'];
const elements = [];
const elementUuidByNode = new Map();

for (let nodeIndex = 0; nodeIndex < gltf.nodes.length; nodeIndex++) {
  const node = gltf.nodes[nodeIndex];
  if (node.mesh == null) continue;
  const primitive = gltf.meshes[node.mesh].primitives[0];
  const positionAccessor = gltf.accessors[primitive.attributes.POSITION];
  const positions = readAccessor(primitive.attributes.POSITION);
  const uvs = readAccessor(primitive.attributes.TEXCOORD_0);
  if (positions.length !== 24 || uvs.length !== 24) {
    throw new Error(`Mesh ${node.mesh} is not an exported Blockbench cuboid`);
  }
  const id = uuid();
  elementUuidByNode.set(nodeIndex, id);
  const faces = {};
  FACE_ORDER.forEach((faceName, faceIndex) => {
    const first = uvs[faceIndex * 4];
    const last = uvs[faceIndex * 4 + 3];
    faces[faceName] = {
      uv: vector([first[0] * 128, first[1] * 128, last[0] * 128, last[1] * 128]),
      texture: 0
    };
  });
  const parentIndex = parentByNode.get(nodeIndex);
  const parentOrigin = groupUuid.has(parentIndex)
    ? groups.find(group => group.uuid === groupUuid.get(parentIndex)).origin
    : [0, 0, 0];
  elements.push({
    name: `${node.name || 'cube'}_${nodeIndex}`,
    box_uv: false,
    render_order: 'default',
    locked: false,
    export: true,
    scope: 0,
    allow_mirror_modeling: true,
    from: modelPoint(positionAccessor.min),
    to: modelPoint(positionAccessor.max),
    autouv: 0,
    color: nodeIndex % 8,
    origin: parentOrigin,
    faces,
    type: 'cube',
    uuid: id
  });
}

function outlineForNode(index) {
  const node = gltf.nodes[index];
  if (node.mesh != null) return elementUuidByNode.get(index);
  if (!node.name) {
    const children = (node.children || []).map(outlineForNode).filter(Boolean);
    return children.length === 1 ? children[0] : children;
  }
  return {
    uuid: groupUuid.get(index),
    isOpen: false,
    children: (node.children || []).map(outlineForNode).flat().filter(Boolean)
  };
}

const rootNodeIndex = gltf.scenes[gltf.scene || 0].nodes[0];
let outliner = [outlineForNode(rootNodeIndex)].flat().filter(Boolean);

function addHitbox(parentName, name, from, to) {
  const parentGroup = groups.find(group => group.name === parentName);
  if (!parentGroup) throw new Error(`Missing hitbox parent ${parentName}`);
  const hitboxGroupId = uuid();
  const hitboxElementId = uuid();
  groups.push({
    name,
    uuid: hitboxGroupId,
    export: true,
    locked: false,
    scope: 0,
    selected: false,
    _static: { properties: {}, temp_data: {} },
    origin: parentGroup.origin,
    rotation: [0, 0, 0],
    color: 1,
    children: [],
    reset: false,
    shade: false,
    mirror_uv: false,
    visibility: true,
    autouv: 0,
    isOpen: false,
    primary_selected: false
  });
  elements.push({
    name: `${name}_shape`,
    box_uv: false,
    render_order: 'default',
    locked: true,
    export: true,
    scope: 0,
    allow_mirror_modeling: false,
    from: modelPoint(from.map(value => value / 16)),
    to: modelPoint(to.map(value => value / 16)),
    autouv: 0,
    color: 1,
    origin: parentGroup.origin,
    visibility: false,
    faces: Object.fromEntries(FACE_ORDER.map(face => [face, { uv: [0, 0, 1, 1], texture: null }])),
    type: 'cube',
    uuid: hitboxElementId
  });
  function attach(nodes) {
    for (const entry of nodes) {
      if (typeof entry === 'object' && entry.uuid === parentGroup.uuid) {
        entry.children.push({ uuid: hitboxGroupId, isOpen: false, children: [hitboxElementId] });
        return true;
      }
      if (typeof entry === 'object' && attach(entry.children || [])) return true;
    }
    return false;
  }
  if (!attach(outliner)) throw new Error(`Unable to attach hitbox ${name}`);
}

addHitbox('body', 'b_body_hitbox', [-13, 7, -20], [13, 27, 22]);
addHitbox('head', 'b_head_hitbox', [-5, 12, -43], [5, 23, -24]);
addHitbox('tail1', 'b_tail1_hitbox', [-9, 5, 21], [9, 19, 40]);
addHitbox('tail2', 'b_tail2_hitbox', [-8, 5, 39], [8, 18, 61]);
addHitbox('tail3', 'b_tail3_hitbox', [-5, 6, 60], [5, 18, 82]);

const imageUri = gltf.images[0].uri;
const imageBytes = Buffer.from(imageUri.split(',')[1], 'base64');
const textureWidth = imageBytes.readUInt32BE(16);
const textureHeight = imageBytes.readUInt32BE(20);

const project = {
  meta: { format_version: '5.0', model_format: 'free', box_uv: false },
  name: 'skeleton_dragon',
  model_identifier: 'yknboss:skeleton_dragon',
  visible_box: [12, 12, 0],
  variable_placeholders: '',
  multi_file_ruleset: '',
  variable_placeholder_buttons: [],
  timeline_setups: [],
  unhandled_root_fields: {},
  resolution: { width: textureWidth, height: textureHeight },
  elements,
  groups,
  outliner,
  textures: [{
    name: 'skeleton_dragon', path: '', folder: '', namespace: 'yknboss', id: '0', group: '', scope: 0,
    width: textureWidth, height: textureHeight, uv_width: textureWidth, uv_height: textureHeight,
    particle: false, use_as_default: true, layers_enabled: false, sync_to_project: '', file_format: 'png',
    render_mode: 'default', render_sides: 'double', wrap_mode: 'limited', pbr_channel: 'color', fps: 7,
    frame_time: 1, frame_order_type: 'loop', frame_order: '', frame_interpolate: false, visible: true,
    internal: true, saved: false, uuid: uuid(), source: imageUri
  }]
};

fs.mkdirSync(outputDir, { recursive: true });
fs.writeFileSync(outputPath, JSON.stringify(project));
console.log(JSON.stringify({
  outputPath,
  cubes: elements.filter(element => element.type === 'cube').length,
  bones: groups.length,
  originalLength,
  scale,
  finalLength: originalLength * scale,
  texture: `${textureWidth}x${textureHeight}`
}, null, 2));
