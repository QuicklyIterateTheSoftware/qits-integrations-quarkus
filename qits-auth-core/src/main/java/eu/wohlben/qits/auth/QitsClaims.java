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
   * project — the platform services that serve all of them, such as qits-artifacts hosting every
   * project's git repositories, hold their claims this way.
   *
   * <p>It is a token-side value only. Asking whether a token covers the literal target {@code "*"}
   * is answered like any other name, so a caller cannot widen its own check by passing it.
   */
  public static final String ANY = "*";

  /** Service id of qits-ci. */
  public static final String CI = "qits-ci";

  /** Service id of qits-cd. */
  public static final String CD = "qits-cd";

  /** Service id of qits-artifacts. */
  public static final String ARTIFACTS = "qits-artifacts";

  /** Service id of qits-workspaces. */
  public static final String WORKSPACES = "qits-workspaces";

  /** Service id of qits-gateway. */
  public static final String GATEWAY = "qits-gateway";

  /**
   * Every static client qits-idp seeds. A service id is the client id when the service asks for a
   * token and the {@code aud} value when it receives one — one name for both ends, so a grant reads
   * the same from either side.
   */
  public static final Set<String> SERVICE_IDS = Set.of(CI, CD, ARTIFACTS, WORKSPACES, GATEWAY);

  private QitsClaims() {}
}
