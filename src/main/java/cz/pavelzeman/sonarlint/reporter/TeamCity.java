package cz.pavelzeman.sonarlint.reporter;

import cz.pavelzeman.sonarlint.Configuration;
import cz.pavelzeman.sonarlint.SonarLintException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.sonarsource.sonarlint.core.repository.rules.RulesRepository;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.rules.ImpactDto;
import org.sonarsource.sonarlint.core.rpc.protocol.client.issue.RaisedFindingDto;
import org.sonarsource.sonarlint.core.rpc.protocol.common.ImpactSeverity;
import org.sonarsource.sonarlint.core.rpc.protocol.common.IssueSeverity;
import org.springframework.util.StringUtils;

/**
 * Reporter for TeamCity CI server.
 */
@SuppressWarnings("java:S106") // Report is generated to standard output, so using standard output is necessary
public class TeamCity {

  private final RulesRepository rulesRepository;

  private final Configuration configuration;

  /** Set of all violated rules indexed by rule key (e.g. javascript:S1234). */
  private final Set<String> ruleSet;

  public TeamCity(RulesRepository rulesRepository, Configuration configuration) {
    this.rulesRepository = rulesRepository;
    this.configuration = configuration;
    ruleSet = new HashSet<>();
  }

  /**
   * Escapes string for TeamCity output using '|' character.
   *
   * @param input string to escape
   * @return Escaped string.
   */
  private String escapeString(String input) {
    if (input == null) {
      input = "";
    }
    return input
        .replace("|", "||")
        .replace("\n", "|n")
        .replace("\r", "|r")
        .replace("'", "|'")
        .replace("[", "|[")
        .replace("]", "|]");
  }

  /**
   * Converts severity returned by Sonarlint to TeamCity severity.
   *
   * @param severity severity in standard experience mode
   * @param impacts list of impacts in MQR mode
   * @return TeamCity severity.
   */
  private String getSeverity(IssueSeverity severity, List<ImpactDto> impacts) {
    if (impacts != null) {
      // Convert maximum impact severity to issue severity
      var maxImpactSeverity = ImpactSeverity.INFO;
      for (var impact : impacts) {
        if (impact.getImpactSeverity().ordinal() > maxImpactSeverity.ordinal()) {
          maxImpactSeverity = impact.getImpactSeverity();
        }
      }
      severity = switch (maxImpactSeverity) {
        case BLOCKER -> IssueSeverity.BLOCKER;
        case HIGH -> IssueSeverity.CRITICAL;
        case MEDIUM -> IssueSeverity.MAJOR;
        case LOW -> IssueSeverity.MINOR;
        case INFO -> IssueSeverity.INFO;
      };
    }
    if (severity == null) {
      throw new SonarLintException("Invalid issue severity (null)");
    }
    return switch (severity) {
      case BLOCKER, CRITICAL -> "ERROR";
      case MAJOR -> "WARNING";
      case MINOR, INFO -> "WEAK WARNING";
    };
  }

  /**
   * Outputs issues to standard output.
   *
   * @param issues map of issues
   */
  public void reportIssues(Map<URI, Collection<RaisedFindingDto>> issues) {
    var rootPath = Path.of(configuration.projectBaseDir());
    for (var issueEntry : issues.entrySet()) {
      var fileUri = issueEntry.getKey();
      var absoluteFilePath = Path.of(fileUri);
      var relativeFilePath = rootPath.relativize(absoluteFilePath);
      for (var issue : issueEntry.getValue()) {
        reportIssue(issue, relativeFilePath);
      }
    }
  }

  @SuppressWarnings("java:S2259") // issue.getTextRange() cannot be null, because we check for null just before using it
  private void reportIssue(RaisedFindingDto issue, Path relativeFilePath) {
    var ruleKey = issue.getRuleKey();
    if (!ruleSet.contains(ruleKey)) {
      // Each rule must be output exactly once
      ruleSet.add(ruleKey);
      @SuppressWarnings({"java:S3655", "OptionalGetWithoutIsPresent"}) // We know that the rule is present, because the issue refers to it
      var rule = rulesRepository.getRule(configuration.host(), ruleKey).get();
      System.out.printf("##teamcity[inspectionType id='%s' name='%s' description='%s' category='%s']%n",
          ruleKey,
          escapeString(ruleKey + " - " + rule.getName()),
          // Description is mandatory, so use name as description, if name is not available
          escapeString(StringUtils.hasText(rule.getHtmlDescription()) ? rule.getHtmlDescription() : rule.getName()),
          rule.getType().name()
      );
    }
    // Output the issue itself
    var severityMode = issue.getSeverityMode();
    System.out.printf("##teamcity[inspection typeId='%s' message='%s' file='%s' line='%d' severity='%s']%n",
        ruleKey,
        escapeString(issue.getPrimaryMessage()),
        relativeFilePath,
        issue.getTextRange() == null ? 0 : issue.getTextRange().getStartLine(),
        getSeverity(severityMode.isLeft() ? severityMode.getLeft().getSeverity() : null, severityMode.isRight() ? severityMode.getRight().getImpacts() : null)
    );
  }
}
