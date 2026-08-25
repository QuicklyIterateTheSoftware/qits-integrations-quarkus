package eu.wohlben.qits.environment;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The far end: a blocking resource method — the platform's shape, and the thread model the filters
 * assume — that answers with the caller tier it is running under. The literal {@code "null"} names
 * "none", because the REST client reads an empty 200 body back as a null string and the sentinel
 * must survive the trip.
 */
@Path("/environment-probe")
public class EnvironmentProbeResource {

  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String callerTier() {
    return String.valueOf(CallerEnvironment.current());
  }
}
