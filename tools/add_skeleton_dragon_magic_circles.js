const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const projectRoot = path.resolve(__dirname, '..');
const modelPaths = [
  path.join(projectRoot, 'packs', 'bettermodel', 'models', 'skeleton_dragon.bbmodel'),
  path.join(projectRoot, 'modle', 'skeleton-drgaon', 'bettermodel', 'skeleton_dragon.bbmodel')
];

const VERSION = 2;
const ACTIVE_SCALE = 1.35;
const PREFIX = 'yknfx_';
const TEXTURE_PREFIX = 'ykn_fx_';
const FX = [
  'ground_outer',
  'ground_middle',
  'ground_core',
  'mouth_outer',
  'mouth_core',
  'sky_outer',
  'sky_core',
  'soul_orbit',
  'bone_seal'
].map(name => `${PREFIX}${name}`);

function stableUuid(value) {
  const hex = crypto.createHash('sha256').update(`yknboss-magic-circle:${value}`).digest('hex').slice(0, 32).split('');
  hex[12] = '4';
  hex[16] = ((parseInt(hex[16], 16) & 3) | 8).toString(16);
  const joined = hex.join('');
  return `${joined.slice(0, 8)}-${joined.slice(8, 12)}-${joined.slice(12, 16)}-${joined.slice(16, 20)}-${joined.slice(20)}`;
}

