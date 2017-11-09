package tech.toparvion.analog.service.tail;

/**
 * @author Toparvion
 * @since v0.7
 */
public class UnrecognizedTailEventException extends Exception {

  public UnrecognizedTailEventException(String message) {
    super(message);
  }
}