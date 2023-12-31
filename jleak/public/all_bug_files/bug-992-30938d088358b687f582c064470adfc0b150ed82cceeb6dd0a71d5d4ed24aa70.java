package games.strategy.engine.lobby.server.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Utilitiy class to create/read/delete muted macs (there is no update).
 */
public class MutedMacController {
  private static final Logger logger = Logger.getLogger(MutedMacController.class.getName());

  /**
   * Mute the mac permanently.
   */
  public void addMutedMac(final String mac) {
    addMutedMac(mac, null);
  }

  /**
   * Mute the given mac. If muteTill is not null, the mute will expire when muteTill is reached.
   *
   * <p>
   * If this mac is already muted, this call will update the mute_end.
   * </p>
   */
  public void addMutedMac(final String mac, final Instant muteTill) {
    if (isMacMuted(mac)) {
      removeMutedMac(mac);
    }
    Timestamp muteTillTs = null;
    if (muteTill != null) {
      muteTillTs = new Timestamp(muteTill.toEpochMilli());
    }
    logger.fine("Muting mac:" + mac);
    final Connection con = Database.getDerbyConnection();
    try {
      final PreparedStatement ps = con.prepareStatement("insert into muted_macs (mac, mute_till) values (?, ?)");
      ps.setString(1, mac);
      ps.setTimestamp(2, muteTillTs);
      ps.execute();
      ps.close();
      con.commit();
    } catch (final SQLException sqle) {
      if (sqle.getErrorCode() == 30000) {
        // this is ok
        // the mac is muted as expected
        logger.info("Tried to create duplicate muted mac:" + mac + " error:" + sqle.getMessage());
        return;
      }
      throw new IllegalStateException("Error inserting muted mac:" + mac, sqle);
    } finally {
      DbUtil.closeConnection(con);
    }
  }

  private void removeMutedMac(final String mac) {
    logger.fine("Removing muted mac:" + mac);
    final Connection con = Database.getDerbyConnection();
    try {
      final PreparedStatement ps = con.prepareStatement("delete from muted_macs where mac = ?");
      ps.setString(1, mac);
      ps.execute();
      ps.close();
      con.commit();
    } catch (final SQLException sqle) {
      throw new IllegalStateException("Error deleting muted mac:" + mac, sqle);
    } finally {
      DbUtil.closeConnection(con);
    }
  }

  /**
   * Is the given mac muted? This may have the side effect of removing from the
   * database any mac's whose mute has expired.
   */
  public boolean isMacMuted(final String mac) {
    final long muteTill = getMacUnmuteTime(mac);
    return muteTill > System.currentTimeMillis();
  }

  /**
   * Returns epoch milli second timestamp of when a mute expires or negative one if there is no mute.
   */
  public long getMacUnmuteTime(final String mac) {
    long result = -1;
    boolean expired = false;
    final String sql = "select mac, mute_till from muted_macs where mac = ?";
    final Connection con = Database.getDerbyConnection();
    try {
      final PreparedStatement ps = con.prepareStatement(sql);
      ps.setString(1, mac);
      final ResultSet rs = ps.executeQuery();
      final boolean found = rs.next();
      if (found) {
        final Timestamp muteTill = rs.getTimestamp(2);
        result = muteTill.getTime();
        if (result < System.currentTimeMillis()) {
          logger.fine("Mute expired for:" + mac);
          expired = true;
        }
      } else {
        result = -1;
      }
      rs.close();
      ps.close();
    } catch (final SQLException sqle) {
      throw new IllegalStateException("Error for testing muted mac existence:" + mac, sqle);
    } finally {
      DbUtil.closeConnection(con);
    }
    // If the mute has expired, allow the mac
    if (expired) {
      removeMutedMac(mac);
      // Signal as not-muted
      result = -1;
    }
    return result;
  }
}
