# YknBoss

面向 Paper 1.21.10 及以上版本的 Minecraft RPG Boss 插件，使用 Java 25。当前包含“骨龙”、“烬日冥卫·阿努赫”和“终焉之魔女·伊芙蕾雅”。

## 烬日冥卫·阿努赫

- 模型和 128×128 贴图来自 `modle/endsun`，BetterModel 模型 ID 为 `endsun_anuhe`。
- 已修复 glTF 平面部件的枢轴换算，背部双翼绑定 `Body`，镰刀绑定 `LeftArm`，不会再被旋转到模型外。
- 包含 `idle`、`walk`、玩家手工制作的 `scythe_slash`，以及回斩、审判、冠冕、圣盾、失衡、心炉、日蚀变身、黑日葬仪、六组新技能、出场和死亡等 21 套 BetterModel 动画。
- 日蚀刻度按玩家独立记录；火焰伤害与正面攻击圣盾会增加刻度，绕后破盾、阴影躲审判、击碎图腾和斩灭灵魂倒影可以降低刻度。
- 三阶段完整实现：P1 烈日守门人、50% 生命进入 P2 冥界审判官、20% 生命进入 P3 蚀日真身；P3 自动准备终焉技“黑日葬仪”。
- 黑日葬仪会生成可拾取的盾牌残片；收集后右键组成遮光屏障，或直接击破心炉中断仪式。场地崩塌仅使用伤害区和粒子模拟，不会删除真实方块。
- 新增黄沙送葬、镰影分魂、亡者禁行、日轮坠落和四类法老敕令；场地机制可与冥镰回斩同时存在。
- P3 可释放双生蚀影：烈日之躯仅接受近战伤害，冥夜之影仅接受远程伤害；两者拥有独立名称、模型动画和血条，期间本体其他技能暂停；第一具倒下后必须在 20 秒内击败另一具。
- 索敌采用累计伤害仇恨：优先追击对自身造成总伤害最高的有效玩家，目标失效后自动切换；非施法状态会主动追击而非原地等待。
- 生命、伤害、冷却、日蚀增长、阶段阈值、图腾、灵魂倒影、护盾和终结技参数均可在 `config.yml` 的 `endsun-guard` 中热加载修改。

## 骨龙

- 模型来自 `modle/skeleton-drgaon/source/model.gltf`，保留原 128×128 贴图和完整骨骼结构；使用连接正确的原始骨架，长度约 10 格。
- BetterModel 工程包含 399 个方块、97 个骨骼组、5 个隐藏碰撞箱；不用 Mesh 路径，兼顾 1.21.10 客户端。
- 十六套 Blockbench 动画，包含 `dive_grab`、`skull_barrage`、`bone_prison`、`wing_break`、`ground_idle`、`ground_charge`、`stun` 和 `death`。
- P1 空战使用喷火、召唤、流星、俯冲抓取与颅骨追踪弹；P2 断翼落地后改用冲撞、棘骨牢笼、喷火和 360° 甩尾；P3 全身红光并每 60 秒自动召唤。
- 每颗流星落地前会持续显示与实际伤害半径一致的白色粒子预警圈，命中后立即消失。
- 彩色尘埃粒子会按服务端实际要求自动选择 `Color` 或 `DustOptions` 数据格式；单个 Boss 的 tick 异常也不会再阻断其他 Boss 的 AI。
- 追踪颅骨可被实体方块阻挡，命中玩家后召唤持剑骷髅侍卫；棘骨牢笼会在 10 秒后安全还原原方块。
- 俯冲抓取期间受到任意一次玩家有效伤害便会被打断并坠地眩晕；P1 非抓取状态累计十次受击也会眩晕，默认持续 8 秒。
- 生命、全部技能伤害、冷却、范围、阶段切换无敌时间、眩晕和死亡动画时长均在 `config.yml` 的 `bone-dragon` 中修改。
- 使用 BetterModel 3.4.1；未安装或未加载模型时，骨龙生成指令会直接说明缺少项。

## 多人 Boss 副本

- 必须安装 Multiverse-Core 5.7.3+；骨龙、阿努赫和伊芙蕾雅分别拥有 10 个半径 100 格的普通虚空世界，可供不同队伍同时战斗。
- 三种 Boss 的实例世界统一存放在服务端 `bossmap/` 目录，例如 `bossmap/bone_dragon_arena_01`；旧版平铺世界会在插件启动时安全迁移，世界内有玩家时不会移动。
- 每队默认最多 10 人（可配置）；队长使用 `/yknboss enter bone_dragon` 或 `/yknboss enter endsun_guard` 分配空闲实例并传送在线成员，队员可用同一指令重新进入本队实例。
- `/p invite <玩家ID>` 会在邀请者没有队伍时自动创建队伍；邀请有效期为 60 秒，60 秒内不能再次邀请同一名玩家。
- 队员死亡后等待 10 秒复活；提前点击重生会进入旁观等待。全队都在等待复活时立即挑战失败并返程重置。
- 队长可在副本内使用 `/yknboss giveup` 放弃挑战；队伍人数上限、复活时间和全部玩家提示均可在 `config.yml` 修改。
- 场地内禁止玩家放置、破坏、点燃方块和使用桶，爆炸、活塞及实体也无法改变场地。
- 右键中央不可破坏的对应 Boss 祭坛开始召唤。Boss 死亡 30 秒后队员返回原位置；实例在下一支队伍进入前以固定种子单独重置。
- `/yknboss arena reset` 可在场内无人时手动重置副本。

