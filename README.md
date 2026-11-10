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
- 每队及每个副本最多 6 人；队长使用 `/yknboss enter bone_dragon` 或 `/yknboss enter endsun_guard` 分配空闲实例并传送在线成员，队员可用同一指令重新进入本队实例。
- `/p invite <玩家ID>` 会在邀请者没有队伍时自动创建队伍；邀请有效期为 60 秒，60 秒内不能再次邀请同一名玩家。
- 队员死亡后等待 10 秒复活；提前点击重生会进入旁观等待。全队都在等待复活时立即挑战失败并返程重置。
- 队长可在副本内使用 `/yknboss leave` 放弃挑战；队伍人数上限、复活时间和全部玩家提示均可在 `config.yml` 修改。
- 场地内禁止玩家放置、破坏、点燃方块和使用桶，爆炸、活塞及实体也无法改变场地。
- 右键中央不可破坏的对应 Boss 祭坛开始召唤。Boss 死亡 30 秒后队员返回原位置；实例在下一支队伍进入前以固定种子单独重置。
- 进入副本后右侧会切换为专用挑战面板，原样保留进入前 Skyblock 面板的标题、顶部日期/服务器标识和底部地址，并显示剩余时间、团队近 10 秒 DPS，以及最多 6 名队员的当前生命和个人 DPS；离开、掉线、结算或挑战失败后自动恢复进入前的计分板。
- v2.0.17 按重做后的 AuraSkills 重新校准：骨龙、阿努赫、伊芙蕾雅分别以 50/80/100 级玩家的最终 DPS 158/286/639 为中心，1~6 人采用线性血量倍率，正常流程目标均约 7 分钟；六人倍率下三个 Boss 的每个阶段实体血量仍不超过 500000。
- `/yknboss arena reset` 可在场内无人时手动重置副本。
- Boss 显式技能仍经过 YknRpg 防御、AuraSkills 韧性/盾御/免疫、讨伐抗性、职业减伤和原版护甲，但不会再误触发仅面向普通怪物的 `Mob Master` 减伤；普通攻击和召唤物攻击不受此规则影响。

## 通关宝箱

- Boss 被击败后，场地中央会出现一个 BetterModel 宝箱（模型 `yknboss_chest_copper` / `yknboss_chest_silver` / `yknboss_chest_gold`）；未加载模型时改用原版箱子外观。
- 品质由通关用时（Boss 出现到被击杀）决定：默认 5:30 内为金宝箱，7:30 内为银宝箱，其余为铜宝箱；各 Boss 的时限在 `raid-loot.time-limits` 单独配置。
- 银宝箱使用 `raid-loot.tables` 中的原始概率；金、铜宝箱按 `raid-loot.multipliers` 对基础素材、核心素材、稀有物品、锻造道具分别乘以倍率。
- 每名合格成员（伤害不低于人均伤害的 30%）各自独立掷骰，概率不随队伍人数变化；核心素材带保底，计数写入玩家 PDC。
- 右键宝箱打开领取界面：四个奖励格、“领取奖励”与“关闭”按钮，其余为淡灰色玻璃。离开副本时未领取的奖励自动发放，离线成员下次上线补发（`pending-loot.yml`）。
- 掉落物来自 MythicMobs 的 YknRpg 首领素材（`mythic:YKI_*`）、首领护符（`eco:talismans:boss_*`）及 YknAnvil 1.2.2 的辅助道具（`yknanvil:luckstone` / `yknanvil:charm` / `yknanvil:scroll`），均为软依赖；物品不存在或来源未启用时跳过，按物品警告一次，重载后重新检查。
- `yknanvil:` 通过反射调用启用插件的 `createItem(String,int)`，以数量 1 创建模板并沿用缓存、逐次克隆发奖；物品与 `/yknanvil give` 相同。未知 ID 或接口异常沿用其他来源的跳过逻辑，不影响其余奖励。
- 四格超出时依次保留 `core → rare → forge → base`，同类保留原始概率较低的物品；`guarantee-one-base` 仍仅在没有任何奖励时从 base 保底，不抽锻造道具。
- 默认银宝箱幸运石/保护符/守护卷轴概率：骨龙 **25% / 5% / 3%**，阿努赫 **30% / 6% / 4%**，伊芙蕾雅 **35% / 7% / 5%**；均为数量 1、无单项保底。铜/银/金宝箱的 forge 倍率分别 **0.5 / 1.0 / 1.5**，旧配置缺项按 1.0。已有配置不自动改写，请将默认配置的九个新条目及倍率合并到自定义掉落表。
- 首名有奖励的玩家实际打开领取界面后，箱盖在 0.5 秒内后翻 105° 并保持，直到副本结算移除；被取消或替换的 GUI 不触发。后续右键不重复动画、开盖音效和爆发粒子。原版外观回退仍播放音效与粒子。
- 铜箱开启后有电火花与间歇火苗，银箱有白色上升光点和烟花闪点，金箱有 `#FFD24A` 细光柱、白色光点与间歇图腾星光。复用插件现有 tick，每 5 tick 检查一次，仅同世界 32 格内有玩家时生成；移除会话后立即停止。
- `treasure` 子骨骼在箱口下方 1–2 像素，使用原 64×64 贴图未使用的透明区域绘制金币和宝石。BetterModel 3.4.1 支持骨骼亮度：模型 `light_emission: 15`，运行时另对该骨骼及子节点设置 block/sky 亮度 15；这是宝物自身满亮，不会照亮世界方块。
- 音效列表在 `raid-loot.sounds.open` 与 `raid-loot.sounds.claim.<copper|silver|gold>`，每项为 `{sound, volume, pitch}`。开盖为箱子声 + 末影箱声，领取按铜/银/金分别为拾取 + 铃声、紫水晶 + 升级、挑战完成 + 烟花。缺键使用代码默认值，显式 `[]` 静音，无效音效名警告一次并跳过。直接领取、在线自动发放及上线补发都仅对领取者播放对应品质音效。
- 新版 `pending-loot.yml` 按批次保存品质，支持一名玩家累积不同品质。旧版只有物品、无法还原品质的记录仍完整补发，使用银箱音效。

