package cz.pavelzeman.sonarlint;

/**
 * Customm exception not to use default {@link RuntimeException}.
 */
public class SonarLintException extends RuntimeException {

  public SonarLintException(String message) {
    super(message);
  }

  public SonarLintException(Throwable cause) {
    super(cause);
  }
}
