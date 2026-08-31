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

public final class EndsunGuardArenaGenerator extends ChunkGenerator {
    private static final int FOUNDATION_DEPTH = 7;
    private static final BiomeProvider VOID_BIOME = new BiomeProvider() {
        @Override
        public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
            return Biome.BASALT_DELTAS;
        }

        @Override
        public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
            return List.of(Biome.BASALT_DELTAS);
        }
    };

    private final int radius;
    private final int floorY;
    private final List<FeaturePoint> sunPillars = new ArrayList<>();

    public EndsunGuardArenaGenerator(int radius, int floorY) {
        this.radius = radius;
        this.floorY = floorY;
        for (int index = 0; index < 12; index++) {
            double angle = Math.PI * 2.0 * index / 12.0 + Math.PI / 12.0;
            sunPillars.add(new FeaturePoint(
                    (int) Math.round(Math.cos(angle) * (radius - 18)),
                    (int) Math.round(Math.sin(angle) * (radius - 18)),
                    15 + (index % 3) * 4));
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
                generateSunPillars(data, localX, localZ, worldX, worldZ);
                generateEclipseArches(data, localX, localZ, worldX, worldZ);
                generateAshVents(data, localX, localZ, worldX, worldZ, surfaceY, distance);
                generateMonument(data, localX, localZ, worldX, worldZ);
            }
        }
    }

    private void generateFoundation(ChunkData data, int x, int z, int worldX, int worldZ,
                                    int surfaceY, double distance) {
        int bottom = floorY - FOUNDATION_DEPTH;
        data.setBlock(x, bottom, z, Material.BEDROCK);
        for (int y = bottom + 1; y < surfaceY; y++) {
            Material material = y <= floorY - 3 ? Material.BLACKSTONE
                    : ((worldX + worldZ + y) & 3) == 0 ? Material.BASALT : Material.POLISHED_BASALT;
            data.setBlock(x, y, z, material);
        }
        data.setBlock(x, surfaceY, z, surfaceMaterial(worldX, worldZ, distance));
    }

    private Material surfaceMaterial(int x, int z, double distance) {
        if (distance <= 31.0) {
            if (nearRing(distance, 9.0, 1.0) || nearRing(distance, 20.0, 1.15)
                    || nearRing(distance, 30.0, 1.2)) return Material.GILDED_BLACKSTONE;
            if (isSunRay(x, z, 4)) return ((Math.abs(x) + Math.abs(z)) & 5) == 0
                    ? Material.MAGMA_BLOCK : Material.POLISHED_BLACKSTONE;
            if (distance <= 8.0 && ((x * x + z * z) & 7) <= 2) return Material.CRYING_OBSIDIAN;
            return Material.POLISHED_BLACKSTONE_BRICKS;
        }
        if (isSunRay(x, z, 3) || nearRing(distance, 48.0, 1.1) || nearRing(distance, 72.0, 1.25)) {
            return (hash(x, z) & 3L) == 0L ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE;
        }
        if (nearRing(distance, radius - 29.0, 1.4) || nearRing(distance, radius - 12.0, 1.5)) {
            return Material.CRYING_OBSIDIAN;
        }
        long value = hash(x, z) & 255L;
        if (value < 13) return Material.MAGMA_BLOCK;
        if (value < 27) return Material.CRACKED_POLISHED_BLACKSTONE_BRICKS;
        if (value < 34) return Material.GILDED_BLACKSTONE;
        return ((x ^ z) & 3) == 0 ? Material.BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS;
    }

    private void generateBoundary(ChunkData data, int x, int z, int worldX, int worldZ,
                                  int surfaceY, double distance) {
        if (distance < radius - 7.0 || isGate(worldX, worldZ)) return;
        int height = distance >= radius - 2.5 ? 10 : 5;
        for (int offset = 1; offset <= height; offset++) {
            Material material = offset == height ? Material.GILDED_BLACKSTONE
                    : offset % 4 == 0 ? Material.CRYING_OBSIDIAN : Material.POLISHED_BLACKSTONE_BRICKS;
            data.setBlock(x, surfaceY + offset, z, material);
        }
    }

    private void generateSunPillars(ChunkData data, int x, int z, int worldX, int worldZ) {
        for (FeaturePoint pillar : sunPillars) {
            double distance = Math.hypot(worldX - pillar.x, worldZ - pillar.z);
            if (distance > 3.0) continue;
            int height = Math.max(2, pillar.height - (int) Math.floor(distance * 3.5));
            for (int offset = 1; offset <= height; offset++) {
                Material material = offset % 6 == 0 ? Material.GILDED_BLACKSTONE
                        : offset > height - 3 ? Material.CRYING_OBSIDIAN : Material.POLISHED_BASALT;
                data.setBlock(x, floorY + offset, z, material);
            }
            if (worldX == pillar.x && worldZ == pillar.z) {
                data.setBlock(x, floorY + pillar.height + 1, z, Material.SHROOMLIGHT);
            }
            return;
        }
    }

    private void generateEclipseArches(ChunkData data, int x, int z, int worldX, int worldZ) {
        int archRadius = Math.max(44, radius * 3 / 5);
        if (Math.abs(Math.abs(worldZ) - archRadius) <= 1 && Math.abs(worldX) <= 26) {
            generateArch(data, x, z, Math.abs(worldX));
        }
        if (Math.abs(Math.abs(worldX) - archRadius) <= 1 && Math.abs(worldZ) <= 26) {
            generateArch(data, x, z, Math.abs(worldZ));
        }
    }

    private void generateArch(ChunkData data, int x, int z, int offset) {
        double normalized = Math.min(1.0, offset / 26.0);
        int top = floorY + 7 + (int) Math.round(Math.sqrt(1.0 - normalized * normalized) * 20.0);
        if (offset >= 23) {
            for (int y = floorY + 1; y <= top; y++) {
                data.setBlock(x, y, z, y % 5 == 0 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BASALT);
            }
        } else {
            data.setBlock(x, top - 1, z, Material.POLISHED_BLACKSTONE);
            data.setBlock(x, top, z, Material.GILDED_BLACKSTONE);
            data.setBlock(x, top + 1, z, Material.POLISHED_BLACKSTONE);
        }
    }

    private void generateAshVents(ChunkData data, int x, int z, int worldX, int worldZ,
                                  int surfaceY, double distance) {
        if (distance < 36.0 || distance > radius - 25.0
                || Math.floorMod(hash(worldX, worldZ), 613L) != 0L) return;
        data.setBlock(x, surfaceY + 1, z, Material.MAGMA_BLOCK);
        if ((hash(worldX + 9, worldZ - 13) & 1L) == 0L) {
            data.setBlock(x, surfaceY + 2, z, Material.SOUL_FIRE);
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
            data.setBlock(x, baseY + 1, z, Material.GILDED_BLACKSTONE);
        }
        if (absoluteZ != 0) return;
        if (absoluteX <= 2) {
            for (int offset = 2; offset <= 8; offset++) {
                boolean edge = absoluteX == 2 || offset == 2 || offset == 8;
                Material material = edge ? Material.GILDED_BLACKSTONE
                        : (offset == 5 && worldX == 0 ? Material.CRYING_OBSIDIAN : Material.POLISHED_BLACKSTONE);
                data.setBlock(x, baseY + offset, z, material);
            }
        }
        if (absoluteX == 3 && (baseY + absoluteX) % 2 == 1) {
            data.setBlock(x, baseY + 7, z, Material.MAGMA_BLOCK);
        }
        if (absoluteX <= 1) data.setBlock(x, baseY + 9, z, Material.GILDED_BLACKSTONE);
        if (worldX == 0) data.setBlock(x, baseY + 10, z, Material.SHROOMLIGHT);
    }

    private int surfaceY(long distanceSquared) {
        if (distanceSquared <= 10 * 10) return floorY + 3;
        if (distanceSquared <= 21 * 21) return floorY + 2;
        if (distanceSquared <= 31 * 31) return floorY + 1;
        return floorY;
    }

    private boolean isGate(int x, int z) {
        int edge = radius - 12;
        return (Math.abs(x) <= 8 && Math.abs(z) >= edge)
                || (Math.abs(z) <= 8 && Math.abs(x) >= edge);
    }

    private static boolean isSunRay(int x, int z, int width) {
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
        return new Location(world, 0.5, floorY + 2.0, radius - 45.5, 180.0f, 0.0f);
    }

    @Override
    public @NotNull BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return VOID_BIOME;
    }

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
