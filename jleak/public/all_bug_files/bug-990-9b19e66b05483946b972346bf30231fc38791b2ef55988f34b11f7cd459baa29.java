package games.strategy.engine.lobby.server.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.logging.Logger;

import games.strategy.util.Tuple;

/**
 * Utilitiy class to create/read/delete banned usernames (there is no update).
 */
public class BannedUsernameController {
  private static final Logger logger = Logger.getLogger(BannedUsernameController.class.getName());

  /**
   * Ban the username permanently.
   */
  public void addBannedUsername(final String username) {
    addBannedUsername(username, null);
  }

  /**
   * Ban the given username. If banTill is not null, the ban will expire when banTill is reached.
   *
   * <p>
   * If this username is already banned, this call will update the ban_end.
   * </p>
   */
  public void addBannedUsername(final String username, final Instant banTill) {
    if (isUsernameBanned(username).getFirst()) {
      removeBannedUsername(username);
    }
    Timestamp banTillTs = null;
    if (banTill != null) {
      banTillTs = new Timestamp(banTill.toEpochMilli());
    }
    logger.fine("Banning username:" + username);
    final Connection con = Database.getDerbyConnection();
    try {
      final PreparedStatement ps =
          con.prepareStatement("insert into banned_usernames (username, ban_till) values (?, ?)");
      ps.setString(1, username);
      ps.setTimestamp(2, banTillTs);
      ps.execute();
      ps.close();
      con.commit();
    } catch (final SQLException sqle) {
      if (sqle.getErrorCode() == 30000) {
        // this is ok
        // the username is banned as expected
        logger.info("Tried to create duplicate banned username:" + username + " error:" + sqle.getMessage());
        return;
      }
      throw new IllegalStateException("Error inserting banned username:" + username, sqle);
    } finally {
      DbUtil.closeConnection(con);
    }
  }

  private void removeBannedUsername(final String username) {
    logger.fine("Removing banned username:" + username);
    final Connection con = Database.getDerbyConnection();
    try {
      final PreparedStatement ps = con.prepareStatement("delete from banned_usernames where username = ?");
      ps.setString(1, username);
      ps.execute();
      ps.close();
      con.commit();
    } catch (final SQLException sqle) {
      throw new IllegalStateException("Error deleting banned username:" + username, sqle);
    } finally {
      DbUtil.closeConnection(con);
    }
  }

  /**
   * Is the given username banned? This may have the side effect of removing from the
   * database any username's whose ban has expired.
   */
  public Tuple<Boolean, Timestamp> isUsernameBanned(final String username) {
    boolean found = false;
    boolean expired = false;
    Timestamp banTill = null;
    final String sql = "select username, ban_till from banned_usernames where username = ?";
    final Connection con = Database.getDerbyConnection();
    try {
      final PreparedStatement ps = con.prepareStatement(sql);
      ps.setString(1, username);
      final ResultSet rs = ps.executeQuery();
      found = rs.next();
      // If the ban has expired, allow the username
      if (found) {
        banTill = rs.getTimestamp(2);
        if (banTill != null && banTill.getTime() < System.currentTimeMillis()) {
          logger.fine("Ban expired for:" + username);
          expired = true;
        }
      }
      rs.close();
      ps.close();
    } catch (final SQLException sqle) {
      throw new IllegalStateException("Error for testing banned username existence:" + username, sqle);
    } finally {
      DbUtil.closeConnection(con);
    }
    if (expired) {
      removeBannedUsername(username);
      return Tuple.of(false, banTill);
    }
    return Tuple.of(found, banTill);
  }
}
