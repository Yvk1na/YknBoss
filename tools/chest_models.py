"""Rebuild and preview the three raid chests. Requires Pillow."""
from __future__ import annotations

import argparse
import base64
import copy
import hashlib
from io import BytesIO
import json
from pathlib import Path
import math
import uuid
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
TIERS = ("copper", "silver", "gold")


def uid(name):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "yknboss/raid-chest/"+name))


def split_at(element, seam):
    """Split a crossing cube without changing any exterior face or its UV mapping."""
    lower, upper = copy.deepcopy(element), copy.deepcopy(element)
    upper["uuid"] = uid(element["uuid"]+"/lid")
    upper["name"] += "_lid"
    lower["to"][1] = upper["from"][1] = seam
    ratio = (element["to"][1]-seam)/(element["to"][1]-element["from"][1])
    for side in ("north", "south", "east", "west"):
        face = element["faces"][side]
        assert not face.get("rotation", 0), "Rotated UV split needs explicit handling"
        u,v,U,V = face["uv"]
        cut = v+(V-v)*ratio
        lower["faces"][side]["uv"] = [u,cut,U,V]
        upper["faces"][side]["uv"] = [u,v,U,cut]
    # These two cut faces were inside the original solid. Leave the mouth hollow.
    lower["faces"]["up"]["texture"] = None
    upper["faces"]["down"]["texture"] = None
    return lower, upper


