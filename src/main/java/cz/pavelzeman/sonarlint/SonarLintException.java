package cz.pavelzeman.sonarlint;

/**
 * Custom exception not to use default {@link RuntimeException}.
 */
public class SonarLintException extends RuntimeException {

  public SonarLintException(String message) {
    super(message);
  }

  public SonarLintException(String message, Throwable cause) {
    super(message, cause);
  }
}
