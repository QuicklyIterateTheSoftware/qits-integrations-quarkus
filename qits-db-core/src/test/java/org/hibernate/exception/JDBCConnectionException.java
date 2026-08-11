package org.hibernate.exception;

import java.sql.SQLException;

/**
 * A MIRROR of Hibernate's type, not Hibernate's type. This module matches it by fully-qualified
 * name and depends on no ORM (see the pom), so the suite has to supply the bytecode itself.
 *
 * <p>This file is the contract the name match rests on. If Hibernate ever moves or renames the
 * class, the rule stops matching silently in every consumer — and this is the one place a person
 * looking for the coupling will find it.
 *
 * <p>Only what the tests construct is here: the message-and-root pair, and a message-only form that
 * isolates the name match from the SQLState match.
 */
public class JDBCConnectionException extends RuntimeException {

  public JDBCConnectionException(String message) {
    super(message);
  }

  public JDBCConnectionException(String message, SQLException root) {
    super(message, root);
  }
}