## 特殊物品

- “彩虹”是固定为 `yknboss:rainbow` 物品模型的独立特殊物品；材质、PDC/NBT 和物品组件不会循环改变。
- 彩虹可装备在头部，佩戴时通过固定物品组件增加 100 点最大生命值。
- 九色变化完全由资源包的动画纹理完成，每帧 10 tick（0.5 秒），服务端不会扫描或替换背包、容器与掉落物。
- 当前没有任何掉落或合成渠道；管理员使用 `/yknboss admin special` 打开目录。
- 点击彩虹进入领取页：左键“获得”领取 1 个，Shift+左键领取 64 个，“返回”回到目录。

## 构建

```powershell
$env:JAVA_HOME = 'C:\java\25'
.\gradlew.bat clean build
```

产物：

- `build/libs/YknBoss-2.0.8-obfuscated.jar`
- `build/distributions/YknBoss-BetterModel-Pack-2.0.8.zip`
- `build/distributions/YknBoss-CraftEngine-Pack-2.0.8.zip`
- `build/distributions/YknBoss-ItemsAdder-Pack-2.0.8.zip`

服务端也需要使用 Java 25 启动。

## 安装（选择一种资源插件）

### CraftEngine

1. 将插件 JAR 放入服务端 `plugins/`。
2. 解压 CraftEngine 资源包，把 ZIP 内的 `yknboss` 文件夹放入 `plugins/CraftEngine/resources/`。
3. 启动服务端或执行 `/ce reload all`，让玩家重新接收资源包。

### ItemsAdder

1. 将插件 JAR 放入服务端 `plugins/`。
2. 将 ItemsAdder 资源包直接解压到 `plugins/ItemsAdder/`，最终路径应为 `plugins/ItemsAdder/contents/yknboss/`。
3. 执行 `/iazip`，按现有托管方式重新下发资源包。

CraftEngine 和 ItemsAdder 都是软依赖，不需要同时安装；目前仅用于下发特殊物品“彩虹”的动态纹理。

### BetterModel（骨龙与烬日冥卫必需）

1. 将用户提供的 `bettermodel-3.4.1-paper.jar` 放入服务端 `plugins/`。
2. 解压 `YknBoss-BetterModel-Pack-2.0.8.zip`，把 `models` 合并到 `plugins/BetterModel/models/`。
3. 启动服务器，或执行 `/bettermodel reload`。
4. 执行 `/yknboss spawn bone_dragon`。

## 指令

| 指令 | 说明 |
| --- | --- |
| `/yknboss spawn bone_dragon` | 在玩家前方上空生成骨龙 |
| `/yknboss spawn endsun_guard` | 在玩家前方生成烬日冥卫·阿努赫 |
| `/yknboss enter bone_dragon` | 分配并进入一个骨龙副本实例 |
| `/yknboss enter endsun_guard` | 分配并进入一个阿努赫副本实例 |
| `/yknboss arena create` | 通过 Multiverse 创建或加载全部 10 个骨龙副本 |
| `/yknboss arena reset` | 场内无人时手动恢复副本初始状态 |
| `/yknboss giveup` | 队长放弃当前副本并触发全队返程重置 |
| `/yknboss spec <玩家ID>` | 以观察者模式进入该玩家正在挑战的骨龙或阿努赫副本 |
| `/yknboss spec leave` | 退出观战并返回原位置 |
| `/yknboss admin special` | 管理员打开特殊物品目录 |
| `/yknboss kill` | 移除 96 格内最近的 Boss |
| `/yknboss kill all` | 移除全部 Boss |
| `/yknboss info` | 查看存活 Boss、血量、阶段与坐标 |
| `/yknboss reload` | 重载配置并更新存活 Boss 属性 |

管理权限：`yknboss.admin`（默认仅 OP）。

队伍指令：`/p invite <玩家ID>`（无队伍时自动建队）、`/party accept`、`/party decline`、`/party remove <玩家ID>`、`/party transfer <玩家ID>`、`/party leave`、`/party disband`、`/party list`、`/party chat <消息>`。队伍权限 `yknboss.party` 默认向所有玩家开放。

Boss 类型写入实体 PDC；服务端重启或区块重新加载后，插件会恢复 BossBar、模型和技能循环。管理指令移除的 Boss 不会产生战利品。
