package bg3builder;

import bg3builder.data.DataLoader;
import bg3builder.model.Feature;

import java.io.File;
import java.util.Map;

public class Main {
    public static void main(String[] args) throws Exception {
        DataLoader loader = new DataLoader();
        Map<String, Feature> features = loader.loadFeatures(new File("data/features.json"));

        System.out.println("Loaded " + features.size() + " features.");

        // Quick sanity check: print one known spell
        Feature fireball = features.get("fireball");
        if (fireball != null) {
            System.out.println("Sample feature: " + fireball);
        }
    }
}