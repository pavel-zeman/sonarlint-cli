package cz.pavelzeman.sonarlint;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonarsource.sonarlint.core.commons.log.LogOutput;
import org.sonarsource.sonarlint.core.rpc.protocol.SonarLintRpcClient;
import org.sonarsource.sonarlint.core.rpc.protocol.client.OpenUrlInBrowserParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.analysis.DidChangeAnalysisReadinessParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.analysis.GetFileExclusionsParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.analysis.GetFileExclusionsResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.client.analysis.GetInferredAnalysisPropertiesParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.analysis.GetInferredAnalysisPropertiesResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.client.binding.AssistBindingParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.binding.AssistBindingResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.client.binding.NoBindingSuggestionFoundParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.binding.SuggestBindingParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.branch.DidChangeMatchedSonarProjectBranchParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.branch.MatchSonarProjectBranchParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.branch.MatchSonarProjectBranchResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.client.connection.AssistCreatingConnectionParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.connection.AssistCreatingConnectionResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.client.connection.GetCredentialsParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.connection.GetCredentialsResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.client.connection.SuggestConnectionParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.event.DidReceiveServerHotspotEvent;
import org.sonarsource.sonarlint.core.rpc.protocol.client.fs.GetBaseDirParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.fs.GetBaseDirResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.client.fs.ListFilesParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.fs.ListFilesResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.client.hotspot.RaiseHotspotsParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.hotspot.ShowHotspotParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.http.CheckServerTrustedParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.http.CheckServerTrustedResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.client.http.GetProxyPasswordAuthenticationParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.http.GetProxyPasswordAuthenticationResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.client.http.SelectProxiesParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.http.SelectProxiesResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.client.info.GetClientLiveInfoResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.client.issue.RaiseIssuesParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.issue.RaisedFindingDto;
import org.sonarsource.sonarlint.core.rpc.protocol.client.issue.ShowIssueParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.log.LogLevel;
import org.sonarsource.sonarlint.core.rpc.protocol.client.log.LogParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.message.ShowMessageParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.message.ShowSoonUnsupportedMessageParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.progress.ReportProgressParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.progress.StartProgressParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.sca.DidChangeDependencyRisksParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.smartnotification.ShowSmartNotificationParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.sync.DidSynchronizeConfigurationScopeParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.taint.vulnerability.DidChangeTaintVulnerabilitiesParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.telemetry.TelemetryClientLiveAttributesResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.common.TokenDto;
import org.springframework.util.StringUtils;

/**
 * Implementation of the back-end client. Only methods needed by CLI are implemented.
 */
public class SonarLintCliRpcClient implements SonarLintRpcClient, LogOutput {

  private static final Logger logger = LoggerFactory.getLogger(SonarLintCliRpcClient.class);

  /** Latch to wait for configuration synchronization. */
  private final CountDownLatch configurationSynchronizationLatch = new CountDownLatch(1);

  /** IDs of finished progress tasks, so that we can wait for them. */
  private final Set<String> finishedProgressTaskIds = new HashSet<>();

  /** Map of raised issues indexed by file URI. */
  private final Map<URI, Collection<RaisedFindingDto>> issues = new HashMap<>();

  private final String projectBaseDir;

  private final String token;

  public SonarLintCliRpcClient(String projectBaseDir, String token) {
    this.projectBaseDir = projectBaseDir;
    this.token = token;
  }

  public Map<URI, Collection<RaisedFindingDto>> getIssues() {
    return issues;
  }

  @Override
  public void log(LogParams params) {
    var localLogger = LoggerFactory.getLogger(params.getLoggerName());
    var level = org.slf4j.event.Level.valueOf(params.getLevel().name());
    if (localLogger.isEnabledForLevel(level)) {
      var message = StringUtils.hasText(params.getMessage()) ? params.getMessage() : "";
      var stacktrace = StringUtils.hasText(params.getStackTrace()) ? params.getStackTrace() : "";
      localLogger.atLevel(level).setMessage(message + stacktrace).log();
    }
  }