function crc32(buffer) {
  let crc = 0xffffffff;
  for (const value of buffer) {
    crc ^= value;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function pngChunk(type, data) {
  const name = Buffer.from(type, 'ascii');
  const size = Buffer.alloc(4);
  size.writeUInt32BE(data.length);
  const checksum = Buffer.alloc(4);
  checksum.writeUInt32BE(crc32(Buffer.concat([name, data])));
  return Buffer.concat([size, name, data, checksum]);
}

function makeRuneTexture(name, color, accent) {
  const size = 16;
  const raw = Buffer.alloc((size * 4 + 1) * size);
  for (let y = 0; y < size; y++) {
    const row = y * (size * 4 + 1);
    raw[row] = 0;
    for (let x = 0; x < size; x++) {
      const index = row + 1 + x * 4;
      const edge = Math.min(x, y, size - 1 - x, size - 1 - y);
      const diagonal = Math.abs(x - y) <= 1 || Math.abs((size - 1 - x) - y) <= 1;
      const glyph = x === 7 || x === 8 || y === 7 || y === 8 || diagonal;
      const mix = glyph ? 0.72 : 0.16 + Math.max(0, edge) * 0.025;
      raw[index] = Math.round(color[0] * (1 - mix) + accent[0] * mix);
      raw[index + 1] = Math.round(color[1] * (1 - mix) + accent[1] * mix);
      raw[index + 2] = Math.round(color[2] * (1 - mix) + accent[2] * mix);
      raw[index + 3] = edge <= 0 ? 0 : (glyph ? 255 : 220);
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  const png = Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    pngChunk('IEND', Buffer.alloc(0))
  ]);
  return {
    path: '',
    name,
    folder: '',
    namespace: '',
    id: '',
    group: 'YknBoss Arcane FX',
    width: size,
    height: size,
    uv_width: size,
    uv_height: size,
    particle: false,
    use_as_default: false,
    layers_enabled: false,
    sync_to_project: '',
    render_mode: 'default',
    render_sides: 'double',
    pbr_channel: 'color',
    frame_time: 1,
    frame_order_type: 'loop',
    frame_order: '',
    frame_interpolate: false,
    visible: true,
    internal: true,
    saved: false,
    uuid: stableUuid(`texture:${name}`),
    source: `data:image/png;base64,${png.toString('base64')}`
  };
}

function faces(texture) {
  return Object.fromEntries(['north', 'east', 'south', 'west', 'up', 'down'].map(side => [side, {
    uv: side === 'up' || side === 'down' ? [16, 0, 0, 16] : [0, 0, 16, 16],
    texture
  }]));
}

function makeCube(name, from, to, origin, rotation, texture) {
  return {
    name,
    box_uv: false,
    render_order: 'default',
    locked: false,
    export: true,
    scope: 0,
    allow_mirror_modeling: true,
    from: from.map(value => Number(value.toFixed(4))),
    to: to.map(value => Number(value.toFixed(4))),
    autouv: 0,
    color: 6,
    origin: origin.map(value => Number(value.toFixed(4))),
    rotation: rotation.map(value => Number(value.toFixed(4))),
    faces: faces(texture),
    type: 'cube',
    uuid: stableUuid(`element:${name}`)
  };
}

function addBar(group, name, center, length, width, thickness, rotation, texture, plane = 'xz') {
  const [x, y, z] = center;
  let from;
  let to;
  if (plane === 'xy') {
    from = [x - length / 2, y - width / 2, z - thickness / 2];
    to = [x + length / 2, y + width / 2, z + thickness / 2];
  } else {
    from = [x - length / 2, y - thickness / 2, z - width / 2];
    to = [x + length / 2, y + thickness / 2, z + width / 2];
  }
  group.elements.push(makeCube(name, from, to, center, rotation, texture));
}

function addRing(group, options) {
  const { name, center, radius, segments, width, thickness, texture, plane = 'xz', broken = false } = options;
  const arc = Math.PI * 2 * radius / segments;
  for (let index = 0; index < segments; index++) {
    if (broken && index % 7 === 3) continue;
    const angle = Math.PI * 2 * index / segments;
    if (plane === 'xy') {
      const point = [center[0] + Math.cos(angle) * radius, center[1] + Math.sin(angle) * radius, center[2]];
      addBar(group, `${name}_${index}`, point, arc * 0.76, width, thickness,
        [0, 0, angle * 180 / Math.PI + 90], texture, plane);
    } else {
      const point = [center[0] + Math.cos(angle) * radius, center[1], center[2] + Math.sin(angle) * radius];
      addBar(group, `${name}_${index}`, point, arc * 0.76, width, thickness,
        [0, -angle * 180 / Math.PI - 90, 0], texture, plane);
    }
  }
}

function addRadialRunes(group, options) {
  const { name, center, radius, count, texture, plane = 'xz', scale = 1 } = options;
  for (let index = 0; index < count; index++) {
    const angle = Math.PI * 2 * index / count;
    if (plane === 'xy') {
      const point = [center[0] + Math.cos(angle) * radius, center[1] + Math.sin(angle) * radius, center[2]];
      addBar(group, `${name}_stem_${index}`, point, 3.9 * scale, 0.75 * scale, 0.32,
        [0, 0, angle * 180 / Math.PI], texture, plane);
      const tip = [center[0] + Math.cos(angle) * (radius + 2.4 * scale), center[1] + Math.sin(angle) * (radius + 2.4 * scale), center[2]];
      addBar(group, `${name}_crown_${index}`, tip, 2.5 * scale, 0.58 * scale, 0.32,
        [0, 0, angle * 180 / Math.PI + 90], texture, plane);
    } else {
      const point = [center[0] + Math.cos(angle) * radius, center[1], center[2] + Math.sin(angle) * radius];
      addBar(group, `${name}_stem_${index}`, point, 3.9 * scale, 0.75 * scale, 0.32,
        [0, -angle * 180 / Math.PI, 0], texture, plane);
      const tip = [center[0] + Math.cos(angle) * (radius + 2.4 * scale), center[1], center[2] + Math.sin(angle) * (radius + 2.4 * scale)];
      addBar(group, `${name}_crown_${index}`, tip, 2.5 * scale, 0.58 * scale, 0.32,
        [0, -angle * 180 / Math.PI - 90, 0], texture, plane);
    }
  }
}

function addStar(group, options) {
  const { name, center, radius, points, texture, plane = 'xz' } = options;
  for (let index = 0; index < points; index++) {
    const angle = 180 * index / points;
    addBar(group, `${name}_${index}`, center, radius * 2, 0.58, 0.30,
      plane === 'xy' ? [0, 0, angle] : [0, -angle, 0], texture, plane);
  }
}

function makeGroup(name, origin) {
  return {
    definition: {
      name,
      uuid: stableUuid(`group:${name}`),
      export: true,
      locked: false,
      scope: 0,
      selected: false,
      _static: { properties: {}, temp_data: {} },
      origin,
      rotation: [0, 0, 0],
      color: 6,
      children: [],
      reset: false,
      shade: false,
      mirror_uv: false,
      visibility: true,
      autouv: 0,
      isOpen: false,
      primary_selected: false
    },
    elements: []
  };
}

function keyframe(animation, bone, channel, time, value, interpolation = 'bezier') {
  return {
    channel,
    data_points: [{ x: String(value[0]), y: String(value[1]), z: String(value[2]) }],
    uuid: stableUuid(`keyframe:${animation}:${bone}:${channel}:${time}:${value.join(',')}`),
    time,
    color: -1,
    interpolation,
    bezier_linked: true,
    bezier_left_time: [-0.08, -0.08, -0.08],
    bezier_left_value: [0, 0, 0],
    bezier_right_time: [0.08, 0.08, 0.08],
    bezier_right_value: [0, 0, 0]
  };
}

function animator(animation, bone, channels) {
  const keyframes = [];
  for (const [channel, frames] of Object.entries(channels)) {
    for (const [time, value, interpolation] of frames) {
      keyframes.push(keyframe(animation, bone, channel, time, value, interpolation));
    }
  }
  return {
    name: bone,
    type: 'bone',
    rotation_global: false,
    quaternion_interpolation: false,
    keyframes
  };
}

function hidden(animation, bone) {
  return animator(animation, bone, { scale: [[0, [0, 0, 0], 'linear']] });
}

function pulse(animation, bone, length, options = {}) {
  const start = options.start ?? 0;
  const appear = options.appear ?? Math.min(length * 0.18, start + 0.42);
  const end = options.end ?? length;
  const requestedHold = options.hold ?? Math.max(appear, end - 0.28);
  const fadeDuration = Math.min(0.85, Math.max(0.60, (end - start) * 0.28));
  const hold = Math.max(appear + 0.18, Math.min(requestedHold, end - fadeDuration));
  const size = (options.size ?? 1) * ACTIVE_SCALE;
  const overshoot = options.overshoot ?? size * 1.08;
  const rotationAxis = options.rotationAxis ?? 'y';
  const turns = options.turns ?? 1;
  const direction = options.direction ?? 1;
  const rotation = [0, 0, 0];
  const finalRotation = [0, 0, 0];
  const axisIndex = rotationAxis === 'x' ? 0 : rotationAxis === 'z' ? 2 : 1;
  finalRotation[axisIndex] = 360 * turns * direction;
  const channels = {
    scale: [
      [start, [0, 0, 0], 'linear'],
      [appear, [overshoot, overshoot, overshoot]],
      [Math.min(hold, appear + 0.18), [size, size, size]],
      [hold, [size, size, size], 'linear'],
      [end, [0, 0, 0], 'linear']
    ],
    rotation: [
      [start, rotation, 'linear'],
      [end, finalRotation, 'linear']
    ]
  };
  if (options.position) channels.position = options.position;
  return animator(animation, bone, channels);
}

function findNode(nodes, uuid) {
  for (const node of nodes) {
    if (!node || typeof node !== 'object') continue;
    if (node.uuid === uuid) return node;
    const nested = findNode(node.children || [], uuid);
    if (nested) return nested;
  }
  return null;
}

function removeNodes(nodes, uuids) {
  return nodes.filter(node => {
    if (typeof node === 'string') return !uuids.has(node);
    if (!node || typeof node !== 'object') return true;
    if (uuids.has(node.uuid)) return false;
    node.children = removeNodes(node.children || [], uuids);
    return true;
  });
}

function installGeometry(model) {
  const oldGroupIds = new Set(model.groups.filter(group => group.name.startsWith(PREFIX)).map(group => group.uuid));
  const oldElementIds = new Set(model.elements.filter(element => element.name.startsWith(PREFIX)).map(element => element.uuid));
  model.groups = model.groups.filter(group => !oldGroupIds.has(group.uuid));
  model.elements = model.elements.filter(element => !oldElementIds.has(element.uuid));
  model.outliner = removeNodes(model.outliner, new Set([...oldGroupIds, ...oldElementIds]));
  for (const animation of model.animations) {
    for (const uuid of oldGroupIds) delete animation.animators[uuid];
  }

  model.textures = model.textures.filter(texture => !texture.name.startsWith(TEXTURE_PREFIX));
  const textureBase = model.textures.length;
  const textures = [
    makeRuneTexture(`${TEXTURE_PREFIX}violet.png`, [116, 25, 218], [255, 224, 255]),
    makeRuneTexture(`${TEXTURE_PREFIX}soul.png`, [34, 188, 218], [225, 255, 255]),
    makeRuneTexture(`${TEXTURE_PREFIX}crimson.png`, [218, 28, 48], [255, 232, 96]),
    makeRuneTexture(`${TEXTURE_PREFIX}bone.png`, [196, 208, 190], [255, 255, 244])
  ];
  textures.forEach((texture, index) => texture.id = String(textureBase + index));
  model.textures.push(...textures);
  const violet = textureBase;
  const soul = textureBase + 1;
  const crimson = textureBase + 2;
  const bone = textureBase + 3;

  const ground = [0, -36.15, 5.2];
  const mouth = [0, -1.4, -49.0];
  const sky = [0, 35.0, 5.2];
  const soulCenter = [0, -8.0, 5.2];
  const groups = Object.fromEntries(FX.map(name => {
    let origin = ground;
    if (name.includes('mouth')) origin = mouth;
    else if (name.includes('sky')) origin = sky;
    else if (name.includes('soul')) origin = soulCenter;
    return [name, makeGroup(name, [...origin])];
  }));

  addRing(groups.yknfx_ground_outer, { name: `${PREFIX}ground_outer_ring`, center: ground, radius: 39, segments: 36, width: 0.9, thickness: 0.34, texture: violet, broken: true });
  addRadialRunes(groups.yknfx_ground_outer, { name: `${PREFIX}ground_outer_rune`, center: ground, radius: 34.5, count: 12, texture: violet, scale: 1.15 });

  addRing(groups.yknfx_ground_middle, { name: `${PREFIX}ground_middle_ring_a`, center: ground, radius: 29, segments: 30, width: 0.72, thickness: 0.32, texture: soul });
  addRing(groups.yknfx_ground_middle, { name: `${PREFIX}ground_middle_ring_b`, center: ground, radius: 24.5, segments: 24, width: 0.55, thickness: 0.30, texture: soul, broken: true });
  addRadialRunes(groups.yknfx_ground_middle, { name: `${PREFIX}ground_middle_rune`, center: ground, radius: 26.8, count: 8, texture: soul, scale: 0.85 });

  addRing(groups.yknfx_ground_core, { name: `${PREFIX}ground_core_ring`, center: ground, radius: 16.5, segments: 24, width: 0.7, thickness: 0.34, texture: crimson });
  addStar(groups.yknfx_ground_core, { name: `${PREFIX}ground_core_star`, center: ground, radius: 14.8, points: 6, texture: crimson });
  addRadialRunes(groups.yknfx_ground_core, { name: `${PREFIX}ground_core_rune`, center: ground, radius: 11.5, count: 6, texture: crimson, scale: 0.65 });

  addRing(groups.yknfx_mouth_outer, { name: `${PREFIX}mouth_outer_ring`, center: mouth, radius: 9.5, segments: 24, width: 0.68, thickness: 0.34, texture: violet, plane: 'xy', broken: true });
  addRadialRunes(groups.yknfx_mouth_outer, { name: `${PREFIX}mouth_outer_rune`, center: mouth, radius: 7.7, count: 8, texture: violet, plane: 'xy', scale: 0.55 });

  addRing(groups.yknfx_mouth_core, { name: `${PREFIX}mouth_core_ring`, center: mouth, radius: 5.4, segments: 18, width: 0.55, thickness: 0.36, texture: crimson, plane: 'xy' });
  addStar(groups.yknfx_mouth_core, { name: `${PREFIX}mouth_core_star`, center: mouth, radius: 4.4, points: 4, texture: crimson, plane: 'xy' });

  addRing(groups.yknfx_sky_outer, { name: `${PREFIX}sky_outer_ring`, center: sky, radius: 35, segments: 36, width: 0.9, thickness: 0.34, texture: violet, broken: true });
  addRadialRunes(groups.yknfx_sky_outer, { name: `${PREFIX}sky_outer_rune`, center: sky, radius: 30.5, count: 12, texture: violet, scale: 1.0 });

  addRing(groups.yknfx_sky_core, { name: `${PREFIX}sky_core_ring_a`, center: sky, radius: 23, segments: 30, width: 0.72, thickness: 0.34, texture: crimson });
  addRing(groups.yknfx_sky_core, { name: `${PREFIX}sky_core_ring_b`, center: sky, radius: 17.5, segments: 24, width: 0.55, thickness: 0.30, texture: soul, broken: true });
  addStar(groups.yknfx_sky_core, { name: `${PREFIX}sky_core_star`, center: sky, radius: 15.8, points: 6, texture: crimson });

  addRing(groups.yknfx_soul_orbit, { name: `${PREFIX}soul_orbit_ring`, center: soulCenter, radius: 18, segments: 24, width: 0.75, thickness: 0.34, texture: soul, broken: true });
  addRadialRunes(groups.yknfx_soul_orbit, { name: `${PREFIX}soul_orbit_rune`, center: soulCenter, radius: 14.8, count: 8, texture: soul, scale: 0.75 });
  addStar(groups.yknfx_soul_orbit, { name: `${PREFIX}soul_orbit_star`, center: soulCenter, radius: 11.5, points: 4, texture: soul });

  addRing(groups.yknfx_bone_seal, { name: `${PREFIX}bone_seal_ring_a`, center: ground, radius: 36, segments: 32, width: 0.95, thickness: 0.36, texture: bone, broken: true });
  addRing(groups.yknfx_bone_seal, { name: `${PREFIX}bone_seal_ring_b`, center: ground, radius: 20, segments: 24, width: 0.72, thickness: 0.34, texture: bone });
  addRadialRunes(groups.yknfx_bone_seal, { name: `${PREFIX}bone_seal_rune`, center: ground, radius: 28, count: 16, texture: bone, scale: 0.9 });

  const all = model.groups.find(group => group.name === 'all');
  const head = model.groups.find(group => group.name === 'head');
  if (!all || !head) throw new Error('Missing all/head group in skeleton dragon model');
  const allNode = findNode(model.outliner, all.uuid);
  const headNode = findNode(model.outliner, head.uuid);
  if (!allNode || !headNode) throw new Error('Missing all/head outliner node in skeleton dragon model');

  for (const [name, group] of Object.entries(groups)) {
    model.groups.push(group.definition);
    model.elements.push(...group.elements);
    const node = {
      uuid: group.definition.uuid,
      isOpen: false,
      children: group.elements.map(element => element.uuid)
    };
    if (name === 'yknfx_mouth_outer' || name === 'yknfx_mouth_core') headNode.children.push(node);
    else allNode.children.push(node);
  }
}

function installAnimations(model) {
  const ids = Object.fromEntries(model.groups.map(group => [group.name, group.uuid]));
  for (const name of FX) if (!ids[name]) throw new Error(`Missing generated FX group ${name}`);

  const effects = {
    spawn: {
      yknfx_ground_outer: { start: 0.0, appear: 0.35, hold: 3.55, turns: 1.4, direction: 1 },
      yknfx_ground_middle: { start: 0.2, appear: 0.65, hold: 3.65, turns: 1.8, direction: -1 },
      yknfx_ground_core: { start: 0.55, appear: 1.0, hold: 3.75, turns: 1.0, direction: 1 },
      yknfx_sky_outer: { start: 0.65, appear: 1.2, hold: 3.45, turns: 1.35, direction: -1, position: [[0.65, [0, 24, 0]], [1.35, [0, 0, 0]], [3.45, [0, 0, 0]], [4.0, [0, -10, 0]]] },
      yknfx_sky_core: { start: 0.85, appear: 1.35, hold: 3.5, turns: 1.7, direction: 1 }
    },
    summon: {
      yknfx_ground_outer: { start: 0.0, appear: 0.32, hold: 2.12, turns: 1.2, direction: 1 },
      yknfx_ground_middle: { start: 0.14, appear: 0.52, hold: 2.2, turns: 1.5, direction: -1 },
      yknfx_ground_core: { start: 0.35, appear: 0.72, hold: 2.25, turns: 0.8, direction: 1 },
      yknfx_soul_orbit: { start: 0.5, appear: 0.86, hold: 2.15, turns: 2.2, direction: -1, size: 0.88 }
    },
    death_summon: {
      yknfx_ground_outer: { start: 0.0, appear: 0.25, hold: 2.15, turns: 1.7, direction: -1, size: 1.1 },
      yknfx_ground_middle: { start: 0.08, appear: 0.42, hold: 2.2, turns: 2.0, direction: 1 },
      yknfx_ground_core: { start: 0.28, appear: 0.62, hold: 2.25, turns: 1.2, direction: -1, size: 1.08 },
      yknfx_soul_orbit: { start: 0.36, appear: 0.68, hold: 2.18, turns: 3.0, direction: 1, size: 1.15 },
      yknfx_sky_core: { start: 0.55, appear: 0.92, hold: 2.12, turns: 2.2, direction: -1, size: 0.7 }
    },
    skeleton_absorb: {
      yknfx_soul_orbit: { start: 0.0, appear: 0.2, hold: 2.15, turns: 4.0, direction: 1, size: 1.35, end: 2.4,
        position: [[0, [0, 0, 0]], [1.75, [0, 7, 0]], [2.4, [0, 10, 0]]] },
      yknfx_ground_middle: { start: 0.0, appear: 0.3, hold: 2.1, turns: 2.6, direction: -1, size: 1.0 },
      yknfx_ground_core: { start: 0.55, appear: 0.9, hold: 2.18, turns: 1.8, direction: 1, size: 0.78 }
    },
    skull_barrage: {
      yknfx_mouth_outer: { start: 0.3, appear: 0.72, hold: 2.72, turns: 2.8, direction: 1, size: 1.15, rotationAxis: 'z' },
      yknfx_mouth_core: { start: 0.58, appear: 0.95, hold: 2.8, turns: 3.4, direction: -1, size: 1.0, rotationAxis: 'z' },
      yknfx_soul_orbit: { start: 0.4, appear: 0.9, hold: 2.55, turns: 2.0, direction: -1, size: 0.72 }
    },
    fire_breath: {
      yknfx_mouth_outer: { start: 0.18, appear: 0.62, hold: 4.72, turns: 3.2, direction: -1, size: 1.0, rotationAxis: 'z' },
      yknfx_mouth_core: { start: 0.38, appear: 0.82, hold: 4.8, turns: 5.0, direction: 1, size: 1.18, rotationAxis: 'z' },
      yknfx_ground_core: { start: 0.55, appear: 1.05, hold: 4.55, turns: 1.4, direction: -1, size: 0.65 }
    },
    meteor: {
      yknfx_sky_outer: { start: 0.0, appear: 0.45, hold: 2.72, turns: 2.0, direction: -1, size: 1.18,
        position: [[0, [0, 18, 0]], [0.65, [0, 0, 0]], [2.55, [0, 0, 0]], [3.0, [0, -8, 0]]] },
      yknfx_sky_core: { start: 0.22, appear: 0.72, hold: 2.78, turns: 2.8, direction: 1, size: 1.05 },
      yknfx_ground_outer: { start: 0.5, appear: 0.95, hold: 2.65, turns: 1.0, direction: 1, size: 0.82 }
    },
    bone_prison: {
      yknfx_bone_seal: { start: 0.0, appear: 0.42, hold: 1.92, turns: 1.4, direction: 1, size: 1.05 },
      yknfx_ground_middle: { start: 0.18, appear: 0.62, hold: 2.0, turns: 1.8, direction: -1, size: 0.88 },
      yknfx_ground_core: { start: 0.42, appear: 0.82, hold: 2.05, turns: 0.75, direction: 1, size: 0.72 }
    },
    bone_spikes: {
      yknfx_bone_seal: { start: 0.0, appear: 0.35, hold: 2.75, turns: 2.0, direction: -1, size: 1.18 },
      yknfx_ground_outer: { start: 0.14, appear: 0.58, hold: 2.78, turns: 1.5, direction: 1, size: 1.0 },
      yknfx_ground_middle: { start: 0.32, appear: 0.72, hold: 2.82, turns: 2.2, direction: -1, size: 0.92 }
    },
    death: {
      yknfx_soul_orbit: { start: 0.0, appear: 0.35, hold: 3.8, end: 4.65, turns: 5.0, direction: 1, size: 1.45,
        position: [[0, [0, 0, 0]], [3.3, [0, 20, 0]], [4.65, [0, 34, 0]]] },
      yknfx_ground_outer: { start: 0.1, appear: 0.65, hold: 3.65, end: 4.45, turns: 2.0, direction: -1, size: 1.1 },
      yknfx_ground_core: { start: 0.45, appear: 1.0, hold: 3.45, end: 4.2, turns: 1.3, direction: 1, size: 0.9 }
    }
  };

  for (const animation of model.animations) {
    const definitions = effects[animation.name] || {};
    for (const name of FX) {
      const definition = definitions[name];
      animation.animators[ids[name]] = definition
        ? pulse(animation.name, name, animation.length, definition)
        : hidden(animation.name, name);
    }
  }
}

for (const modelPath of modelPaths) {
  const model = JSON.parse(fs.readFileSync(modelPath, 'utf8'));
  installGeometry(model);
  installAnimations(model);
  model.yknboss_magic_circle_version = VERSION;
  fs.writeFileSync(modelPath, JSON.stringify(model));
  const effectElements = model.elements.filter(element => element.name.startsWith(PREFIX)).length;
  console.log(`Installed BetterModel magic circles v${VERSION}: ${modelPath} (${effectElements} FX cubes)`);
}

// The second path is an editable Blockbench mirror of the distributed model.
// Keep it byte-for-byte identical so future animation patches cannot drift.
fs.copyFileSync(modelPaths[0], modelPaths[1]);
