package eu.wohlben.qits.environment;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * The incoming half: a request carrying {@link EnvironmentHeader#NAME} runs its resource method
 * with {@link CallerEnvironment} holding that tier, so a platform service learns which environment
 * is calling without every resource method reading a header. Discovered by {@code @Provider}; a
 * consumer without a REST server never instantiates it.
 *
 * <p><b>Every request establishes a value, including one with no header.</b> An unstamped caller
 * has no tier to lend, and saying so with {@code swap(null)} is what keeps a pooled worker's
 * previous request from lending its caller's tier to this one. Absent and blank read the same way
 * — the caller's tier is advisory, and a request must never fail over it.
 *
 * <p><b>The response filter restores rather than clears</b>, the previous value having ridden the
 * request context (boxed, because "the previous value was null" and "the request filter never ran"
 * — a 404 runs only the response half — must read differently). The pairing assumes both halves
 * run on the resource method's thread, which is what RESTEasy Reactive does for a blocking method
 * — the platform's shape; an async method whose completion migrates threads inherits the executor
 * caveat {@link CallerEnvironment} documents, bounded by the establishing swap at the next
 * request's start. The whole arrangement is qits-eventstream's {@code CausationServerFilter},
 * restated for the tier.
 */
@Provider
@Priority(Priorities.HEADER_DECORATOR)
public class EnvironmentServerFilter implements ContainerRequestFilter, ContainerResponseFilter {

  private static final String PREVIOUS = EnvironmentServerFilter.class.getName() + ".previous";

  /** The box whose presence means "the request half ran here", whatever it holds. */
  private record Previous(String environment) {}

  @Override
  public void filter(ContainerRequestContext requestContext) {
    String tier = EnvironmentHeader.parse(requestContext.getHeaderString(EnvironmentHeader.NAME));
    requestContext.setProperty(PREVIOUS, new Previous(CallerEnvironment.swap(tier)));
  }

  @Override
  public void filter(
      ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
    if (requestContext.getProperty(PREVIOUS) instanceof Previous(String environment)) {
      CallerEnvironment.swap(environment);
      requestContext.removeProperty(PREVIOUS);
    }
  }
}
