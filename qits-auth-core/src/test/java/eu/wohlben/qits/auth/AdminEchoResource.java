package eu.wohlben.qits.auth;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/admin-echo")
@RolesAllowed("qits:admin")
public class AdminEchoResource {

  @GET
  public String get() {
    return "ok";
  }
}
