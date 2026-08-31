import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class ValidateYaml {
    public static void main(String[] args) throws IOException {
        for (String value : args) {
            Object parsed = new Yaml().load(Files.readString(Path.of(value)));
            if (!(parsed instanceof Map<?, ?> root)) throw new IllegalStateException(value + " is not a YAML map");
            System.out.println(value + " OK " + root.keySet());
        }
    }
}