  /**
   * Implementation of {@link LogOutput} interface.
   */
  @Override
  public void log(@Nullable String formattedMessage, Level level, @Nullable String stacktrace) {
    log(new LogParams(LogLevel.valueOf(level.name()), formattedMessage, null, stacktrace, Instant.now()));
  }

  @Override
  public CompletableFuture<Void> startProgress(StartProgressParams params) {
    logger.info("Starting progress {} with id {}", params.getTitle(), params.getTaskId());
    return getCompletedFuture(null);
  }

  @Override
  @SuppressWarnings("java:S2446") // notify is correct here, there is just a single waiting thread
  public synchronized void reportProgress(ReportProgressParams params) {
    if (params.getNotification().isLeft()) {
      var updateNotification = params.getNotification().getLeft();
      logger.info("Progress id {} status {} message {}", params.getTaskId(), updateNotification.getPercentage(), updateNotification.getMessage());
    } else {
      finishedProgressTaskIds.add(params.getTaskId());
      logger.info("Progress id {} ended", params.getTaskId());
      notify();
    }
  }

  /**
   * Waits for given progress task to finish.
   *
   * @param taskId task ID
   */
  public synchronized void waitForProgress(String taskId) {
    while (!finishedProgressTaskIds.contains(taskId)) {
      try {
        wait();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new SonarLintException("Interrupted while waiting for progress " + taskId, e);
      }
    }
    finishedProgressTaskIds.remove(taskId);
  }

  @Override
  public void didSynchronizeConfigurationScopes(DidSynchronizeConfigurationScopeParams params) {
    logger.info("Configuration scopes synchronized {}", params.getConfigurationScopeIds());
    configurationSynchronizationLatch.countDown();
  }

  /**
   * Waits for configuration synchronization to complete.
   */
  public void waitForConfigurationSynchronization() throws InterruptedException {
    // The configuration is synchronized in a separate thread and if it fails, we don't get any notification. As a result, we wait with a timeout here.
    if (!configurationSynchronizationLatch.await(2, TimeUnit.MINUTES)) {
      throw new SonarLintException("Timeout waiting for configuration synchronization");
    }
  }

  @Override
  public CompletableFuture<GetCredentialsResponse> getCredentials(@NotNull GetCredentialsParams params) {
    return getCompletedFuture(new GetCredentialsResponse(new TokenDto(token)));
  }

  @Override
  public CompletableFuture<SelectProxiesResponse> selectProxies(@NotNull SelectProxiesParams params) {
    return getCompletedFuture(new SelectProxiesResponse(List.of()));
  }

  @Override
  public CompletableFuture<MatchSonarProjectBranchResponse> matchSonarProjectBranch(MatchSonarProjectBranchParams params) {
    return getCompletedFuture(new MatchSonarProjectBranchResponse(params.getMainSonarBranchName()));
  }

  @Override
  public CompletableFuture<GetBaseDirResponse> getBaseDir(@NotNull GetBaseDirParams params) {
    return getCompletedFuture(new GetBaseDirResponse(Path.of(projectBaseDir)));
  }

  @Override
  public CompletableFuture<ListFilesResponse> listFiles(@NotNull ListFilesParams params) {
    // Return empty list here, we will add the files later
    return getCompletedFuture(new ListFilesResponse(Collections.emptyList()));
  }

  @Override
  public void didChangeAnalysisReadiness(DidChangeAnalysisReadinessParams params) {
    logger.info("Analysis readiness changed to {}", params.areReadyForAnalysis());
  }

  @Override
  public CompletableFuture<GetInferredAnalysisPropertiesResponse> getInferredAnalysisProperties(@NotNull GetInferredAnalysisPropertiesParams params) {
    return getCompletedFuture(new GetInferredAnalysisPropertiesResponse(Map.of()));
  }

