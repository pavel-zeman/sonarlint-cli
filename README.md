# SonarLint CLI [![Build](https://github.com/pavel-zeman/sonarlint-cli/actions/workflows/maven.yml/badge.svg?branch=main)](https://github.com/pavel-zeman/sonarlint-cli/actions/workflows/maven.yml)

A command-line utility to run [SonarLint](https://www.sonarsource.com/products/sonarqube/ide/) analysis using configuration from SonarQube or SonarCloud server, with output in [TeamCity](https://www.jetbrains.com/teamcity/) inspection format. The functionality is similar to [sonarlint-cli](https://github.com/SonarSource/sonarlint-cli), which is now deprecated as explained by a SonarQube representative in [this discussion](https://groups.google.com/g/sonarqube/c/WlALjVzp-OE/m/Ev3QpnaOBAAJ).


## Motivation
SonarLint is a popular static code analysis tool integrated into several IDEs. It automatically inspects code based on configurations defined typically by SonarQube/SonarCloud server.
However, there is currently no way, how to run SonarLint analysis from command line, which is useful especially in CI/CD pipelines.
This project fills that gap by providing a CLI tool that fetches configuration from SonarQube/SonarCloud server, runs SonarLint analysis and outputs results in a format compatible with TeamCity CI/CD tool.

## Features
- Fetches analysis configuration from SonarQube/SonarCloud server
- Supports authentication via token
- Outputs issues in TeamCity-compatible format for CI integration
- Reads configuration from a `.properties` file (compatible with SonarQube/SonarCloud)

## Prerequisites
- Java Runtime Environment (JRE) 21 or newer

## Configuration
Create a properties file (e.g. `sonar-project.properties`) with the following properties:

| Property                   | Description                                                                   | Required            | Default value             |
|----------------------------|-------------------------------------------------------------------------------|---------------------|---------------------------|
| `sonar.host.url`           | SonarQube/SonarCloud server URL                                               | Yes                 | -                         |
| `sonar.token`              | Authentication token                                                          | Yes                 | -                         |
| `sonar.projectKey`         | Project key                                                                   | Yes                 | -                         |
| `sonar.organization`       | Organization key                                                              | For SonarCloud only | -                         |
| `sonar.projectBaseDir`     | Path to project base directory                                                | No                  | Current working directory |
| `sonar.sources`            | Comma-separated list of source directories relative to `sonar.projectBaseDir` | No                  | `sonar.projectBaseDir`    |
| `sonar.tests`              | Comma-separated list of test directories relative to `sonar.projectBaseDir`   | No                  | Empty (no test sources)   |
| `sonar.log.level`          | Log level (one of `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `OFF`)           | No                  | INFO                      |

All lines starting with `#` are treated as comments and ignored.

Sample properties file is as follows:

```properties
# SonarCloud URL
sonar.host.url=https://sonarcloud.io
sonar.token=8371623874687468723321947328746
sonar.projectKey=my-test-project
# Organization is required, as we use SonarCloud
sonar.organization=my-organization
sonar.sources=src,demo
sonar.tests=test
```

Alternatively, all properties can be also set in one of the following ways:
- Java system properties - use the property names from the table above when starting Java, e.g. `-Dsonar.host.url=...`
- Environment variables - in this case, the property names must be in upper case with dots replaced by underscores, e.g. `SONAR_HOST_URL=...`

If there is a conflict, i.e. a property value is set in multiple ways, the precedence is as follows (from highest to lowest):
1. Java system properties
2. Environment variables
3. Properties file


## Usage
The tool is distributed as a single JAR file, which can be run as follows:
```sh
java -jar sonarlint-cli-<version>.jar <path to properties file>
```

Example:
```sh
java -jar sonarlint-cli-1.0.0.jar sonar-project.properties
```

Identified issues are printed to standard output in TeamCity inspection format, suitable for integration with TeamCity CI. 

## Local configuration cache
When starting up, the tool creates a local cache directory `.sonarlint-cli` in the user's home directory. 
This cache is used to store configuration and analyzer binaries downloaded from the SonarQube/SonarCloud server, improving performance on subsequent runs. It also stores temporary files created during analysis.
If you delete the cache directory, it will be automatically recreated on the next run.

## Limitations
- Output is tailored for TeamCity (contact me, if you need other formats)
- Only server-based configuration is supported (no local rule configuration)
- VB.NET analysis is currently not supported, because the SonarLint library fails during startup, when VB.NET is enabled
- When started for the first time (or after deleting the local configuration cache), the tool needs to download analyzer binaries from the server, which may take some time depending especially on network connectivity.
If it does not finish within reasonable time (currently 2 minutes), the tool fails with a timeout error. In such case, you can simply re-run the tool, which will continue downloading the remaining binaries.
- Only the configuration properties listed above are supported. All other properties (e.g. source file exclusions) need to be configured in the SonarQube/SonarCloud project configuration.

## License
Licensed under the [GNU LGPL v3](LICENSE.txt).

