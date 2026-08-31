import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ValidateMessageKeys {
    private static final Pattern DIRECT = Pattern.compile("(?:component|plain)\\(\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern SEND = Pattern.compile("send\\([^,]+,\\s*\\\"([^\\\"]+)\\\"");

    public static void main(String[] args) throws IOException {
        Path project = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        Map<?, ?> yaml;
        try (var reader = Files.newBufferedReader(project.resolve("src/main/resources/config.yml"))) {
            yaml = new Yaml().load(reader);
        }
        Set<String> available = new HashSet<>();
        flatten("", yaml, available);
        Set<String> referenced = new HashSet<>();
        try (var files = Files.walk(project.resolve("src/main/java"))) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    String source = Files.readString(path);
                    collect(DIRECT, source, referenced);
                    collect(SEND, source, referenced);
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
        }
        Set<String> missing = new java.util.TreeSet<>();
        for (String key : referenced) {
            if (!available.contains("messages." + key)) missing.add(key);
        }
        if (!missing.isEmpty()) throw new IllegalStateException("Missing message keys: " + missing);
        System.out.println("Validated " + referenced.size() + " literal message keys.");
    }

    private static void collect(Pattern pattern, String source, Set<String> result) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) result.add(matcher.group(1));
    }

    private static void flatten(String prefix, Object value, Set<String> result) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String key = prefix.isEmpty() ? entry.getKey().toString() : prefix + "." + entry.getKey();
                flatten(key, entry.getValue(), result);
            }
        } else {
            result.add(prefix);
        }
    }
}
