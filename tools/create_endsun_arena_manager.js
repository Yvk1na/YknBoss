const fs = require('fs');
const path = require('path');

const source = path.resolve(__dirname, '../src/main/java/cn/yknboss/arena/BoneDragonArenaManager.java');
const target = path.resolve(__dirname, '../src/main/java/cn/yknboss/arena/EndsunGuardArenaManager.java');
let text = fs.readFileSync(source, 'utf8');

const replacements = [
  ['BoneDragonArenaManager', 'EndsunGuardArenaManager'],
  ['BoneDragonArenaSettings', 'EndsunGuardArenaSettings'],
  ['BoneDragon', 'EndsunGuard'],
  ['boneDragonManager()', 'endsunGuardManager()'],
  ['bone_dragon_arena', 'endsun_guard_arena'],
  ['arena_generation', 'endsun_arena_generation'],
  ['"arena.', '"endsun-arena.'],
  ['骨龙竞技场', '烬日冥卫竞技场'],
  ['骨龙副本', '烬日冥卫副本'],
  ['骨龙石碑', '烬日祭坛'],
  ['骨龙', '烬日冥卫·阿努赫'],
];
for (const [from, to] of replacements) text = text.replaceAll(from, to);
text = text.replace('private static final int GENERATION_VERSION = 4;',
  'private static final int GENERATION_VERSION = 1;');
text = text.replace('return new Location(world, 0.5, settings.floorY() + 7.0, 0.5, 0.0f, 0.0f);',
  'return new Location(world, 0.5, settings.floorY() + 4.0, 0.5, 0.0f, 0.0f);');

fs.writeFileSync(target, text);
