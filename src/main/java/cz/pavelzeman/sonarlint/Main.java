package cz.pavelzeman.sonarlint;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Manifest;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.InputFile.Type;
import org.sonarsource.sonarlint.core.ConfigurationService;
import org.sonarsource.sonarlint.core.ServerFileExclusions;
import org.sonarsource.sonarlint.core.analysis.AnalysisService;
import org.sonarsource.sonarlint.core.commons.log.SonarLintLogger;
import org.sonarsource.sonarlint.core.fs.ClientFileSystemService;
import org.sonarsource.sonarlint.core.plugin.commons.sonarapi.MapSettings;
import org.sonarsource.sonarlint.core.repository.rules.RulesRepository;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.config.binding.BindingConfigurationDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.config.scope.ConfigurationScopeDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.connection.config.SonarQubeConnectionConfigurationDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.file.DidUpdateFileSystemParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.initialize.BackendCapability;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.initialize.ClientConstantInfoDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.initialize.HttpConfigurationDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.initialize.InitializeParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.initialize.SslConfigurationDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.initialize.TelemetryClientConstantAttributesDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.log.LogLevel;
import org.sonarsource.sonarlint.core.rpc.protocol.common.ClientFileDto;
import org.sonarsource.sonarlint.core.rpc.protocol.common.IssueSeverity;
import org.sonarsource.sonarlint.core.rpc.protocol.common.Language;
import org.sonarsource.sonarlint.core.spring.SpringApplicationContextInitializer;
import org.sonarsource.sonarlint.core.storage.StorageService;
import org.springframework.util.StringUtils;

@SuppressWarnings("java:S106") // This is a command line application, so using standard input/output is necessary
public class Main {

  private static final String CONFIGURATION_SCOPE_ID = "sonarLintCliConfigurationScope";
  private static final String CONNECTION_ID = "sonarLintCliConnection";

  /**
   * Configuration read from input properties file.
   */
  private Configuration configuration;

  private SpringApplicationContextInitializer initializer;

  private SonarLintCliRpcClient client;

  private List<ClientFileDto> inputFiles = new ArrayList<>();

  private ServerFileExclusions exclusionFilters;

  private static final Set<BackendCapability> disabledBackendCapabilities = Set.of(
      BackendCapability.EMBEDDED_SERVER,
      BackendCapability.FLIGHT_RECORDER,
      BackendCapability.ISSUE_STREAMING,
      BackendCapability.TELEMETRY,
      BackendCapability.GESSIE_TELEMETRY,
      BackendCapability.MONITORING,
      BackendCapability.SMART_NOTIFICATIONS,
      BackendCapability.SERVER_SENT_EVENTS
  );

  private String getVersion() throws IOException {
    var manifestResource = getClass().getResourceAsStream("/META-INF/MANIFEST.MF");
    var version = "unknown";
    if (manifestResource != null) {
      var manifest = new Manifest(manifestResource);
      var attrs = manifest.getMainAttributes();
      var manifestVersion = attrs.getValue("Implementation-Version");
      if (manifestVersion != null) {
        version = manifestVersion;
      }
    }
    return version;
  }

  private InitializeParams createInitializeParams() throws IOException {
    var version = getVersion();
    return new InitializeParams(
        new ClientConstantInfoDto("SonarLint CLI", "java"),
        new TelemetryClientConstantAttributesDto("sonarLintCli", "SonarLint CLI", version, version, null),
        new HttpConfigurationDto(
            new SslConfigurationDto(null, null, null, null, null, null),
            null,
            null,
            null,
            null
        ),
        null,
        new HashSet<>(Arrays.stream(BackendCapability.values()).filter(c -> !disabledBackendCapabilities.contains(c)).toList()),
        Path.of(System.getProperty("user.home"), ".sonarlint-cli", "storage"),
        Path.of(System.getProperty("user.home"), ".sonarlint-cli", "work"),
        null,
        null,
        new HashSet<>(Arrays.asList(Language.values())),
        null,
        null,
        List.of(
            new SonarQubeConnectionConfigurationDto(CONNECTION_ID, configuration.host(), true)
        ),
        null,
        null,
        null,
        false,
        null,
        false,
        null,
        LogLevel.TRACE
    );
  }

