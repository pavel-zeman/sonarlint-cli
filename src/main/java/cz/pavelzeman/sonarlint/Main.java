package cz.pavelzeman.sonarlint;

import cz.pavelzeman.sonarlint.reporter.TeamCity;
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
import org.sonarsource.sonarlint.core.rpc.protocol.backend.connection.config.SonarCloudConnectionConfigurationDto;
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
import org.sonarsource.sonarlint.core.rpc.protocol.common.Language;
import org.sonarsource.sonarlint.core.rpc.protocol.common.SonarCloudRegion;
import org.sonarsource.sonarlint.core.spring.SpringApplicationContextInitializer;
import org.sonarsource.sonarlint.core.storage.StorageService;

@SuppressWarnings("java:S106") // This is a command line application, so using standard input/output is necessary
public class Main {

  /** Configuration scope ID used for the analysis. */
  private static final String CONFIGURATION_SCOPE_ID = "sonarLintCliConfigurationScope";

  /** Configuration read from input properties file. */
  private Configuration configuration;

  private SpringApplicationContextInitializer initializer;

  private SonarLintCliRpcClient client;

  private final List<ClientFileDto> inputFiles = new ArrayList<>();

  /** Exclusion filters from server configuration. */
  private ServerFileExclusions exclusionFilters;

  /** Blacklist of backend capabilities. It contains all items, which are not needed for CLI usage. */
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

  /**
   * Returns current version of the tool based on the MANIFEST.MF file.
   *
   * @return Current version.
   */
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

  /**
   * Returns root directory used to store SonarLint data.
   *
   * @return Sonarlint home.
   */
  private Path getSonarLintHome() {
    return Path.of(System.getProperty("user.home"), ".sonarlint-cli");
  }

  /**
   * Gets SonarCloud region based on the configured host URL. The logic considers just URL prefixes.
   *
   * @return SonarCloud region. If there is no region found, it throws an exception.
   */
  private SonarCloudRegion getSonarCloudRegion() {
    for (var region: org.sonarsource.sonarlint.core.SonarCloudRegion.values()) {
      if (configuration.host().startsWith(region.getProductionUri().toString())) {
        return SonarCloudRegion.valueOf(region.name());
      }
    }
    throw new SonarLintException("Invalid SonarCloud host URL: " + configuration.host());
  }

  /**
   * Returns connection ID. Currently, it is the same as the host URL.
   *
   * @return Connection ID.
   */
  private String getConnectionId() {
    return configuration.host();
  }

  /**
   * Creates initialization parameters.
   *
   * @return Initialization parameters.
   */
  private InitializeParams createInitializeParams() throws IOException {
    var version = getVersion();
    var sonarQubeConnection = configuration.organization() == null ? new SonarQubeConnectionConfigurationDto(getConnectionId(), configuration.host(), true) : null;
    var sonarCloudConnection = configuration.organization() == null ? null : new SonarCloudConnectionConfigurationDto(getConnectionId(), configuration.organization(), getSonarCloudRegion(), true);
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
        getSonarLintHome().resolve("storage"),
        getSonarLintHome().resolve("work"),
        null,
        null,
        // VBNET causes the analysis to fail due to missing Spring bean (this is caused by implementation of SLCORE-1898)
        new HashSet<>(Arrays.stream(Language.values()).filter(lang -> lang != Language.VBNET).toList()),
        null,
        null,
        sonarQubeConnection == null ? null : List.of(sonarQubeConnection),
        sonarCloudConnection == null ? null : List.of(sonarCloudConnection),
        null,
        null,
        false,
        null,
        false,
        null,
        LogLevel.TRACE
    );
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
   * Prepares exclusion filters based on server configuration.
   */
  private void prepareExclusionFilters() {
    var storageService = initializer.getInitializedApplicationContext().getBean(StorageService.class);
    var analyzerStorage = storageService.connection(getConnectionId()).project(configuration.projectKey()).analyzerConfiguration();
    var analyzerConfig = analyzerStorage.read();
    var settings = new MapSettings(analyzerConfig.getSettings().getAll());
    exclusionFilters = new ServerFileExclusions(settings.asConfig());
    exclusionFilters.prepare();
  }

  /**
   * Gets list of input files to analyze based on configuration. The list is stored to {@link #inputFiles}.
   * This has to be run after project synchronization, because it uses exclusion filters from project configuration.
   */
  private void getInputFiles() {
    prepareExclusionFilters();

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

    // Generate event, so that the analysis engine knows about the input files
    var fsService = initializer.getInitializedApplicationContext().getBean(ClientFileSystemService.class);
    fsService.didUpdateFileSystem(new DidUpdateFileSystemParams(inputFiles, Collections.emptyList(), Collections.emptyList()));
  }

  /**
   * Lists all files in given root path recursively and adds them to {@link #inputFiles}, if they are accepted by exclusion filters.
   *
   * @param currentPath current directory
   * @param root root directory used to relativize file paths
   * @param type file type (source or test)
   */
  private void listFiles(Path currentPath, Path root, InputFile.Type type) {
    try (var pathStream = Files.list(currentPath)) {
      pathStream.forEach(path -> {
        if (Files.isDirectory(path)) {
          listFiles(path, root, type);
        } else if (exclusionFilters.accept(root.relativize(path).toString(), type)) {
          inputFiles.add(new ClientFileDto(
              path.toUri(),
              root.relativize(path),
              CONFIGURATION_SCOPE_ID,
              type == Type.TEST,
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
  private void synchronizeConfiguration() throws IOException, InterruptedException {
    var params = createInitializeParams();
    initializer = new SpringApplicationContextInitializer(client, params);
    var configurationService = initializer.getInitializedApplicationContext().getBean(ConfigurationService.class);
    var configurationScope = new ConfigurationScopeDto(
        CONFIGURATION_SCOPE_ID,
        null,
        true,
        CONFIGURATION_SCOPE_ID,
        new BindingConfigurationDto(getConnectionId(), configuration.projectKey(), true)
    );
    // Generate configuration add event to start synchronization
    configurationService.didAddConfigurationScopes(List.of(configurationScope));
    client.waitForConfigurationSynchronization();
  }

  /**
   * Runs project analysis.
   */
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

  /**
   * Initializes Sonarlint logging based on the configured log level.
   */
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
    new TeamCity(initializer.getInitializedApplicationContext().getBean(RulesRepository.class), configuration).reportIssues(client.getIssues());
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