  @Override
  public void raiseHotspots(RaiseHotspotsParams params) {
    for (var hotspotEntry : params.getHotspotsByFileUri().entrySet()) {
      var fileUri = hotspotEntry.getKey();
      var fileIssues = hotspotEntry.getValue();
      // Input parameters contain all files regardless of whether there are issues in them or not
      if (!fileIssues.isEmpty()) {
        var existingIssues = issues.computeIfAbsent(fileUri, k -> new ArrayList<>());
        existingIssues.addAll(hotspotEntry.getValue());
      }
    }
  }

  @Override
  public void raiseIssues(RaiseIssuesParams params) {
    for (var issuesEntry : params.getIssuesByFileUri().entrySet()) {
      var fileUri = issuesEntry.getKey();
      var fileIssues = issuesEntry.getValue();
      // Input parameters contain all files regardless of whether there are issues in them or not
      if (!fileIssues.isEmpty()) {
        var existingIssues = issues.computeIfAbsent(fileUri, k -> new ArrayList<>());
        existingIssues.addAll(fileIssues);
      }
    }
  }

  /**
   * Creates a completed future with given value.
   *
   * @param value future value
   * @return Completed future.
   */
  private <T> CompletableFuture<T> getCompletedFuture(T value) {
    var result = new CompletableFuture<T>();
    result.complete(value);
    return result;
  }

  @Override
  public void suggestBinding(@NotNull SuggestBindingParams params) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void suggestConnection(@NotNull SuggestConnectionParams params) {
    throw new UnsupportedOperationException();

  }

  @Override
  public void openUrlInBrowser(@NotNull OpenUrlInBrowserParams params) {
    throw new UnsupportedOperationException();

  }

  @Override
  public void showMessage(@NotNull ShowMessageParams params) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void showSoonUnsupportedMessage(@NotNull ShowSoonUnsupportedMessageParams params) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void showSmartNotification(@NotNull ShowSmartNotificationParams params) {
    throw new UnsupportedOperationException();
  }

  @Override
  public CompletableFuture<GetClientLiveInfoResponse> getClientLiveInfo() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void showHotspot(@NotNull ShowHotspotParams params) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void showIssue(@NotNull ShowIssueParams params) {
    throw new UnsupportedOperationException();
  }

  @Override
  public CompletableFuture<AssistCreatingConnectionResponse> assistCreatingConnection(@NotNull AssistCreatingConnectionParams params) {
    throw new UnsupportedOperationException();
  }

  @Override
  public CompletableFuture<AssistBindingResponse> assistBinding(@NotNull AssistBindingParams params) {
    throw new UnsupportedOperationException();
  }

  @Override
  public CompletableFuture<TelemetryClientLiveAttributesResponse> getTelemetryLiveAttributes() {
    throw new UnsupportedOperationException();
  }

  @Override
  public CompletableFuture<GetProxyPasswordAuthenticationResponse> getProxyPasswordAuthentication(@NotNull GetProxyPasswordAuthenticationParams params) {
    throw new UnsupportedOperationException();
  }

  @Override
  public CompletableFuture<CheckServerTrustedResponse> checkServerTrusted(@NotNull CheckServerTrustedParams params) {
    return getCompletedFuture(new CheckServerTrustedResponse(true));
  }

  @Override
  public void didReceiveServerHotspotEvent(@NotNull DidReceiveServerHotspotEvent params) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void didChangeMatchedSonarProjectBranch(@NotNull DidChangeMatchedSonarProjectBranchParams params) {
    throw new UnsupportedOperationException();
  }
  @Override
  public void didChangeTaintVulnerabilities(@NotNull DidChangeTaintVulnerabilitiesParams params) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void didChangeDependencyRisks(@NotNull DidChangeDependencyRisksParams params) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void noBindingSuggestionFound(@NotNull NoBindingSuggestionFoundParams params) {
    throw new UnsupportedOperationException();
  }

  @Override
  public CompletableFuture<GetFileExclusionsResponse> getFileExclusions(@NotNull GetFileExclusionsParams params) {
    throw new UnsupportedOperationException();
  }
}