  private ConfigurationScopeDto getConfigurationScope() {
    return new ConfigurationScopeDto(
        CONFIGURATION_SCOPE_ID,
        null,
        true,
        CONFIGURATION_SCOPE_ID,
        new BindingConfigurationDto(CONNECTION_ID, configuration.projectKey(), true)
    );
  }

  public String escapeStringForTc(String input) {
    if (input == null) {
      input = "";
    }
    input = input.replace("|", "||");
    input = input.replace("\n", "|n");
    input = input.replace("\r", "|r");
    input = input.replace("'", "|'");
    input = input.replace("[", "|[");
    input = input.replace("]", "|]");
    return input;
  }

  private static String getSeverity(IssueSeverity severity) {
    if (severity == null) {
      throw new SonarLintException("Unexpected severity");
    } else {
      return switch (severity) {
        case BLOCKER, CRITICAL -> "ERROR";
        case MAJOR -> "WARNING";
        case MINOR, INFO -> "WEAK WARNING";
      };
    }
  }

  private void reportResults() {
    var rulesRepository = initializer.getInitializedApplicationContext().getBean(RulesRepository.class);

    var issues = client.getIssues();
    var rootPath = Path.of(configuration.projectBaseDir());
    var ruleSet = new HashSet<String>();
    for (var issueEntry : issues.entrySet()) {
      var fileUri = issueEntry.getKey();
      var absoluteFilePath = Path.of(fileUri);
      var relativeFilePath = rootPath.relativize(absoluteFilePath);
      for (var issue : issueEntry.getValue()) {
        var ruleKey = issue.getRuleKey();
        if (!ruleSet.contains(ruleKey)) {
          ruleSet.add(ruleKey);
          var rule = rulesRepository.getRule(CONNECTION_ID, ruleKey).get();
          System.out.printf("##teamcity[inspectionType id='%s' name='%s' description='%s' category='%s']%n",
              ruleKey,
              escapeStringForTc(ruleKey + " - " + rule.getName()),
              // Description is mandatory, so use name as description, if name is not available
              escapeStringForTc(StringUtils.hasText(rule.getHtmlDescription()) ? rule.getHtmlDescription() : rule.getName()),
              rule.getType().name()
          );
        }
        var severityMode = issue.getSeverityMode();
        System.out.printf("##teamcity[inspection typeId='%s' message='%s' file='%s' line='%d' severity='%s']%n",
            ruleKey,
            escapeStringForTc(issue.getPrimaryMessage()),
            relativeFilePath,
            issue.getTextRange() == null ? 0 : issue.getTextRange().getStartLine(),
            getSeverity(severityMode.isLeft() ? severityMode.getLeft().getSeverity() : null)
        );
      }
    }
  }

  /**
   * Reads configuration from given file.
   *
   * @param file file to read configuration from
   */
  private void parseConfiguration(String file) {
    var properties = new Properties();
    try (var inputStream = new FileInputStream(file)) {
      properties.load(inputStream);
    } catch (IOException e) {
      throw new SonarLintException("Error when reading configuration file", e);
    }
    configuration = Configuration.create(properties);
  }

