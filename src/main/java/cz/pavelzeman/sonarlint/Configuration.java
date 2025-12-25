package cz.pavelzeman.sonarlint;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Properties;
import org.sonarsource.sonarlint.core.commons.log.LogOutput.Level;

/**
 * Configuration properties. For more information about each property, see
 * <a href="https://docs.sonarsource.com/sonarqube/latest/analyzing-source-code/analysis-parameters/">SonarQube documentation</a>.
 *
 * @param host SonarQube server URL
 * @param token authentication token
 * @param projectKey project key
 * @param sources directories with source files
 * @param tests directories with test files
 * @param projectBaseDir project base directory
 * @param logLevel log level
 */
@SuppressWarnings("java:S6218") // No need to override equals and hashCode, because this is just a "POJO" and is not used in any hash-like structures
public record Configuration(String host, String token, String projectKey, String[] sources, String[] tests, String projectBaseDir, Level logLevel) {

  /**
   * Creates configuration object from given properties.
   *
   * @param properties properties to create configuration from
   */
  public static Configuration create(Properties properties) {
    // Use current working directory as default base
    var projectBaseDir = getAbsolutePath(getProperty(properties, PropertyNames.PROJECT_BASE_DIR, false));
    if (projectBaseDir == null) {
      projectBaseDir = Paths.get("").toAbsolutePath().toString();
    }

    // Use base directory as default source directory
    var sourceDirs = parseSources(getProperty(properties, PropertyNames.SOURCES, false));
    if (sourceDirs == null) {
      sourceDirs = new String[]{"."};
    }

    var logLevelString = getProperty(properties, PropertyNames.LOG_LEVEL, false);
    // Use INFO as default log level
    var logLevel = logLevelString == null ? Level.INFO : stringToLevel(logLevelString);

    return new Configuration(
        getProperty(properties, PropertyNames.HOST, true),
        getProperty(properties, PropertyNames.TOKEN, true),
        getProperty(properties, PropertyNames.PROJECT_KEY, true),
        sourceDirs,
        parseSources(getProperty(properties, PropertyNames.TESTS, false)),
        projectBaseDir,
        logLevel
    );
  }

  /**
   * Converts string representation of log level to {@link Level}. Throws exception, if the log level is invalid.
   * @param logLevelString string to convert
   * @return Converted log level.
   */
  private static Level stringToLevel(String logLevelString) {
    try {
      return Level.valueOf(logLevelString.toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid log level: " + logLevelString, e);
    }
  }

  /**
   * Converts property key as used in properties file to environment variable name.
   *
   * @param property property key
   * @return Corresponding environment variable name.
   */
  private static String propertyKeyToEnvironmentVariable(String property) {
    return property.toUpperCase().replace('.', '_');
  }


  /**
   * Gets property value from system properties, environment variables, or given properties (in this order).
   *
   * @param properties properties file
   * @param property property name (without "sonar." prefix)
   * @param required true, if required (exception is thrown if property is missing)
   * @return Property value.
   */
  private static String getProperty(Properties properties, String property, boolean required) {
    var key = "sonar." + property;
    var value = System.getProperty(key);
    if (value == null) {
      value = System.getenv(propertyKeyToEnvironmentVariable(key));
      if (value == null) {
        value = properties.getProperty(key);
      }
    }
    if (required && value == null) {
      throw new IllegalArgumentException("Missing value of " + key + " property");
    }
    return value;
  }

  private static String getAbsolutePath(String path) {
    return path == null ? null : Path.of(path).toAbsolutePath().toString();
  }

  private static String[] parseSources(String sources) {
    return sources == null || sources.trim().isEmpty() ? null : Arrays.stream(sources.split(",")).map(String::trim).toArray(String[]::new);
  }

  /**
   * Names of configuration properties corresponding to configuration properties in the {@link Configuration} class.
   */
  private static class PropertyNames {

    public static final String HOST = "host.url";
    public static final String TOKEN = "token";
    public static final String PROJECT_KEY = "projectKey";
    public static final String SOURCES = "sources";
    public static final String TESTS = "tests";
    public static final String PROJECT_BASE_DIR = "projectBaseDir";
    public static final String LOG_LEVEL = "log.level";
  }
}
