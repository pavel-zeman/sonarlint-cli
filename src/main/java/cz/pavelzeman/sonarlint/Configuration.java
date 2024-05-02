package cz.pavelzeman.sonarlint;

/**
 * Configuration properties. For more information about each property, see
 * <a href="https://docs.sonarsource.com/sonarqube/latest/analyzing-source-code/analysis-parameters/">SonarQube documentation</a>.
 */
public class Configuration {

  /**
   * Names of configuration properties corresponding to configuration properties in the {@link Configuration} class.
   */
  public static class Properties {
    private Properties() {
      // Default empty constructor to prevent instantiation
    }
    public static final String HOST = "host.url";
    public static final String TOKEN = "token";
    public static final String PROJECT_KEY = "projectKey";
    public static final String SOURCES = "sources";
    public static final String PROJECT_BASE_DIR = "projectBaseDir";
    public static final String EXCLUSIONS = "exclusions";
  }

  /**
   * SonarQube server URL.
   */
  private final String host;

  /**
   * Authentication token.
   */
  private final String token;

  /**
   * Project key.
   */
  private final String projectKey;

  /**
   * Directories with source files.
   */
  private final String[] sources;

  /**
   * Project base directory.
   */
  private final String projectBaseDir;

  /**
   * Exclusions, i.e. ant path expressions for files, which should be excluded from analysis.
   */
  private final String[] exclusions;

  public Configuration(String host, String token, String projectKey, String[] sources, String projectBaseDir, String[] exclusions) {
    this.host = host;
    this.token = token;
    this.projectKey = projectKey;
    this.sources = sources;
    this.projectBaseDir = projectBaseDir;
    this.exclusions = exclusions;
  }

  public String getHost() {
    return host;
  }

  public String getToken() {
    return token;
  }

  public String getProjectKey() {
    return projectKey;
  }

  public String[] getSources() {
    return sources;
  }

  public String getProjectBaseDir() {
    return projectBaseDir;
  }

  public String[] getExclusions() {
    return exclusions;
  }
}