  /**
   * Gets list of input files to analyze based on configuration. The list is stored in {@link #inputFiles}.
   */
  private void getInputFiles() {
    var storageService = initializer.getInitializedApplicationContext().getBean(StorageService.class);
    var analyzerStorage = storageService.connection(CONNECTION_ID).project(configuration.projectKey()).analyzerConfiguration();
    var analyzerConfig = analyzerStorage.read();
    var settings = new MapSettings(analyzerConfig.getSettings().getAll());
    exclusionFilters = new ServerFileExclusions(settings.asConfig());
    exclusionFilters.prepare();

    inputFiles = new ArrayList<>();

    for (var sourcePathString : configuration.sources()) {
      var sourcePath = Path.of(configuration.projectBaseDir(), sourcePathString);
      listFiles(sourcePath, sourcePath, Type.MAIN);
    }

    if (configuration.tests() != null) {
      for (var testPathString : configuration.tests()) {
        var testPath = Path.of(configuration.projectBaseDir(), testPathString);
        listFiles(testPath, testPath, Type.TEST);
      }
    }

    var fsService = initializer.getInitializedApplicationContext().getBean(ClientFileSystemService.class);
    fsService.didUpdateFileSystem(new DidUpdateFileSystemParams(inputFiles, Collections.emptyList(), Collections.emptyList()));
  }

  private void listFiles(Path rootPath, Path root, InputFile.Type type) {
    try (var pathStream = Files.list(rootPath)) {
      pathStream.forEach(path -> {
        if (Files.isDirectory(path)) {
          listFiles(path, root, type);
        } else if (exclusionFilters.accept(root.relativize(path).toString(), type)) {
          inputFiles.add(new ClientFileDto(
              path.toUri(),
              root.relativize(path),
              CONFIGURATION_SCOPE_ID,
              Boolean.FALSE,
              null,
              path,
              null,
              null,
              true
          ));
        }
      });
    } catch (IOException e) {
      throw new SonarLintException("Error when getting list of files to analyze", e);
    }
  }

  /**
   * Connects to SonarQube server and synchronizes configuration.
   */
  private void synchronizeConfiguration() throws IOException {
    var params = createInitializeParams();
    initializer = new SpringApplicationContextInitializer(client, params);
    var configurationService = initializer.getInitializedApplicationContext().getBean(ConfigurationService.class);
    client.resetConfigurationSynchronizationWait();
    configurationService.didAddConfigurationScopes(List.of(getConfigurationScope()));
    try {
      client.waitForConfigurationSynchronization();
    } catch (InterruptedException e) {
      throw new SonarLintException("Error when waiting for synchronization", e);
    }
  }

  private void analyze() {
    var analysisService = initializer.getInitializedApplicationContext().getBean(AnalysisService.class);
    var analysisId = analysisService.analyzeFullProject(CONFIGURATION_SCOPE_ID, false);
    client.waitForProgress(analysisId.toString());
  }

  /**
   * Gets name of the JAR file, that this class is running from.
   * @return JAR file name (during development, it can be also a directory, but when built, it is always a JAR file)
   */
  private String getJarName() {
    var file = new File(getClass().getProtectionDomain().getCodeSource().getLocation().getPath());
    return file.getName();
  }

  private void initializeLogging() {
    var sonarLintLogger = SonarLintLogger.get();
    sonarLintLogger.setTarget(client);
    sonarLintLogger.setLevel(configuration.logLevel());
  }

  private void run(String... args) throws Exception {
    if (args.length != 1) {
      System.err.printf("Usage: java -jar %s <path to sonar-project.properties>", getJarName());
      System.exit(1);
    }
    parseConfiguration(args[0]);
    client = new SonarLintCliRpcClient(configuration.projectBaseDir(), configuration.token());
    initializeLogging();
    synchronizeConfiguration();
    getInputFiles();
    analyze();
    reportResults();
    initializer.close();
  }

  public static void main(String... args) {
    try {
      new Main().run(args);
    } catch (Exception e) {
      // We need to catch any exception, so that we can explicitly terminate the application (without termination, the engine keeps running in the background)
      //noinspection CallToPrintStackTrace we need to print stack trace manually before exiting
      e.printStackTrace();
      System.exit(1);
    }
    // The engine sometimes keeps running in a separate thread, so we have to force exit here
    System.exit(0);
  }
}
