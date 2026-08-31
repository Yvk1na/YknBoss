package cn.yknboss.util;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

public final class ParticleCompat {
    private ParticleCompat() { }

    public static void spawnDust(World world, Location location, int count,
                                 double offsetX, double offsetY, double offsetZ, double extra,
                                 Color color, float size) {
        if (world == null || location == null || color == null) return;
        Object data = Particle.DUST.getDataType() == Color.class
                ? color
                : new Particle.DustOptions(color, size);
        world.spawnParticle(Particle.DUST, location, count,
                offsetX, offsetY, offsetZ, extra, data);
    }

    public static void spawnFlash(World world, Location location, int count, Color color) {
        if (world == null || location == null || color == null || count <= 0) return;
        if (Particle.FLASH.getDataType() == Color.class) {
            world.spawnParticle(Particle.FLASH, location, count, color);
        } else {
            world.spawnParticle(Particle.FLASH, location, count);
        }
    }
}
