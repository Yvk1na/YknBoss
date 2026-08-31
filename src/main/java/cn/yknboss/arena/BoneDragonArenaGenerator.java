package cn.yknboss.arena;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class BoneDragonArenaGenerator extends ChunkGenerator {
    private static final int FOUNDATION_DEPTH = 6;
    private static final BiomeProvider VOID_BIOME = new BiomeProvider() {
        @Override
        public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
            return Biome.THE_VOID;
        }

        @Override
        public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
            return List.of(Biome.THE_VOID);
        }
    };

    private final int radius;
    private final int floorY;
    private final List<FeaturePoint> outerPillars = new ArrayList<>();
    private final List<FeaturePoint> obsidianSpikes = new ArrayList<>();

    public BoneDragonArenaGenerator(int radius, int floorY) {
        this.radius = radius;
        this.floorY = floorY;
        for (int index = 0; index < 16; index++) {
            double angle = Math.PI * 2.0 * index / 16.0 + Math.PI / 16.0;
            outerPillars.add(new FeaturePoint(
                    (int) Math.round(Math.cos(angle) * (radius - 16)),
                    (int) Math.round(Math.sin(angle) * (radius - 16)),
                    16 + (index % 4 == 0 ? 6 : 0)));
        }
        for (int index = 0; index < 12; index++) {
            double angle = Math.PI * 2.0 * index / 12.0 + Math.PI / 12.0;
            obsidianSpikes.add(new FeaturePoint(
                    (int) Math.round(Math.cos(angle) * (radius - 47)),
                    (int) Math.round(Math.sin(angle) * (radius - 47)),
                    13 + (index % 3) * 4));
        }
    }

    @Override
    public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random,
                              int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;
        long radiusSquared = (long) radius * radius;
        for (int localX = 0; localX < 16; localX++) {
            int worldX = startX + localX;
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldZ = startZ + localZ;
                long distanceSquared = (long) worldX * worldX + (long) worldZ * worldZ;
                if (distanceSquared > radiusSquared) continue;
                double distance = Math.sqrt(distanceSquared);
                int surfaceY = surfaceY(distanceSquared);
                generateFoundation(chunkData, localX, localZ, worldX, worldZ, surfaceY, distance);
                generateBoundary(chunkData, localX, localZ, worldX, worldZ, surfaceY, distance);
                generateBoneArches(chunkData, localX, localZ, worldX, worldZ);
                generateOuterPillars(chunkData, localX, localZ, worldX, worldZ);
                generateObsidianSpikes(chunkData, localX, localZ, worldX, worldZ);
                generateBoneRemains(chunkData, localX, localZ, worldX, worldZ, surfaceY, distance);
                generateMonument(chunkData, localX, localZ, worldX, worldZ);
            }
        }
    }

    private void generateFoundation(ChunkData data, int x, int z, int worldX, int worldZ,
                                    int surfaceY, double distance) {
        int bottom = floorY - FOUNDATION_DEPTH;
        data.setBlock(x, bottom, z, Material.BEDROCK);
        for (int y = bottom + 1; y < surfaceY - 1; y++) {
            data.setBlock(x, y, z, y <= floorY - 3 ? Material.BLACKSTONE : Material.POLISHED_BASALT);
        }
        if (surfaceY > bottom + 1) data.setBlock(x, surfaceY - 1, z, Material.DEEPSLATE_BRICKS);
        data.setBlock(x, surfaceY, z, surfaceMaterial(worldX, worldZ, distance));
    }

    private Material surfaceMaterial(int x, int z, double distance) {
        if (distance <= 28.5) {
            if (isSkullEye(x, z)) return Material.CRYING_OBSIDIAN;
            if (isSkullJaw(x, z)) return Material.CHISELED_POLISHED_BLACKSTONE;
            if (nearRing(distance, 10.0, 1.2) || nearRing(distance, 20.0, 1.2)
                    || nearRing(distance, 28.0, 1.2)) return Material.BONE_BLOCK;
            return (hash(x, z) & 7L) == 0L ? Material.CRACKED_DEEPSLATE_TILES : Material.POLISHED_BLACKSTONE_BRICKS;
        }
        boolean road = Math.min(Math.abs(x), Math.abs(z)) <= 2
                || Math.abs(Math.abs(x) - Math.abs(z)) <= 2;
        if (road || nearRing(distance, 48.0, 1.25) || nearRing(distance, 72.0, 1.4)) {
            return Material.BONE_BLOCK;
        }
        if (nearRing(distance, radius - 28.0, 1.5) || nearRing(distance, radius - 14.0, 1.5)) {
            return Material.CRYING_OBSIDIAN;
        }
        long value = hash(x, z) & 255L;
        if (value < 15) return Material.CRACKED_DEEPSLATE_TILES;
        if (value < 22) return Material.GILDED_BLACKSTONE;
        if (value < 28) return Material.SOUL_SOIL;
        return ((x ^ z) & 3) == 0 ? Material.DEEPSLATE_TILES : Material.POLISHED_BLACKSTONE_BRICKS;
    }

    private void generateBoundary(ChunkData data, int x, int z, int worldX, int worldZ,
                                  int surfaceY, double distance) {
        if (distance < radius - 7.0 || isGate(worldX, worldZ)) return;
        int height = distance >= radius - 2.5 ? 9 : 4;
        for (int offset = 1; offset <= height; offset++) {
            Material material = offset == height
                    ? Material.BONE_BLOCK
                    : (offset % 3 == 0 ? Material.POLISHED_BASALT : Material.POLISHED_BLACKSTONE_BRICKS);
            data.setBlock(x, surfaceY + offset, z, material);
        }
    }

    private void generateBoneArches(ChunkData data, int x, int z, int worldX, int worldZ) {
        int archRadius = Math.max(42, radius * 3 / 5);
        if (Math.abs(Math.abs(worldZ) - archRadius) <= 1 && Math.abs(worldX) <= 30) {
            generateArchColumn(data, x, z, Math.abs(worldX));
        }
        if (Math.abs(Math.abs(worldX) - archRadius) <= 1 && Math.abs(worldZ) <= 30) {
            generateArchColumn(data, x, z, Math.abs(worldZ));
        }
    }

    private void generateArchColumn(ChunkData data, int x, int z, int offset) {
        double normalized = Math.min(1.0, offset / 30.0);
        int archTop = floorY + 8 + (int) Math.round(Math.sqrt(1.0 - normalized * normalized) * 24.0);
        if (offset >= 27) {
            for (int y = floorY + 1; y <= archTop; y++) {
                data.setBlock(x, y, z, y % 5 == 0 ? Material.POLISHED_BASALT : Material.BONE_BLOCK);
            }
        } else {
            data.setBlock(x, archTop - 1, z, Material.BONE_BLOCK);
            data.setBlock(x, archTop, z, Material.BONE_BLOCK);
            data.setBlock(x, archTop + 1, z, Material.BONE_BLOCK);
        }
    }

    private void generateOuterPillars(ChunkData data, int x, int z, int worldX, int worldZ) {
        for (FeaturePoint pillar : outerPillars) {
            double distance = Math.hypot(worldX - pillar.x, worldZ - pillar.z);
            if (distance > 2.2) continue;
            int height = Math.max(2, pillar.height - (int) Math.floor(distance * 3.0));
            for (int offset = 1; offset <= height; offset++) {
                Material material = offset % 6 == 0 ? Material.BONE_BLOCK : Material.POLISHED_BASALT;
                data.setBlock(x, floorY + offset, z, material);
            }
            if (worldX == pillar.x && worldZ == pillar.z) {
                data.setBlock(x, floorY + pillar.height + 1, z, Material.SOUL_LANTERN);
            }
            return;
        }
    }

    private void generateObsidianSpikes(ChunkData data, int x, int z, int worldX, int worldZ) {
        for (FeaturePoint spike : obsidianSpikes) {
            double distance = Math.hypot(worldX - spike.x, worldZ - spike.z);
            if (distance > 4.2) continue;
            int height = Math.max(1, (int) Math.round(spike.height * (1.0 - distance / 4.5)));
            for (int offset = 1; offset <= height; offset++) {
                data.setBlock(x, floorY + offset, z,
                        offset > height - 3 ? Material.CRYING_OBSIDIAN : Material.OBSIDIAN);
            }
            return;
        }
    }

    private void generateBoneRemains(ChunkData data, int x, int z, int worldX, int worldZ,
                                     int surfaceY, double distance) {
        if (distance < 42.0 || distance > radius - 24.0 || Math.floorMod(hash(worldX, worldZ), 997L) != 0L) return;
        int height = 1 + (int) (Math.floorMod(hash(worldX + 31, worldZ - 17), 3L));
        for (int offset = 1; offset <= height; offset++) {
            data.setBlock(x, surfaceY + offset, z, Material.BONE_BLOCK);
        }
    }

    private void generateMonument(ChunkData data, int x, int z, int worldX, int worldZ) {
        int absoluteX = Math.abs(worldX);
        int absoluteZ = Math.abs(worldZ);
        if (absoluteX > 4 || absoluteZ > 2) return;

        int baseY = floorY + 4;
        if (absoluteX <= 3 && absoluteZ <= 2) {
            data.setBlock(x, baseY, z, Material.CHISELED_POLISHED_BLACKSTONE);
        }
        if (absoluteX <= 2 && absoluteZ <= 1) {
            data.setBlock(x, baseY + 1, z, Material.POLISHED_BASALT);
        }
        if (absoluteZ != 0) return;

        if (absoluteX <= 2) {
            for (int offset = 2; offset <= 8; offset++) {
                boolean edge = absoluteX == 2 || offset == 2 || offset == 8;
                Material material = edge ? Material.BONE_BLOCK
                        : (offset == 5 && worldX == 0 ? Material.CRYING_OBSIDIAN : Material.POLISHED_BLACKSTONE);
                data.setBlock(x, baseY + offset, z, material);
            }
        }
        if (absoluteX == 3) {
            for (int offset = 5; offset <= 8; offset++) {
                if (offset == 5 || offset == 8) data.setBlock(x, baseY + offset, z, Material.BONE_BLOCK);
            }
        }
        if (absoluteX == 4) {
            data.setBlock(x, baseY + 9, z, Material.BONE_BLOCK);
            data.setBlock(x, baseY + 10, z, Material.BONE_BLOCK);
        }
        if (absoluteX <= 1) data.setBlock(x, baseY + 9, z, Material.BONE_BLOCK);
        if (worldX == 0) data.setBlock(x, baseY + 10, z, Material.SOUL_LANTERN);
    }

    private int surfaceY(long distanceSquared) {
        if (distanceSquared <= 10 * 10) return floorY + 3;
        if (distanceSquared <= 20 * 20) return floorY + 2;
        if (distanceSquared <= 29 * 29) return floorY + 1;
        return floorY;
    }

    private boolean isGate(int x, int z) {
        int edge = radius - 12;
        return (Math.abs(x) <= 8 && Math.abs(z) >= edge)
                || (Math.abs(z) <= 8 && Math.abs(x) >= edge);
    }

    private static boolean isSkullEye(int x, int z) {
        return Math.abs(Math.abs(x) - 4) <= 1 && z >= -5 && z <= -2;
    }

    private static boolean isSkullJaw(int x, int z) {
        return Math.abs(x) <= 6 && z >= 4 && z <= 7 && ((x + z) & 1) == 0;
    }

    private static boolean nearRing(double distance, double ring, double width) {
        return Math.abs(distance - ring) <= width;
    }

    private static long hash(int x, int z) {
        long value = x * 341873128712L + z * 132897987541L;
        value ^= value >>> 29;
        value *= 0x9E3779B97F4A7C15L;
        return value ^ value >>> 32;
    }

    @Override
    public int getBaseHeight(@NotNull WorldInfo worldInfo, @NotNull Random random,
                             int x, int z, @NotNull HeightMap heightMap) {
        long distanceSquared = (long) x * x + (long) z * z;
        return distanceSquared <= (long) radius * radius ? surfaceY(distanceSquared) + 1 : worldInfo.getMinHeight();
    }

    @Override
    public @NotNull Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        return new Location(world, 0.5, floorY + 2.0, radius - 45.5, 180.0f, 0.0f);
    }

    @Override
    public @NotNull BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return VOID_BIOME;
    }

    @Override
    public boolean canSpawn(@NotNull World world, int x, int z) {
        return (long) x * x + (long) z * z <= (long) radius * radius;
    }

    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return false; }
    @Override public boolean shouldGenerateCaves() { return false; }
    @Override public boolean shouldGenerateDecorations() { return false; }
    @Override public boolean shouldGenerateMobs() { return false; }
    @Override public boolean shouldGenerateStructures() { return false; }

    private record FeaturePoint(int x, int z, int height) { }
}