### 宝箱模型制作与验证

原始闭合模型保存在 `tools/chest_sources/*.bbmodel`；运行 `python -X utf8 tools/chest_models.py` 可重复生成三个打包模型和 `build/preview/chest-{copper,silver,gold}-{closed,open}.png`，总览为 `build/preview/chests-closed-open.png`。需要 Pillow，无需其它 Python 包。

铜、银箱锁扣朝 −X，铰链为 `(10.5, 9, 0)`，沿 Z 轴向后翻；金箱锁扣朝 −Z，铰链为 `(0, 17, 11)`，沿 X 轴向后翻。跨越开口的原始方块沿箱口裁切并保持 UV 连续，金箱原实心顶面拆成箱沿和随盖移动的内衬。构建会验证生成结果无过期、原模型与新模型闭合状态四个视角均为 0 像素差异，并通过实际 BetterModel 3.4.1 解析器检查骨骼、满亮度与 hold 动画。完整检查仍只需 `deploy.cmd -BuildOnly`。

## 特殊物品

- “彩虹”是固定为 `yknboss:rainbow` 物品模型的独立特殊物品；材质、PDC/NBT 和物品组件不会循环改变。
- 彩虹可装备在头部，佩戴时通过固定物品组件增加 100 点最大生命值。
- 九色变化完全由资源包的动画纹理完成，每帧 10 tick（0.5 秒），服务端不会扫描或替换背包、容器与掉落物。
- 当前没有任何掉落或合成渠道；管理员使用 `/yknboss admin special` 打开目录。
- 点击彩虹进入领取页：左键“获得”领取 1 个，Shift+左键领取 64 个，“返回”回到目录。

## 构建

```powershell
.\deploy.cmd -BuildOnly
```

入口固定 JDK 25 与仓库内 `.gradle-user-home`，依次运行 clean、JUnit 测试、编译、混淆、资源打包及发布产物校验；`-BuildOnly` 不操作服务端。

产物：

- `build/libs/YknBoss-2.0.20-obfuscated.jar`
- `build/distributions/YknBoss-BetterModel-Pack-2.0.20.zip`
- `build/distributions/YknBoss-CraftEngine-Pack-2.0.20.zip`
- `build/distributions/YknBoss-ItemsAdder-Pack-2.0.20.zip`

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
2. 解压 `YknBoss-BetterModel-Pack-2.0.20.zip`，把 `models` 合并到 `plugins/BetterModel/models/`。
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
| `/yknboss leave` | 队长放弃当前副本并触发全队返程重置 |
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
