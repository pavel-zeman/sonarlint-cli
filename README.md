Simple tool allowing to run [sonarlint](https://www.sonarsource.com/products/sonarlint/) from command line 
based on configuration from SonarQube server and output identified issues to standard output.

The functionality is similar to [sonarlint-cli](https://github.com/SonarSource/sonarlint-cli), which is now deprecated
as explained by a SonarQube representative in [this discussion](https://groups.google.com/g/sonarqube/c/WlALjVzp-OE/m/Ev3QpnaOBAAJ).

# Usage
The tool is distributed as a single JAR file, which can be run using Java version 17+ as follows:
```
java -jar sonarlint-cli-<version>.jar <path to configuration file>
```

The configuration file is a simple properties file supporting a subset of properties of 
the [SonarQube scanner](https://docs.sonarsource.com/sonarqube/latest/analyzing-source-code/analysis-parameters/).
The supported properties are as follows:
* `sonar.host.url` - URL of the SonarQube server used as a source of the analysis configuration
* `sonar.token` - authentication token to connect the SonarQube server
* `sonar.projectKey` - identification of a SonarQube project used as a source of the analysis configuration
* `sonar.sources` - comma separated list of directories containing source files to analyze
  (directory can be specified using absolute path or relative to current directory)
* `sonar.exclusions` - comma separate list of ant path expressions matching files to exclude from analysis
* `sonar.projectBaseDir` - base directory

All identified issues are written to standard output in the format supported by [TeamCity](https://www.jetbrains.com/teamcity/).

# Limitations
The tool is currently in an alpha versions, and it has the following limitations:
* Output tailored to TeamCity.
* There is no support for test sources, all the sources are treated the same (i.e. as production sources).
* There is no support for specification of Java binaries. As a result, the analysis of Java code may miss some problems.
* The list of supported programming languages, which can be analyzed, is hardcoded to JS, TS, CSS, HTML, JAVA, XML, YAML and JSON.
* Analysis configuration can only be read from SonarQube server. 
  It is not possible to specify analysis rules locally, neither use SonarCloud.