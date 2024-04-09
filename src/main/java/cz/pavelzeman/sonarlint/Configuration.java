package cz.pavelzeman.sonarlint;

public class Configuration {

  public static class Properties {
    public static final String HOST = "host.url";
    public static final String TOKEN = "token";
    public static final String PROJECT_KEY = "projectKey";
    public static final String SOURCES = "sources";
    public static final String PROJECT_BASE_DIR = "projectBaseDir";
  }

  private final String host;
  private final String token;
  private final String projectKey;
  private final String[] sources;
  private final String projectBaseDir;

  public Configuration(String host, String token, String projectKey, String[] sources, String projectBaseDir) {
    this.host = host;
    this.token = token;
    this.projectKey = projectKey;
    this.sources = sources;
    this.projectBaseDir = projectBaseDir;
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
}