def treasure_texture(source, tier):
    palette = {
        "copper": ("#ffd38b", "#ed9a42", "#b86b2f", "#fff0ba", "#ffcb66"),
        "silver": ("#efffff", "#b8e9ff", "#79adc4", "#ffffff", "#86f5ef"),
        "gold": ("#fff294", "#ffd24a", "#c78e26", "#fffbd0", "#ff6666"),
    }[tier]
    image = Image.open(BytesIO(base64.b64decode(source["textures"][0]["source"].split(",")[1]))).convert("RGBA")
    used = set()
    for element in source["elements"]:
        for face in element.get("faces", {}).values():
            if face.get("texture") is None:
                continue
            u,v,U,V = face["uv"]
            used.update((x,y) for y in range(math.floor(min(v,V)),math.ceil(max(v,V)))
                        for x in range(math.floor(min(u,U)),math.ceil(max(u,U))))
    def reserve(size):
        for y in range(image.height-size+1):
            for x in range(image.width-size+1):
                area = {(a,b) for b in range(y,y+size) for a in range(x,x+size)}
                if not (area & used) and all(image.getpixel(p)[3] == 0 for p in area):
                    used.update(area)
                    return [x,y,x+size,y+size]
        raise AssertionError(f"No unused transparent atlas area for {tier}/{size}")
    coin_uv, gem_uv = reserve(8), reserve(4)
    coins = Image.new("RGBA", (8,8), palette[2])
    draw = ImageDraw.Draw(coins)
    for y in range(0,8,4):
        for x in range((y//4%2)*2-2,8,4):
            draw.rectangle((max(0,x),y,min(7,x+3),y+2), fill=palette[1])
            draw.line((max(0,x),y,min(7,x+2),y), fill=palette[0])
            if x >= 0:
                draw.point((x,y+1), fill=palette[3])
    gem = Image.new("RGBA", (4,4), palette[4])
    ImageDraw.Draw(gem).line((0,0,2,0), fill=palette[3])
    image.paste(coins, tuple(coin_uv[:2]))
    image.paste(gem, tuple(gem_uv[:2]))
    encoded = BytesIO()
    image.save(encoded, format="PNG")
    return "data:image/png;base64,"+base64.b64encode(encoded.getvalue()).decode(), coin_uv, gem_uv


def generate(source, tier):
    model = copy.deepcopy(source)
    texture, coin_uv, gem_uv = treasure_texture(source, tier)
    seam = 17 if tier == "gold" else 9
    hinge = [0,seam,11] if tier == "gold" else [10.5,seam,0]
    base, lid = [], []
    for index, original in enumerate(source["elements"]):
        element = copy.deepcopy(original)
        # Whole locks hang below the seam but must travel with the lid.
        lock = index in ({0,1} if tier == "gold" else {0})
        if lock or element["from"][1] >= seam:
            lid.append(element)
        elif element["to"][1] <= seam:
            if tier == "gold" and index == 14:
                # Retain the exposed lip exactly, cutting only the interior under the lid.
                element["faces"]["up"]["texture"] = None
                for i,(x,z,X,Z) in enumerate(((-12,-11,-10,11),(10,-11,12,11),(-10,-11,10,-9),(-10,9,10,11))):
                    rim = copy.deepcopy(original)
                    rim.update(name=f"mouth_rim_{i}", uuid=uid(f"gold/rim/{i}"),
                               **{"from": [x,seam,z], "to": [X,seam,Z]})
                    rim["faces"] = {"up": {"uv": [40+x+12,z+11,40+X+12,Z+11], "texture": 0}}
                    base.append(rim)
                # The old solid top becomes the lid's inner lining. In the closed pose
                # it also seals the original atlas's tiny transparent lid details.
                lining = copy.deepcopy(original)
                lining.update(name="lid_lining", uuid=uid("gold/lid_lining"),
                              **{"from": [-10,seam,-9], "to": [10,seam,9]})
                lining["faces"] = {"up": {"uv": [42,2,62,20], "texture": 0},
                                   "down": {"uv": [42,2,62,20], "texture": 0}}
                lid.append(lining)
            base.append(element)
        else:
            assert not any(element.get("rotation", [0,0,0]))
            lower, upper = split_at(element, seam)
            base.append(lower)
            lid.append(upper)
    treasure = []
    def cube(name, lower, upper, uv):
        treasure.append({"name": "treasure_"+name, "type": "cube", "uuid": uid(tier+"/"+name),
                         "from": lower, "to": upper, "origin": [0,0,0], "rotation": [0,0,0],
                         "light_emission": 15,
                         "visibility": True, "export": True, "box_uv": False,
                         "faces": {s: {"uv": uv, "texture": 0} for s in ("north","south","east","west","up","down")}})
    half_x,half_z = (9,8) if tier == "gold" else (6,7)
    center_x = 0 if tier == "gold" else .5
    cube("coins", [center_x-half_x,seam-1.8,-half_z], [center_x+half_x,seam-1.6,half_z], coin_uv)
    for i,(x,z) in enumerate(((-3,-3),(2,2),(4,-2))):
        cube(f"gem_{i}", [x-.8,seam-1.6,z-.8], [x+.8,seam-1,z+.8], gem_uv)
    model["elements"] = base+lid+treasure
    model["groups"] = [{"name": name, "uuid": uid(tier+"/"+name), "origin": origin,
                        "rotation": [0,0,0], "export": True, "visibility": True,
                        "light_emission": 15 if name == "treasure" else 0}
                       for name,origin in (("base",[0,0,0]),("lid",hinge),("treasure",[0,0,0]))]
    base_id,lid_id,treasure_id = [g["uuid"] for g in model["groups"]]
    model["outliner"] = [
        {"uuid": base_id, "isOpen": True, "children": [e["uuid"] for e in base]+[
            {"uuid": treasure_id, "isOpen": True, "children": [e["uuid"] for e in treasure]}]},
        {"uuid": lid_id, "isOpen": True, "children": [e["uuid"] for e in lid]},
    ]
    model["textures"][0]["source"] = texture
    axis = "x" if tier == "gold" else "z"
    frames = []
    for time,angle in ((0,0),(.15,65),(.3,95),(.5,105)):
        point = dict(x="0",y="0",z="0")
        point[axis] = str(angle if tier == "gold" else -angle)
        frames.append({"uuid": uid(f"{tier}/frame/{time}"), "channel": "rotation", "time": time,
                       "data_points": [point], "interpolation": "linear"})
    model["animations"] = [{"uuid": uid(tier+"/open"), "name": "open", "loop": "hold", "override": False,
                            "length": .5, "snapping": 20, "animators": {
                                lid_id: {"name": "lid", "type": "bone", "rotation_global": False, "keyframes": frames}}}]
    return model


def rotate(point, angles):
    x, y, z = map(math.radians, angles)
    a, b, c = point
    b, c = b*math.cos(x)-c*math.sin(x), b*math.sin(x)+c*math.cos(x)
    a, c = a*math.cos(y)+c*math.sin(y), -a*math.sin(y)+c*math.cos(y)
    return (a*math.cos(z)-b*math.sin(z), a*math.sin(z)+b*math.cos(z), c)


def around(point, origin, angles):
    return tuple(a+b for a,b in zip(rotate(tuple(a-b for a,b in zip(point, origin)), angles), origin))


def faces(element):
    x, y, z = element["from"]
    X, Y, Z = element["to"]
    return {
        "north": [(X, Y, z), (x, Y, z), (x, y, z), (X, y, z)],
        "south": [(x, Y, Z), (X, Y, Z), (X, y, Z), (x, y, Z)],
        "west": [(x, Y, z), (x, Y, Z), (x, y, Z), (x, y, z)],
        "east": [(X, Y, Z), (X, Y, z), (X, y, z), (X, y, Z)],
        "up": [(x, Y, z), (X, Y, z), (X, Y, Z), (x, Y, Z)],
        "down": [(x, y, Z), (X, y, Z), (X, y, z), (x, y, z)],
    }


def render(model, angle=0, yaw=0):
    """Orthographic textured z-buffer, including element and animated parent rotations."""
    width, height = 640, 600
    canvas = Image.new("RGBA", (width, height), (24, 29, 39, 255))
    pixels = canvas.load()
    depth = [[-math.inf]*width for _ in range(height)]
    norm = math.sqrt(2+.9**2)
    toward = rotate((-1/norm, .9/norm, -1/norm), (0,yaw,0))
    right = rotate((-1/math.sqrt(2), 0, 1/math.sqrt(2)), (0,yaw,0))
    up = (toward[1]*right[2], toward[2]*right[0]-toward[0]*right[2], -toward[1]*right[0])
    camera = (right, tuple(-a for a in up), toward)
    textures = [Image.open(BytesIO(base64.b64decode(t["source"].split(",")[1]))).convert("RGBA")
                for t in model["textures"]]
    groups = {g["name"]: g for g in model["groups"]}
    lid = groups.get("lid")
    lid_ids = set()
    for node in model["outliner"]:
        if lid and node["uuid"] == lid["uuid"]:
            lid_ids.update(c for c in node["children"] if isinstance(c, str))
    axis = [0,0,0]
    if lid and angle:
        point = model["animations"][0]["animators"][lid["uuid"]]["keyframes"][-1]["data_points"][0]
        axis = [float(point[c])*angle/105 for c in ("x","y","z")]
    for element in model["elements"]:
        local = element.get("rotation", [0, 0, 0])
        origin = element.get("origin", [0, 0, 0])
        for side, points in faces(element).items():
            face = element.get("faces", {}).get(side)
            if not face or face.get("texture") is None:
                continue
            vertices = [around(p, origin, local) for p in points]
            if element["uuid"] in lid_ids:
                vertices = [around(p, lid["origin"], axis) for p in vertices]
            # Minecraft cube faces are one-sided. Our vertex order gives inward normals.
            a,b = [tuple(vertices[i][j]-vertices[0][j] for j in range(3)) for i in (1,2)]
            normal = (a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])
            if sum(a*b for a,b in zip(normal,toward)) >= -1e-8:
                continue
            projected = [tuple(sum(a*b for a,b in zip(p,row)) for row in camera) for p in vertices]
            projected = [(x*9+width/2, y*9+height*.8, z) for x,y,z in projected]
            u, v, U, V = face["uv"]
            uv = [(u,v), (U,v), (U,V), (u,V)]
            turns = face.get("rotation", 0) // 90
            uv = uv[turns:]+uv[:turns]
            texture = textures[int(face["texture"])]
            samples = texture.load()
            shade = 1.0 if element.get("name", "").startswith("treasure_") else {
                "up": 1, "down": .55, "north": .78, "south": .78, "west": .88, "east": .88}[side]
            for indices in ((0, 1, 2), (0, 2, 3)):
                a, b, c = [projected[i] for i in indices]
                lo = [max(0, math.floor(min(a[i],b[i],c[i]))) for i in (0,1)]
                hi = [min(size-1, math.ceil(max(a[i],b[i],c[i]))) for i,size in enumerate((width,height))]
                denominator = (b[1]-c[1])*(a[0]-c[0]) + (c[0]-b[0])*(a[1]-c[1])
                if abs(denominator) < 1e-8:
                    continue
                ta,tb,tc = [uv[i] for i in indices]
                for yy in range(lo[1],hi[1]+1):
                    for xx in range(lo[0],hi[0]+1):
                        p = ((b[1]-c[1])*(xx+.5-c[0])+(c[0]-b[0])*(yy+.5-c[1]))/denominator
                        q = ((c[1]-a[1])*(xx+.5-c[0])+(a[0]-c[0])*(yy+.5-c[1]))/denominator
                        r = 1-p-q
                        if min(p,q,r) < -1e-8:
                            continue
                        z = p*a[2]+q*b[2]+r*c[2]
                        if z < depth[yy][xx]-1e-6:
                            continue
                        tx = max(0,min(texture.width-1,math.floor(p*ta[0]+q*tb[0]+r*tc[0]+1e-9)))
                        ty = max(0,min(texture.height-1,math.floor(p*ta[1]+q*tb[1]+r*tc[1]+1e-9)))
                        color = samples[tx,ty]
                        if color[3] >= 128:
                            pixels[xx,yy] = tuple(int(v*shade) for v in color[:3])+(255,)
                            depth[yy][xx] = z
    return canvas


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preview-only", action="store_true")
    parser.add_argument("--check", action="store_true", help="Check committed outputs instead of writing models")
    args = parser.parse_args()
    output = ROOT / "build/preview"
    output.mkdir(parents=True, exist_ok=True)
    report = {}
    sheet = Image.new("RGBA", (1280,3*600), (24,29,39,255))
    for row,tier in enumerate(TIERS):
        path = ROOT / f"packs/bettermodel/models/yknboss_chest_{tier}.bbmodel"
        source_path = ROOT / f"tools/chest_sources/{tier}.bbmodel"
        source = json.loads(source_path.read_text(encoding="utf-8-sig"))
        model = generate(source, tier)
        if args.check or args.preview_only:
            assert json.loads(path.read_text(encoding="utf-8-sig")) == model, f"Stale model: {path}"
        changes = []
        for yaw in (0,90,180,270):
            before,after = render(source,yaw=yaw),render(model,yaw=yaw)
            a,b = before.tobytes(),after.tobytes()
            changed = sum(a[i:i+4] != b[i:i+4] for i in range(0,len(a),4))
            changes.append(changed)
            assert changed == 0, (tier,yaw,changed)
        if not (args.check or args.preview_only):
            path.write_text(json.dumps(model,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        closed,opened = render(model),render(model,105)
        closed.save(output / f"chest-{tier}-closed.png")
        opened.save(output / f"chest-{tier}-open.png")
        for column,picture in enumerate((closed,opened)):
            sheet.paste(picture,(640*column,600*row))
            ImageDraw.Draw(sheet).text((640*column+16,600*row+16),tier.upper()+(' / OPEN 105 deg' if column else ' / CLOSED'),fill='white')
        report[tier] = {"closed_changed_pixels_four_views": changes,
                        "source_sha256": hashlib.sha256(source_path.read_bytes()).hexdigest(),
                        "source_elements": len(source["elements"]), "generated_elements": len(model["elements"]),
                        "lid_pivot": model["groups"][1]["origin"], "treasure_light_emission": 15}
    sheet.save(output / "chests-closed-open.png")
    (output / "chest-model-validation.json").write_text(json.dumps(report,indent=2),encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
