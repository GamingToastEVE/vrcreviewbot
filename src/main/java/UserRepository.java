import java.sql.*;

public class UserRepository {
    private final DatabaseManager dbManager;

    public UserRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    /**
     * Saves or updates the link between Discord ID and VRChat User.
     */
    public void linkUser(String discordId, String vrcUserId, String vrcDisplayName) {
        String sql = "INSERT INTO user_links (discord_user_id, vrc_user_id, vrc_display_name) " +
                "VALUES (?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE vrc_user_id = ?, vrc_display_name = ?, linked_at = CURRENT_TIMESTAMP";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, discordId);
            stmt.setString(2, vrcUserId);
            stmt.setString(3, vrcDisplayName);

            // Update values if exists
            stmt.setString(4, vrcUserId);
            stmt.setString(5, vrcDisplayName);

            stmt.executeUpdate();
            System.out.println("Linked Discord User " + discordId + " to VRC User " + vrcDisplayName);

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Returns the VRChat User ID (usr_...) for a Discord ID, or null if not linked.
     */
    public String getVrcUserId(String discordId) {
        String sql = "SELECT vrc_user_id FROM user_links WHERE discord_user_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, discordId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return rs.getString("vrc_user_id");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null; // Not found
    }

    public void unlinkUser(String userId) {
        String sql = "DELETE FROM user_links WHERE discord_user_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            stmt.executeUpdate();
            System.out.println("Unlinked Discord User " + userId);

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
