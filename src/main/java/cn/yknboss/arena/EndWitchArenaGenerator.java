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

/** Generates the 100-block-radius black/verdant ritual arena for 伊芙蕾雅. */
public final class EndWitchArenaGenerator extends ChunkGenerator {
    private static final int FOUNDATION_DEPTH = 7;
    private static final BiomeProvider VOID_BIOME = new BiomeProvider() {
        @Override
        public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
            return Biome.DEEP_DARK;
        }

        @Override
        public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
            return List.of(Biome.DEEP_DARK);
        }
    };

    private final int radius;
    private final int floorY;
    private final List<FeaturePoint> witchSpires = new ArrayList<>();
    private final List<FeaturePoint> gazePillars = new ArrayList<>();

    public EndWitchArenaGenerator(int radius, int floorY) {
        this.radius = radius;
        this.floorY = floorY;
        for (int index = 0; index < 12; index++) {
            double angle = Math.PI * 2 * index / 12.0 + Math.PI / 12.0;
            witchSpires.add(new FeaturePoint(
                    (int) Math.round(Math.cos(angle) * (radius - 15)),
                    (int) Math.round(Math.sin(angle) * (radius - 15)),
                    14 + index % 4 * 3));
        }
        for (int index = 0; index < 8; index++) {
            double angle = Math.PI * 2 * index / 8.0 + Math.PI / 8.0;
            gazePillars.add(new FeaturePoint(
                    (int) Math.round(Math.cos(angle) * 43),
                    (int) Math.round(Math.sin(angle) * 43),
                    9 + index % 2 * 3));
        }
    }

    @Override
    public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random,
                              int chunkX, int chunkZ, @NotNull ChunkData data) {
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;
        long limit = (long) radius * radius;
        for (int localX = 0; localX < 16; localX++) {
            int worldX = startX + localX;
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldZ = startZ + localZ;
                long distanceSquared = (long) worldX * worldX + (long) worldZ * worldZ;
                if (distanceSquared > limit) continue;
                double distance = Math.sqrt(distanceSquared);
                int surfaceY = surfaceY(distanceSquared);
                generateFoundation(data, localX, localZ, worldX, worldZ, surfaceY, distance);
                generateBoundary(data, localX, localZ, worldX, worldZ, surfaceY, distance);
                generateCleanseCircles(data, localX, localZ, worldX, worldZ, surfaceY);
                generateWitchSpires(data, localX, localZ, worldX, worldZ);
                generateGazePillars(data, localX, localZ, worldX, worldZ);
                generateBrokenArches(data, localX, localZ, worldX, worldZ);
                generateRitualMonument(data, localX, localZ, worldX, worldZ);
            }
        }
    }

    private void generateFoundation(ChunkData data, int x, int z, int worldX, int worldZ,
                                    int surfaceY, double distance) {
        int bottom = floorY - FOUNDATION_DEPTH;
        data.setBlock(x, bottom, z, Material.BEDROCK);
        for (int y = bottom + 1; y < surfaceY; y++) {
            Material material = y <= floorY - 3 ? Material.DEEPSLATE
                    : ((worldX + worldZ + y) & 3) == 0 ? Material.REINFORCED_DEEPSLATE
                    : Material.POLISHED_DEEPSLATE;
            data.setBlock(x, y, z, material);
        }
        data.setBlock(x, surfaceY, z, surfaceMaterial(worldX, worldZ, distance));
    }

    private Material surfaceMaterial(int x, int z, double distance) {
        if (distance <= 31) {
            if (nearRing(distance, 8.0, .85) || nearRing(distance, 18.0, 1.0)
                    || nearRing(distance, 30.0, 1.1)) return Material.OXIDIZED_CUT_COPPER;
            if (isRuneRay(x, z, 2)) return ((Math.abs(x) + Math.abs(z)) & 5) == 0
                    ? Material.SEA_LANTERN : Material.DARK_PRISMARINE;
            if (distance <= 7 && ((x * x + z * z) & 7) <= 2) return Material.CRYING_OBSIDIAN;
            return Material.DEEPSLATE_TILES;
        }
        if (isRuneRay(x, z, 1) || nearRing(distance, 52, 1.0) || nearRing(distance, 76, 1.2)) {
            return (hash(x, z) & 3L) == 0 ? Material.SCULK : Material.DARK_PRISMARINE;
        }
        if (nearRing(distance, radius - 28, 1.3) || nearRing(distance, radius - 10, 1.5)) {
            return Material.CRYING_OBSIDIAN;
        }
        long value = hash(x, z) & 255L;
        if (value < 12) return Material.SCULK;
        if (value < 22) return Material.CRACKED_DEEPSLATE_TILES;
        if (value < 28) return Material.OXIDIZED_COPPER;
        return ((x ^ z) & 3) == 0 ? Material.POLISHED_DEEPSLATE : Material.DEEPSLATE_TILES;
    }

    private void generateBoundary(ChunkData data, int x, int z, int worldX, int worldZ,
                                  int surfaceY, double distance) {
        if (distance < radius - 6 || isGate(worldX, worldZ)) return;
        int height = distance >= radius - 2.5 ? 11 : 5;
        for (int offset = 1; offset <= height; offset++) {
            Material material = offset == height ? Material.OXIDIZED_COPPER
                    : offset % 4 == 0 ? Material.CRYING_OBSIDIAN : Material.DEEPSLATE_BRICKS;
            data.setBlock(x, surfaceY + offset, z, material);
        }
    }

    private void generateCleanseCircles(ChunkData data, int x, int z, int worldX, int worldZ, int surfaceY) {
        for (int index = 0; index < 4; index++) {
            double angle = index * Math.PI / 2;
            int centerX = (int) Math.round(Math.cos(angle) * 18);
            int centerZ = (int) Math.round(Math.sin(angle) * 18);
            double distance = Math.hypot(worldX - centerX, worldZ - centerZ);
            if (distance <= 2.5 && distance >= 1.5) data.setBlock(x, surfaceY, z, Material.QUARTZ_BLOCK);
            else if (distance < 1.5) data.setBlock(x, surfaceY, z, Material.CALCITE);
        }
    }

    private void generateWitchSpires(ChunkData data, int x, int z, int worldX, int worldZ) {
        for (FeaturePoint point : witchSpires) {
            double distance = Math.hypot(worldX - point.x, worldZ - point.z);
            if (distance > 3.2) continue;
            int height = Math.max(2, point.height - (int) Math.floor(distance * 3.8));
            for (int offset = 1; offset <= height; offset++) {
                Material material = offset % 5 == 0 ? Material.OXIDIZED_CUT_COPPER
                        : offset > height - 3 ? Material.SCULK : Material.POLISHED_DEEPSLATE;
                data.setBlock(x, floorY + offset, z, material);
            }
            if (worldX == point.x && worldZ == point.z) {
                data.setBlock(x, floorY + point.height + 1, z, Material.SOUL_LANTERN);
            }
            return;
        }
    }

    private void generateGazePillars(ChunkData data, int x, int z, int worldX, int worldZ) {
        for (FeaturePoint point : gazePillars) {
            int dx = Math.abs(worldX - point.x);
            int dz = Math.abs(worldZ - point.z);
            if (dx > 1 || dz > 1) continue;
            for (int offset = 1; offset <= point.height; offset++) {
                Material material = offset == point.height ? Material.SEA_LANTERN
                        : offset % 4 == 0 ? Material.OXIDIZED_COPPER : Material.DEEPSLATE_BRICKS;
                data.setBlock(x, floorY + offset, z, material);
            }
            return;
        }
    }

    private void generateBrokenArches(ChunkData data, int x, int z, int worldX, int worldZ) {
        int archRadius = Math.max(57, radius * 2 / 3);
        if (Math.abs(Math.abs(worldZ) - archRadius) <= 1 && Math.abs(worldX) <= 25) {
            generateArch(data, x, z, Math.abs(worldX), worldX + worldZ);
        }
        if (Math.abs(Math.abs(worldX) - archRadius) <= 1 && Math.abs(worldZ) <= 25) {
            generateArch(data, x, z, Math.abs(worldZ), worldX - worldZ);
        }
    }

    private void generateArch(ChunkData data, int x, int z, int offset, int breakSeed) {
        double normalized = Math.min(1.0, offset / 25.0);
        int top = floorY + 6 + (int) Math.round(Math.sqrt(1 - normalized * normalized) * 18);
        if (offset >= 22) {
            for (int y = floorY + 1; y <= top; y++) {
                data.setBlock(x, y, z, y % 5 == 0 ? Material.OXIDIZED_COPPER : Material.DEEPSLATE_BRICKS);
            }
        } else if (Math.floorMod(breakSeed + top, 7) != 0) {
            data.setBlock(x, top - 1, z, Material.DARK_PRISMARINE);
            data.setBlock(x, top, z, Material.OXIDIZED_CUT_COPPER);
        }
    }

    private void generateRitualMonument(ChunkData data, int x, int z, int worldX, int worldZ) {
        int absoluteX = Math.abs(worldX);
        int absoluteZ = Math.abs(worldZ);
        if (absoluteX > 4 || absoluteZ > 2) return;
        int baseY = floorY + 4;
        if (absoluteX <= 3 && absoluteZ <= 2) data.setBlock(x, baseY, z, Material.CHISELED_DEEPSLATE);
        if (absoluteX <= 2 && absoluteZ <= 1) data.setBlock(x, baseY + 1, z, Material.DARK_PRISMARINE);
        if (absoluteZ != 0) return;
        if (absoluteX <= 2) {
            for (int offset = 2; offset <= 8; offset++) {
                boolean edge = absoluteX == 2 || offset == 2 || offset == 8;
                Material material = edge ? Material.OXIDIZED_CUT_COPPER
                        : offset == 5 && worldX == 0 ? Material.CRYING_OBSIDIAN : Material.DEEPSLATE_TILES;
                data.setBlock(x, baseY + offset, z, material);
            }
        }
        if (absoluteX <= 1) data.setBlock(x, baseY + 9, z, Material.DARK_PRISMARINE);
        if (worldX == 0) data.setBlock(x, baseY + 10, z, Material.SEA_LANTERN);
    }

    private int surfaceY(long distanceSquared) {
        if (distanceSquared <= 10 * 10) return floorY + 3;
        if (distanceSquared <= 22 * 22) return floorY + 2;
        if (distanceSquared <= 32 * 32) return floorY + 1;
        return floorY;
    }

    private boolean isGate(int x, int z) {
        int edge = radius - 12;
        return (Math.abs(x) <= 8 && Math.abs(z) >= edge)
                || (Math.abs(z) <= 8 && Math.abs(x) >= edge);
    }

    private static boolean isRuneRay(int x, int z, int width) {
        return Math.min(Math.abs(x), Math.abs(z)) <= width
                || Math.abs(Math.abs(x) - Math.abs(z)) <= width;
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
        return new Location(world, .5, floorY + 2.0, radius - 45.5, 180, 0);
    }

    @Override public @NotNull BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) { return VOID_BIOME; }
    @Override public boolean canSpawn(@NotNull World world, int x, int z) {
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
