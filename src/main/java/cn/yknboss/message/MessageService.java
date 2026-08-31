package cn.yknboss.message;

import cn.yknboss.YknBossPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

public final class MessageService {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final YknBossPlugin plugin;

    public MessageService(YknBossPlugin plugin) {
        this.plugin = plugin;
    }

    public Component component(String key, Object... replacements) {
        return LEGACY.deserialize(raw(key, replacements));
    }

    public Component componentLiteral(String text) {
        return LEGACY.deserialize(text == null ? "" : text);
    }

    public String plain(String key, Object... replacements) {
        return raw(key, replacements).replaceAll("(?i)&[0-9A-FK-ORX]", "");
    }

    public String raw(String key, Object... replacements) {
        FileConfiguration config = plugin.getConfig();
        String value = config.getString("messages." + key, "&cMissing message: " + key);
        if (value == null) value = "";
        if (replacements.length % 2 != 0) {
            throw new IllegalArgumentException("消息占位符必须按名称和值成对提供：" + key);
        }
        for (int index = 0; index < replacements.length; index += 2) {
            value = value.replace("{" + replacements[index] + "}", String.valueOf(replacements[index + 1]));
        }
        return value;
    }

    public List<Component> list(String key, Object... replacements) {
        List<String> configured = plugin.getConfig().getStringList("messages." + key);
        List<Component> result = new ArrayList<>();
        for (String line : configured) {
            String value = line;
            for (int index = 0; index + 1 < replacements.length; index += 2) {
                value = value.replace("{" + replacements[index] + "}", String.valueOf(replacements[index + 1]));
            }
            result.add(LEGACY.deserialize(value));
        }
        return result;
    }

    public void send(CommandSender sender, String key, Object... replacements) {
        sender.sendMessage(component(key, replacements));
    }

    public void sendList(CommandSender sender, String key, Object... replacements) {
        for (Component line : list(key, replacements)) sender.sendMessage(line);
    }
}
