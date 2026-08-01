package eu.wohlben.qits.auth;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Test-only: a resource guarded exactly as a wave-2 endpoint will be, so the CDI wiring and the
 * shipped gate default are proven through a real request rather than only in a unit test.
 */
@Path("/guarded/{project}")
@Produces(MediaType.TEXT_PLAIN)
public class GuardedEchoResource {

  @Inject MachineAuth machineAuth;

  @GET
  public String get(@PathParam("project") String project) {
    machineAuth.requireProject(project);
    return machineAuth.enforced() ? "enforced" : "open";
  }
}
