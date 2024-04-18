package cz.pavelzeman.sonarlint;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.sonarsource.sonarlint.core.ConnectedSonarLintEngineImpl;
import org.sonarsource.sonarlint.core.analysis.api.ClientInputFile;
import org.sonarsource.sonarlint.core.client.api.common.analysis.Issue;
import org.sonarsource.sonarlint.core.client.api.connected.ConnectedAnalysisConfiguration;
import org.sonarsource.sonarlint.core.client.api.connected.ConnectedGlobalConfiguration;
import org.sonarsource.sonarlint.core.commons.IssueSeverity;
import org.sonarsource.sonarlint.core.commons.Language;
import org.sonarsource.sonarlint.core.commons.Version;
import org.sonarsource.sonarlint.core.commons.log.ClientLogOutput;
import org.sonarsource.sonarlint.core.http.HttpClientProvider;
import org.sonarsource.sonarlint.core.serverapi.EndpointParams;
import org.sonarsource.sonarlint.shaded.org.springframework.util.AntPathMatcher;
import org.sonarsource.sonarlint.shaded.org.springframework.util.StringUtils;

public class Main {

  private static final Logger logger = LoggerFactory.getLogger(Main.class);

  private Configuration configuration;
  private ConnectedSonarLintEngineImpl engine;
  private List<ClientInputFile> inputFiles;
  private List<Issue> issueList;

  private static String getSeverity(IssueSeverity severity) {
    switch (severity) {
      case BLOCKER:
      case CRITICAL:
        return "ERROR";
      case MAJOR:
        return "WARNING";
      case MINOR:
      case INFO:
        return "WEAK WARNING";
      default:
        throw new SonarLintException("Unexpected severity - " + severity);
    }
  }

  private boolean isExcluded(Path file) {
    if (configuration.getExclusions() != null) {
      AntPathMatcher matcher = new AntPathMatcher(File.separator);
      for (String exclusion : configuration.getExclusions()) {
        if (matcher.match(exclusion, file.toString())) {
          return true;
        }
      }
    }
    return false;
  }

  private void listFiles(Path path) throws IOException {
    try (var stream = Files.list(path)) {
      stream.forEach(file -> {
        if (Files.isDirectory(file)) {
          try {
            listFiles(file);
          } catch (IOException e) {
            throw new SonarLintException(e);
          }
        } else if (!isExcluded(file)) {
          inputFiles.add(new InputFile(file));
        }
      });
    }
  }

