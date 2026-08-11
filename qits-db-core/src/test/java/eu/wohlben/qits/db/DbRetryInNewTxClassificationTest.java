package eu.wohlben.qits.db;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.transaction.HeuristicMixedException;
import jakarta.transaction.RollbackException;
import jakarta.transaction.SystemException;
import java.sql.SQLException;
import javax.transaction.xa.XAException;
import org.junit.jupiter.api.Test;

/**
 * The classification on its own, one outcome at a time.
 *
 * <p>{@link DbRetryInNewTxTest} proves the two ends of this on a real wire — a statement-phase loss
 * retried, a commit-phase loss rethrown — but it can only produce the outcomes postgres and Narayana
 * happen to produce on this host. Heuristics and XA ambiguity belong to a transaction manager the
 * fleet has not grown into yet, so they are pinned here rather than left to be discovered.
 *
 * <p>Every case on the transaction manager's side answers the same way, and that uniformity is the
 * rule rather than an accident: see {@link DbRetry#retriableInNewTx} for the measurement that
 * collapsed them.
 */
class DbRetryInNewTxClassificationTest {

  /** A lost connection, as pgjdbc reports one. */
  private static SQLException lostConnection() {
    return new SQLException("An I/O error occurred while sending to the backend.", "08006");
  }

  @Test
  void aLostConnectionOutOfTheBodyIsRetried() {
    // Quarkus rolls a failed body back before this ever escapes, so "the body threw it" and
    // "nothing was committed" are one statement.
    assertTrue(DbRetry.retriableInNewTx(new RuntimeException(lostConnection()), true));
  }

  @Test
  void theSameLostConnectionOutOfTheCommitIsRethrown() {
    // One round trip later the failure is undecidable: the database may have committed and lost the
    // answer on the way back.
    assertFalse(DbRetry.retriableInNewTx(new RuntimeException(lostConnection()), false));
  }

  @Test
  void aRollbackTheTransactionManagerClaimsIsNotEvidence() {
    // Narayana answers an undeliverable commit with a RollbackException, and answers a genuine
    // rollback-before-commit with the same type. Reading either as a no-commit would retry exactly
    // the write that may already be in the database.
    RollbackException rolledBack = new RollbackException("ARJUNA016053: Could not commit transaction.");
    rolledBack.initCause(lostConnection());

    assertFalse(DbRetry.retriableInNewTx(new RuntimeException(rolledBack), false));
  }

  @Test
  void aHeuristicOutcomeIsRethrown() {
    // The manager saying, in as many words, that it does not know what the resources did.
    HeuristicMixedException heuristic = new HeuristicMixedException("branches disagreed");
    heuristic.initCause(lostConnection());

    assertFalse(DbRetry.retriableInNewTx(new RuntimeException(heuristic), false));
  }

  @Test
  void xaAmbiguityIsRethrown() {
    // A branch that failed inside its own commit is the distributed shape of the same undecidable
    // round trip.
    XAException xa = new XAException(XAException.XAER_RMFAIL);
    xa.initCause(lostConnection());

    assertFalse(DbRetry.retriableInNewTx(new RuntimeException(xa), false));
  }

  @Test
  void aTransactionManagerFailureWithNothingToPlaceItIsRethrown() {
    SystemException systemFailure = new SystemException("could not complete the transaction");
    systemFailure.initCause(lostConnection());

    assertFalse(DbRetry.retriableInNewTx(new RuntimeException(systemFailure), false));
  }

  @Test
  void aNonConnectionFailureIsRethrownEvenThoughItCertainlyDidNotCommit() {
    // Certainty that nothing committed makes a retry SAFE; it does not make it useful. A constraint
    // violation fails identically on the second attempt, and retrying it for fifteen seconds is the
    // failure mode this whole class exists to avoid.
    SQLException duplicate = new SQLException("duplicate key value", "23505");

    assertFalse(DbRetry.retriableInNewTx(new RuntimeException(duplicate), true));
    assertFalse(DbRetry.retriableInNewTx(new IllegalStateException("a bug"), true));
  }

  @Test
  void aCauseChainThatLoopsDoesNotHangTheCaller() {
    SQLException first = new SQLException("odd", "42601");
    SQLException second = new SQLException("odder", "42601");
    first.initCause(second);
    second.initCause(first);

    assertFalse(DbRetry.retriableInNewTx(first, true));
  }
}
