package com.example;

import org.yaml.snakeyaml.Yaml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class ConfigLoader {

    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);

    public static Map<String, String> loadConfigProperties() {
        Map<String, String> configProperties = new HashMap<>();

        // Load from application.properties
        configProperties.putAll(loadPropertiesFile("application.properties"));

        // Load from application.yml
        configProperties.putAll(loadYamlFile("application.yml"));

        // Load from environment variables
        configProperties.putAll(loadEnvironmentVariables());

        logger.info("Loaded {} configuration properties", configProperties.size());

        return configProperties;
    }

    private static Map<String, String> loadPropertiesFile(String filename) {
        Properties properties = new Properties();
        Map<String, String> propertiesMap = new HashMap<>();

        try (InputStream input = ConfigLoader.class.getClassLoader().getResourceAsStream(filename)) {
            if (input == null) {
                logger.warn("Unable to find {}", filename);
                return propertiesMap;
            }

            properties.load(input);
            for (String key : properties.stringPropertyNames()) {
                propertiesMap.put(key, properties.getProperty(key));
            }
            logger.info("Loaded {} properties from {}", propertiesMap.size(), filename);
        } catch (IOException ex) {
            logger.error("Error loading properties file {}", filename, ex);
        }

        return propertiesMap;
    }

    private static Map<String, String> loadYamlFile(String filename) {
        Map<String, String> yamlProperties = new HashMap<>();

        try (InputStream input = ConfigLoader.class.getClassLoader().getResourceAsStream(filename)) {
            if (input == null) {
                logger.warn("Unable to find {}", filename);
                return yamlProperties;
            }

            Yaml yaml = new Yaml();
            Map<String, Object> yamlMap = yaml.load(input);
            flattenYamlMap(yamlMap, "", yamlProperties);
            logger.info("Loaded {} properties from {}", yamlProperties.size(), filename);
        } catch (Exception ex) {
            logger.error("Error loading YAML file {}", filename, ex);
        }

        return yamlProperties;
    }

    private static void flattenYamlMap(Map<String, Object> yamlMap, String prefix, Map<String, String> result) {
        for (Map.Entry<String, Object> entry : yamlMap.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) entry.getValue();
                flattenYamlMap(nestedMap, key, result);
            } else {
                result.put(key, entry.getValue().toString());
            }
        }
    }

    private static Map<String, String> loadEnvironmentVariables() {
        Map<String, String> envVariables = new HashMap<>();
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            envVariables.put(entry.getKey(), entry.getValue());
        }
        logger.info("Loaded {} environment variables", envVariables.size());
        return envVariables;
    }
}