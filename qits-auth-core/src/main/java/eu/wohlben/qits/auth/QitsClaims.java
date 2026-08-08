package eu.wohlben.qits.auth;

import java.util.Set;

/**
 * The vocabulary of a qits machine token: the structured claim names, and the service ids that are
 * both an {@code aud} value and a client id.
 *
 * <p><b>Claims, not scopes.</b> qits-idp issues no scope strings. A token says who it is for
 * ({@code aud}) and what it is about ({@code project} / {@code workspace} / {@code branch}); the
 * resource service decides what that permits. Spell the names from here rather than inline — a
 * typo in a claim name reads as "no claim", which is a silent pass on an unenforced path and a
 * silent denial on an enforced one.
 *
 * <p>Claims appear on a token only when granted to the client, so absence is normal and never an
 * error by itself.
 *
 * <p><b>Only platform service ids are constants.</b> An environment service is named
 * {@code <env>-qits-<app>} — {@code prod-qits-ci} — and the environment is not known until deploy
 * time, so a service reads its own id and its peers' ids from injected config. Anything named here
 * would be true in one environment and wrong in every other.
 */
public final class QitsClaims {

  /** The project a token is about. */
  public static final String PROJECT = "project";

  /** The workspace a token is about. */
  public static final String WORKSPACE = "workspace";

  /** The branch a token is about. Only meaningful beside {@link #WORKSPACE}. */
  public static final String BRANCH = "branch";

  /** Every structured claim qits-idp can grant. */
  public static final Set<String> NAMES = Set.of(PROJECT, WORKSPACE, BRANCH);

  /**
   * The claim value that covers every value. A client granted {@code project=*} may act on any
   * project — the platform services that serve all of them, such as qits-platform-artifacts
   * hosting every project's git repositories, hold their claims this way.
   *
   * <p>It is a token-side value only. Asking whether a token covers the literal target {@code "*"}
   * is answered like any other name, so a caller cannot widen its own check by passing it.
   */
  public static final String ANY = "*";

  /**
   * Service id of qits-platform-artifacts, the only platform service qits-idp seeds a static client
   * for today.
   *
   * <p>The other platform services are deliberately absent. qits-platform-idp issues tokens rather
   * than asking for them, and qits-platform-docs and qits-platform-edge have no seeded client — add
   * a constant here when one appears on {@code qits.idp.clients}, not before.
   */
  public static final String ARTIFACTS = "qits-platform-artifacts";

  /**
   * The static platform client ids. A service id is the client id when the service asks for a token
   * and the {@code aud} value when it receives one — one name for both ends, so a grant reads the
   * same from either side.
   *
   * <p><b>Platform services only.</b> An environment service is deployed once per environment and
   * its id carries that environment: {@code <env>-qits-<app>}, such as {@code prod-qits-ci}. The
   * environment is known at deploy time, not at compile time, so those ids reach a service through
   * injected config and cannot be constants here.
   */
  public static final Set<String> SERVICE_IDS = Set.of(ARTIFACTS);

  private QitsClaims() {}
}
