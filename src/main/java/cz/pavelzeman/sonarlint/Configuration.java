package cz.pavelzeman.sonarlint;

public class Configuration {

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

  private final String host;
  private final String token;
  private final String projectKey;
  private final String[] sources;
  private final String projectBaseDir;
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
