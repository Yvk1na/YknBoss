const fs = require("fs");

const MODEL_PATHS = [
  "packs/bettermodel/models/skeleton_dragon.bbmodel",
  "modle/skeleton-drgaon/bettermodel/skeleton_dragon.bbmodel",
];

function divideVector(value) {
  if (!Array.isArray(value)) return;
  for (let index = 0; index < Math.min(3, value.length); index++) {
    if (typeof value[index] === "number") value[index] /= 2;
  }
}

function divideOutliner(nodes) {
  for (const node of nodes || []) {
    divideVector(node.origin);
    divideOutliner(node.children);
  }
}

function dividePositionAnimations(animations) {
  for (const animation of animations || []) {
    for (const animator of Object.values(animation.animators || {})) {
      for (const keyframe of animator.keyframes || []) {
        if (keyframe.channel !== "position") continue;
        for (const point of keyframe.data_points || []) {
          for (const axis of ["x", "y", "z"]) {
            if (typeof point[axis] === "number") point[axis] /= 2;
            else if (typeof point[axis] === "string" && Number.isFinite(Number(point[axis]))) {
              point[axis] = String(Number(point[axis]) / 2);
            }
          }
        }
      }
    }
  }
}

for (const path of MODEL_PATHS) {
  const model = JSON.parse(fs.readFileSync(path, "utf8"));
  if (model.yknboss_model_base_length_blocks === 10) {
    console.log(`${path}: already restored to connected base geometry`);
    continue;
  }
  if (model.yknboss_model_length_blocks !== 20) {
    throw new Error(`${path}: expected the previous 20-block geometry marker`);
  }

  for (const element of model.elements || []) {
    divideVector(element.from);
    divideVector(element.to);
    divideVector(element.origin);
  }
  divideOutliner(model.outliner);
  dividePositionAnimations(model.animations);
  if (Array.isArray(model.visible_box)) model.visible_box = model.visible_box.map(value => value / 2);

  delete model.yknboss_model_length_blocks;
  delete model.yknboss_position_animation_scale_version;
  model.yknboss_model_base_length_blocks = 10;
  model.yknboss_runtime_scale = 2;
  fs.writeFileSync(path, `${JSON.stringify(model)}\n`, "utf8");
  console.log(`${path}: restored connected base geometry; BetterModel runtime scale is 2`);
}
