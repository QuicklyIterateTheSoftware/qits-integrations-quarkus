package eu.wohlben.qits.db.testdb;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A TCP proxy in front of the embedded postgres, whose whole purpose is to be killed on command.
 *
 * <p><b>Why hand-rolled.</b> The failures {@code DbRetry.inNewTx} classifies are positions in the
 * postgres wire protocol — a connection lost while a statement is in flight, and a connection lost
 * while the commit is. Nothing but a real socket between a real driver and a real server can put a
 * test on either side of that line. The repository already builds its far ends this way
 * (qits-eventstream's {@code StubEventsServer}); this is the same bargain, in fewer lines.
 *
 * <p>Two kills, and the difference between them is the whole point:
 *
 * <ul>
 *   <li>{@link #killEverything()} drops every open connection now. Whatever was in flight was a
 *       statement, so the transaction certainly did not commit.
 *   <li>{@link #killTheNextCommit()} watches the client-to-server stream for {@code COMMIT} and
 *       drops the connection <b>without forwarding it</b>. The client sees a connection that died
 *       during the commit round trip, which is the one outcome it cannot decide — exactly what the
 *       classification must refuse to retry.
 * </ul>
 *
 * <p>Sockets are closed with {@code SO_LINGER 0}, so the peer gets a reset rather than an orderly
 * close and pgjdbc reports a connection failure rather than an end of stream.
 */
public final class KillableProxy implements AutoCloseable {

  private static final byte[] COMMIT = "COMMIT".getBytes(StandardCharsets.US_ASCII);

  private final ServerSocket listener;
  private final int targetPort;
  private final List<Socket> live = new ArrayList<>();
  private final AtomicBoolean killOnCommit = new AtomicBoolean();
  private final AtomicInteger commitsSeen = new AtomicInteger();
  private final AtomicInteger connectionsAccepted = new AtomicInteger();
  private volatile boolean closed;

  public KillableProxy(int targetPort) throws IOException {
    this.targetPort = targetPort;
    this.listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    Thread accepting = new Thread(this::acceptLoop, "killable-proxy-accept");
    accepting.setDaemon(true);
    accepting.start();
  }

  /** The port a client connects to instead of postgres' own. */
  public int port() {
    return listener.getLocalPort();
  }

  /** How many connections have been opened through this proxy since it started. */
  public int connectionsAccepted() {
    return connectionsAccepted.get();
  }

  /** How many commits have been seen on the wire, whether forwarded or killed. */
  public int commitsSeen() {
    return commitsSeen.get();
  }

  /** Drops every open connection at once. A statement in flight dies with it. */
  public void killEverything() {
    List<Socket> doomed;
    synchronized (live) {
      doomed = new ArrayList<>(live);
      live.clear();
    }
    doomed.forEach(KillableProxy::reset);
  }

  /**
   * Arms the commit kill: the next {@code COMMIT} a client sends is dropped on the floor and its
   * connection is reset. Disarms itself after firing once, so a retried attempt would get through.
   */
  public void killTheNextCommit() {
    killOnCommit.set(true);
  }

  @Override
  public void close() {
    closed = true;
    killEverything();
    try {
      listener.close();
    } catch (IOException ignored) {
      // Shutting down; a listener that is already gone is the state we wanted.
    }
  }

  private void acceptLoop() {
    while (!closed) {
      try {
        Socket fromClient = listener.accept();
        Socket toServer = new Socket(InetAddress.getLoopbackAddress(), targetPort);
        fromClient.setTcpNoDelay(true);
        toServer.setTcpNoDelay(true);
        connectionsAccepted.incrementAndGet();
        synchronized (live) {
          live.add(fromClient);
          live.add(toServer);
        }
        pump(fromClient, toServer, true);
        pump(toServer, fromClient, false);
      } catch (IOException e) {
        if (!closed) {
          // A refused dial or a client that vanished mid-handshake. The next accept is the answer.
          continue;
        }
      }
    }
  }

  private void pump(Socket from, Socket to, boolean fromTheClient) {
    Thread thread =
        new Thread(
            () -> {
              byte[] buffer = new byte[8192];
              try (InputStream in = from.getInputStream()) {
                OutputStream out = to.getOutputStream();
                while (true) {
                  int read = in.read(buffer, 0, buffer.length);
                  if (read < 0) {
                    break;
                  }
                  if (fromTheClient && contains(buffer, read, COMMIT)) {
                    commitsSeen.incrementAndGet();
                    if (killOnCommit.compareAndSet(true, false)) {
                      // Not forwarded: the client is left with a commit it can neither confirm nor
                      // rule out, which is the outcome under test.
                      killEverything();
                      return;
                    }
                  }
                  out.write(buffer, 0, read);
                  out.flush();
                }
              } catch (IOException expected) {
                // A killed connection is how this class works.
              } finally {
                reset(from);
                reset(to);
              }
            },
            "killable-proxy-pump");
    thread.setDaemon(true);
    thread.start();
  }

  private static boolean contains(byte[] haystack, int length, byte[] needle) {
    outer:
    for (int start = 0; start <= length - needle.length; start++) {
      for (int i = 0; i < needle.length; i++) {
        if (haystack[start + i] != needle[i]) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }

  private static void reset(Socket socket) {
    try {
      // Linger zero makes close() send a reset, so the peer reports a connection failure rather
      // than reading a clean end of stream and calling it a tidy shutdown.
      socket.setSoLinger(true, 0);
    } catch (IOException ignored) {
      // Already closed, or an implementation that will not take the option. The close still lands.
    }
    try {
      socket.close();
    } catch (IOException ignored) {
      // Already closed is the state we wanted.
    }
  }
}
