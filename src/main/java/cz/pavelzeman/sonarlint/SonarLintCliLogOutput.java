package cz.pavelzeman.sonarlint;

import java.time.Instant;
import org.jetbrains.annotations.Nullable;
import org.sonarsource.sonarlint.core.commons.log.LogOutput;
import org.sonarsource.sonarlint.core.rpc.protocol.SonarLintRpcClient;
import org.sonarsource.sonarlint.core.rpc.protocol.client.log.LogLevel;
import org.sonarsource.sonarlint.core.rpc.protocol.client.log.LogParams;

public class SonarLintCliLogOutput implements LogOutput {

  private final SonarLintRpcClient client;

  public SonarLintCliLogOutput(SonarLintRpcClient client) {
    this.client = client;
  }

  @Override
  public void log(@Nullable String formattedMessage, Level level, @Nullable String stacktrace) {
    client.log(new LogParams(LogLevel.valueOf(level.name()), formattedMessage, null, stacktrace, Instant.now()));
  }
}
