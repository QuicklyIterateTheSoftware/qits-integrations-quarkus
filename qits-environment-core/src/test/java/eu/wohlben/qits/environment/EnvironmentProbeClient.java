package eu.wohlben.qits.environment;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;

/**
 * The near end: a REST-client interface with {@link EnvironmentClientFilter} applied the way a
 * consumer gets it — through {@code @Provider} discovery, registered by nobody. The second method
 * sets the header itself, which is how the tests reach "an explicit header wins" and "blank reads
 * as none" without a hand-built request.
 */
@Path("/environment-probe")
public interface EnvironmentProbeClient {

  @GET
  String callerTier();

  @GET
  String callerTierWith(@HeaderParam(EnvironmentHeader.NAME) String explicitHeader);
}
