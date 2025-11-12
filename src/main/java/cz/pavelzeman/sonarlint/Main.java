package cz.pavelzeman.sonarlint;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.sonarsource.sonarlint.core.ConfigurationService;
import org.sonarsource.sonarlint.core.analysis.AnalysisService;
import org.sonarsource.sonarlint.core.commons.log.LogOutput.Level;
import org.sonarsource.sonarlint.core.commons.log.SonarLintLogger;
import org.sonarsource.sonarlint.core.repository.rules.RulesRepository;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.config.binding.BindingConfigurationDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.config.scope.ConfigurationScopeDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.connection.config.SonarQubeConnectionConfigurationDto;
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
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;

public class Main {

  private static final String CONFIGURATION_SCOPE_ID = "sonarLintCliConfigurationScope";
  private static final String CONNECTION_ID = "sonarLintCliConnection";

  /**
   * Configuration read from input properties file.
   */
  private Configuration configuration;

  private SpringApplicationContextInitializer initializer;

  private List<ClientFileDto> inputFiles = new ArrayList<>();

  private static final Set<BackendCapability> disabledBackendCapabilities = Set.of(
      BackendCapability.EMBEDDED_SERVER,
      BackendCapability.FLIGHT_RECORDER,
      BackendCapability.ISSUE_STREAMING,
      BackendCapability.TELEMETRY,
      BackendCapability.MONITORING
  );

  private InitializeParams createInitializeParams() {
    return new InitializeParams(
        new ClientConstantInfoDto("SonarLint CLI", "SonarLint CLI"),
        new TelemetryClientConstantAttributesDto("sonarLintCli", "SonarLint CLI", "0.0.0", "0.0.0", null),
        new HttpConfigurationDto(
            new SslConfigurationDto(null, null, null, null, null, null),
            null,
            null,
            null,
            null
        ),
        null,
        new HashSet<>(Arrays.asList(BackendCapability.values()).stream().filter(c -> !disabledBackendCapabilities.contains(c)).toList()),
        Path.of(System.getProperty("user.home"), ".sonarlint-cli", "storage"),
        Path.of(System.getProperty("user.home"), ".sonarlint-cli", "work"),
        null,
        null,
        new HashSet<>(Arrays.asList(Language.values())),
        null,
        null,
        List.of(
            new SonarQubeConnectionConfigurationDto(CONNECTION_ID, configuration.getHost(), true)
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
        new BindingConfigurationDto(CONNECTION_ID, configuration.getProjectKey(), true)
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

  private void reportResults(SonarLintCliRpcClient client) {
    var rulesRepository = initializer.getInitializedApplicationContext().getBean(RulesRepository.class);

    var issues = client.getIssues();
    var rootPath = Path.of(configuration.getProjectBaseDir());
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

  private String getProperty(Properties properties, String property) {
    return properties.getProperty("sonar." + property);
  }

  private String getAbsolutePathProperty(Properties properties, String property) {
    var value = getProperty(properties, property);
    return value == null ? null : Path.of(value).toAbsolutePath().toString();
  }

  private String[] parseSources(String sources) {
    return sources == null ? null : Arrays.stream(sources.split(",")).map(String::trim).toArray(String[]::new);
  }

  private String[] parseExclusions(String exclusions) {
    return exclusions == null ? null : Arrays.stream(exclusions.split(",")).map(String::trim).map(string -> string.replace("/", File.separator)).toArray(String[]::new);
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
    configuration = new Configuration(
        getProperty(properties, Configuration.Properties.HOST),
        getProperty(properties, Configuration.Properties.TOKEN),
        getProperty(properties, Configuration.Properties.PROJECT_KEY),
        parseSources(getProperty(properties, Configuration.Properties.SOURCES)),
        getAbsolutePathProperty(properties, Configuration.Properties.PROJECT_BASE_DIR),
        parseExclusions(getProperty(properties, Configuration.Properties.EXCLUSIONS))
    );
  }

  private void getInputFiles() {
    inputFiles = new ArrayList<>();
    for (String sourcePathString : configuration.getSources()) {
      var sourcePath = Path.of(configuration.getProjectBaseDir(), sourcePathString);
      listFiles(sourcePath, sourcePath);
    }
  }

  private boolean isExcluded(Path file) {
    if (configuration.getExclusions() != null) {
      var matcher = new AntPathMatcher();
      for (String exclusion : configuration.getExclusions()) {
        if (matcher.match(exclusion, file.toString())) {
          return true;
        }
      }
    }
    return false;
  }

  private void listFiles(Path path, Path root) {
    try (var stream = Files.list(path)) {
      stream.forEach(file -> {
        if (Files.isDirectory(file)) {
          listFiles(file, root);
        } else if (!isExcluded(file)) {
          inputFiles.add(new ClientFileDto(
              file.toUri(),
              root.relativize(file), // get file path relative to root
              CONFIGURATION_SCOPE_ID,
              Boolean.FALSE,
              null,
              file,
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

  private SonarLintCliRpcClient analyze() {
    var client = new SonarLintCliRpcClient(configuration.getProjectBaseDir(), inputFiles, configuration.getToken());
    var params = createInitializeParams();
    SonarLintLogger.get().setTarget(new SonarLintCliLogOutput(client));
    SonarLintLogger.get().setLevel(Level.INFO);
    initializer = new SpringApplicationContextInitializer(client, params);
    var configurationService = initializer.getInitializedApplicationContext().getBean(ConfigurationService.class);
    client.resetConfigurationSynchronizationWait();
    configurationService.didAddConfigurationScopes(List.of(getConfigurationScope()));
    try {
      client.waitForConfigurationSynchronization();
    } catch (InterruptedException e) {
      throw new cz.pavelzeman.sonarlint.SonarLintException("Error when waiting for synchronization", e);
    }
    var analysisService = initializer.getInitializedApplicationContext().getBean(AnalysisService.class);
    var analysisId = analysisService.analyzeFullProject(CONFIGURATION_SCOPE_ID, false);
    client.waitForProgress(analysisId.toString());
    return client;
  }

  private void run(String... args) {
    if (args.length != 1) {
      System.err.println("Usage: java -jar sonarlint-cli.jar <path to sonar-project.properties>");
      System.exit(1);
    }
    parseConfiguration(args[0]);
    getInputFiles();
    var client = analyze();
    reportResults(client);
  }

  public static void main(String... args) {
    try {
      new Main().run(args);
    } catch (Throwable t) {
      t.printStackTrace();
      // The engine sometimes keeps running in a separate thread, so we have to force exit here
      System.exit(1);
    }
    // The engine sometimes keeps running in a separate thread, so we have to force exit here
    System.exit(0);
  }
}
