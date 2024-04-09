package cz.pavelzeman.sonarlint;

import static org.sonarsource.sonarlint.core.commons.log.ClientLogOutput.Level.INFO;

import java.io.BufferedReader;
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
import java.util.concurrent.ExecutionException;
import org.sonar.api.batch.fs.InputFile;
import org.sonarsource.sonarlint.core.ConnectedSonarLintEngineImpl;
import org.sonarsource.sonarlint.core.analysis.api.ClientInputFile;
import org.sonarsource.sonarlint.core.client.api.common.analysis.Issue;
import org.sonarsource.sonarlint.core.client.api.connected.ConnectedAnalysisConfiguration;
import org.sonarsource.sonarlint.core.client.api.connected.ConnectedGlobalConfiguration;
import org.sonarsource.sonarlint.core.commons.IssueSeverity;
import org.sonarsource.sonarlint.core.commons.Language;
import org.sonarsource.sonarlint.core.commons.Version;
import org.sonarsource.sonarlint.core.http.HttpClientProvider;
import org.sonarsource.sonarlint.core.serverapi.EndpointParams;

public class Main {

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
        throw new RuntimeException("Unexpected severity - " + severity);
    }
  }

  private static void listFiles(Path path, List<ClientInputFile> clientInputFiles) throws IOException {
    Files.list(path).forEach(file -> {
      if (Files.isDirectory(file)) {
        try {
          listFiles(file, clientInputFiles);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      } else {
        clientInputFiles.add(new InputFile(file));
      }
    });
  }

  public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
    new Main().run(args);
  }

  private String getProperty(Properties properties, String property) {
    return properties.getProperty("sonar." + property);
  }

  private String[] parseSources(String sources) {
    return Arrays.stream(sources.split(",")).map(String::trim).toArray(String[]::new);
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
        getProperty(properties, Configuration.Properties.PROJECT_BASE_DIR)
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
      throw new RuntimeException("Unsupported OS: " + os);
    }
    Process process = new ProcessBuilder(executable, "node").start();
    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
    return reader.readLine();
  }

  private void createEngine() throws IOException {
    var nodePath = getNodePath();
    var nodeVersion = getNodeVersion(nodePath);
    var sonarConfiguration = ConnectedGlobalConfiguration.sonarQubeBuilder()
        .addEnabledLanguages(Language.JS)
        .setConnectionId("sonarlint-cli")
        .setStorageRoot(getStorageRoot())
        .setNodeJs(Path.of(nodePath), Version.create(nodeVersion))
        .build();

    engine = new ConnectedSonarLintEngineImpl(sonarConfiguration);
    var httpClientProvider = new HttpClientProvider("sonarlint-cli", null, null, null, null);
    var endpointParams = new EndpointParams(configuration.getHost(), false, null);
    var httpClient = httpClientProvider.getHttpClientWithPreemptiveAuth(configuration.getToken(), null);
    //engine.sync(endpointParams, httpClient, Set.of(configuration.getProjectKey()), null);
  }

  private void getInputFiles() throws IOException {
    List<ClientInputFile> inputFiles = new ArrayList<>();
    for (String sourcePath : configuration.getSources()) {
      listFiles(Path.of(sourcePath), inputFiles);
    }
    this.inputFiles = inputFiles;
  }

  private void analyze() {
    var builder = ConnectedAnalysisConfiguration.builder();
    builder.setBaseDir(Path.of(configuration.getProjectBaseDir()));
    builder.addInputFiles(inputFiles);
    builder.setProjectKey(configuration.getProjectKey());

    issueList = new ArrayList<>();

    engine.analyze(builder.build(), issue -> {
      issueList.add(issue);
    }, (s, level) -> {
      if (level.ordinal() <= INFO.ordinal()) {
        System.out.println("Log: " + level + ": " + s);
      }
    }, null);
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
  private void reportResults() throws ExecutionException, InterruptedException {
    String lastFile = null;
    var ruleSet = new HashSet<String>();
    for (Issue issue : issueList) {
      if (!issue.getInputFile().relativePath().equals(lastFile)) {
        if (lastFile != null) {
          // System.out.println("</file>");
        }
        lastFile = issue.getInputFile().relativePath();
        // System.out.println("<file name=\"" + issue.getInputFile().relativePath() + "\">");
      }
      var ruleDetails = engine.getActiveRuleDetails(null, null, issue.getRuleKey(), null).get();
      var ruleKey = issue.getRuleKey();
      if (!ruleSet.contains(ruleKey)) {
        ruleSet.add(ruleKey);
        System.out.printf("##teamcity[inspectionType id='%s' name='%s' description='%s' category='%s']%n",
            ruleKey,
            escapeStringForTc(ruleDetails.getName()),
            escapeStringForTc(ruleDetails.getHtmlDescription()),
            ruleDetails.getType().name()
            );
      }
      System.out.printf("##teamcity[inspection typeId='%s' message='%s' file='%s' line='%d' severity='%s']%n",
          ruleKey,
          escapeStringForTc(issue.getMessage()),
          lastFile,
          issue.getStartLine(),
          getSeverity(issue.getSeverity())
      );
      // System.out.printf(
      //     "<violation beginline=\"%d\" endline=\"%d\" begincolumn=\"%d\" endcolumn=\"%d\" rule=\"%s\" ruleset=\"%s\" priority=\"%d\" externalInfoUrl=\"https://rules.sonarsource.com/java/RSPEC-2789\">%n",
      //     issue.getStartLine(), issue.getEndLine(), issue.getStartLineOffset(), issue.getEndLineOffset(), issue.getRuleKey(), ruleDetails.getLanguage().toString().toLowerCase(),
      //     getPriority(issue.getSeverity()));
      // System.out.println(issue.getMessage());
      // System.out.println("</violation>");
    }
    // System.out.println("</file>");
    System.out.println("Analysis finished");
  }

  private void stopEngine() {
    engine.stop(false);
  }

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