  public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
    new Main().run(args);
    // The engine sometimes keeps running, so we have to explicitly exit here
    System.exit(0);
  }

  private String getProperty(Properties properties, String property) {
    return properties.getProperty("sonar." + property);
  }

  private String[] parseSources(String sources) {
    return sources == null ? null : Arrays.stream(sources.split(",")).map(String::trim).toArray(String[]::new);
  }

  private String[] parseExclusions(String exclusions) {
    return exclusions == null ? null : Arrays.stream(exclusions.split(",")).map(String::trim).map(string -> string.replace("/", File.separator)).toArray(String[]::new);
  }

  private void parseConfiguration(String path) throws IOException {
    var properties = new Properties();
    try (var inputStream = new FileInputStream(path)) {
      properties.load(inputStream);
    }
    configuration = new Configuration(
        getProperty(properties, Configuration.Properties.HOST),
        getProperty(properties, Configuration.Properties.TOKEN),
        getProperty(properties, Configuration.Properties.PROJECT_KEY),
        parseSources(getProperty(properties, Configuration.Properties.SOURCES)),
        getProperty(properties, Configuration.Properties.PROJECT_BASE_DIR),
        parseExclusions(getProperty(properties, Configuration.Properties.EXCLUSIONS))
    );
  }

  private Path getStorageRoot() {
    var home = System.getProperty("user.home");
    return Path.of(home, ".sonarlint-cli");
  }

  private String getNodeVersion(String nodePath) throws IOException {
    var process = new ProcessBuilder(nodePath, "--version").start();
    var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
    var output = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      output.append(line).append("\n");
    }
    var version = output.toString().trim();
    if (version.startsWith("v")) {
      version = version.substring(1);
    }
    return version;
  }

  private String getNodePath() throws IOException {
    String os = System.getProperty("os.name").toLowerCase();
    String executable;
    if (os.contains("win")) {
      executable = "where";
    } else if (os.contains("nix") || os.contains("nux") || os.contains("mac")) {
      executable = "which";
    } else {
      throw new SonarLintException("Unsupported OS: " + os);
    }
    Process process = new ProcessBuilder(executable, "node").start();
    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
    return reader.readLine();
  }

  private void createEngine() throws IOException {
    var builder = ConnectedGlobalConfiguration.sonarQubeBuilder()
        .addEnabledLanguages(Language.JS)
        .addEnabledLanguages(Language.CSS)
        .addEnabledLanguages(Language.HTML)
        .addEnabledLanguages(Language.JAVA)
        .addEnabledLanguages(Language.XML)
        .addEnabledLanguages(Language.YAML)
        .addEnabledLanguages(Language.JSON)
        .setConnectionId("sonarlint-cli")
        .setStorageRoot(getStorageRoot());

    var nodePath = getNodePath();
    if (StringUtils.hasText(nodePath)) {
      var nodeVersion = getNodeVersion(nodePath);
      builder.setNodeJs(Path.of(nodePath), Version.create(nodeVersion));
    } else {
      logger.warn("No Node.js found in path, some inspections may be skipped");
    }

    var sonarConfiguration = builder.build();

    engine = new ConnectedSonarLintEngineImpl(sonarConfiguration);
    var httpClientProvider = new HttpClientProvider("sonarlint-cli", null, null, null, null);
    var endpointParams = new EndpointParams(configuration.getHost(), false, null);
    var httpClient = httpClientProvider.getHttpClientWithPreemptiveAuth(configuration.getToken(), null);
    engine.sync(endpointParams, httpClient, Set.of(configuration.getProjectKey()), null);
  }

  private void getInputFiles() throws IOException {
    inputFiles = new ArrayList<>();
    for (String sourcePath : configuration.getSources()) {
      listFiles(Path.of(sourcePath));
    }
  }

  private Level transformLevelToSlf4j(ClientLogOutput.Level level) {
    switch (level) {
      case ERROR: return Level.ERROR;
      case WARN:return Level.WARN;
      case INFO:return Level.INFO;
      case DEBUG:return Level.DEBUG;
      default: return Level.TRACE;
    }
  }

  private void analyze() {
    var builder = ConnectedAnalysisConfiguration.builder();
    builder.setBaseDir(Path.of(configuration.getProjectBaseDir()));
    builder.addInputFiles(inputFiles);
    builder.setProjectKey(configuration.getProjectKey());

    issueList = new ArrayList<>();

    engine.analyze(
        builder.build(),
        issue -> issueList.add(issue),
        (s, level) -> logger.atLevel(transformLevelToSlf4j(level)).log(s),
        null
    );
  }

  public String escapeStringForTc(String input) {
    input = input.replace("|", "||");
    input = input.replace("\n", "|n");
    input = input.replace("\r", "|r");
    input = input.replace("'", "|'");
    input = input.replace("[", "|[");
    input = input.replace("]", "|]");
    return input;
  }

  @SuppressWarnings("java:S106") // System.out is fine here, because it is the way to communicate with TC
  private void reportResults() throws ExecutionException, InterruptedException {
    var ruleSet = new HashSet<String>();
    for (Issue issue : issueList) {
      var ruleDetails = engine.getActiveRuleDetails(null, null, issue.getRuleKey(), null).get();
      var ruleKey = issue.getRuleKey();
      if (!ruleSet.contains(ruleKey)) {
        ruleSet.add(ruleKey);
        System.out.printf("##teamcity[inspectionType id='%s' name='%s' description='%s' category='%s']%n",
            ruleKey,
            escapeStringForTc(ruleKey + " - " + ruleDetails.getName()),
            escapeStringForTc(ruleDetails.getHtmlDescription()),
            ruleDetails.getType().name()
            );
      }
      System.out.printf("##teamcity[inspection typeId='%s' message='%s' file='%s' line='%d' severity='%s']%n",
          ruleKey,
          escapeStringForTc(issue.getMessage()),
          issue.getInputFile().relativePath(),
          issue.getStartLine() == null ? 0 : issue.getStartLine(),
          getSeverity(issue.getSeverity())
      );
    }
  }

  private void stopEngine() {
    engine.stop(false);
  }

  @SuppressWarnings("java:S106") // System.err is used to print usage information
  private void run(String[] args) throws IOException, ExecutionException, InterruptedException {
    if (args.length != 1) {
      System.err.println("Usage: java -jar sonarlint-cli.jar <path to sonar-project.properties>");
      System.exit(1);
    }
    parseConfiguration(args[0]);
    createEngine();
    getInputFiles();
    analyze();
    reportResults();
    stopEngine();
  }

  private static class InputFile implements ClientInputFile {

    private final Path file;

    public InputFile(Path file) {
      this.file = file;
    }

    @Override
    public String getPath() {
      return file.toAbsolutePath().toString();
    }

    @Override
    public boolean isTest() {
      return false;
    }

    @Override
    public Charset getCharset() {
      return null;
    }

    @Override
    public <G> G getClientObject() {
      return null;
    }

    @Override
    public InputStream inputStream() throws IOException {
      return new FileInputStream(file.toFile());
    }

    @Override
    public String contents() throws IOException {
      return Files.readString(file);
    }

    @Override
    public String relativePath() {
      return file.toString();
    }

    @Override
    public URI uri() {
      return file.toUri();
    }
  }
}
