const fs = require("fs");

const MODEL_PATHS = [
  "packs/bettermodel/models/skeleton_dragon.bbmodel",
  "modle/skeleton-drgaon/bettermodel/skeleton_dragon.bbmodel",
];

// Do not scale individual cubes or pivots. BetterModel must scale the complete
// tracker so every parent/child joint keeps the original connected transform.
for (const path of MODEL_PATHS) {
  const model = JSON.parse(fs.readFileSync(path, "utf8"));
  if (model.yknboss_model_base_length_blocks !== 10) {
    throw new Error(`${path}: run restore_skeleton_dragon_base_scale.js first`);
  }
  model.yknboss_runtime_scale = 1;
  fs.writeFileSync(path, `${JSON.stringify(model)}\n`, "utf8");
  console.log(`${path}: kept connected ten-block base geometry; runtime scale is 1`);
}
