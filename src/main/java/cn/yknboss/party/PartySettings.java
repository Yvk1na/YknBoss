package cn.yknboss.party;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public record PartySettings(int maxMembers, long inviteLifetimeMillis) {
    public static PartySettings load(FileConfiguration config) {
        ConfigurationSection root = config.getConfigurationSection("party");
        if (root == null) throw new IllegalStateException("config.yml 缺少 party 配置段");
        int maxMembers = Math.max(1, Math.min(6, root.getInt("max-members", 6)));
        long inviteLifetimeMillis = Math.max(5L, Math.min(3600L,
                root.getLong("invite-expire-seconds", 60L))) * 1000L;
        return new PartySettings(maxMembers, inviteLifetimeMillis);
    }
}
