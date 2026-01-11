import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class ReviewRepository {

    private final DatabaseManager dbManager;

    public ReviewRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    // 1. Add or Update a Review
    public void upsertReview(String groupId, String discordUserId, int rating, boolean asksForDob, String comment) {
        String sql = "INSERT INTO group_reviews (group_id, discord_user_id, rating, asks_for_dob, comment) " +
                "VALUES (?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE rating = ?, asks_for_dob = ?, comment = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            // Insert Parameters
            stmt.setString(1, groupId);
            stmt.setString(2, discordUserId);
            stmt.setInt(3, rating);
            stmt.setBoolean(4, asksForDob); // Set the boolean
            stmt.setString(5, comment);

            // Update Parameters (if entry exists)
            stmt.setInt(6, rating);
            stmt.setBoolean(7, asksForDob); // Update the boolean
            stmt.setString(8, comment);

            stmt.executeUpdate();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // 2. Get Average Rating
    public double getAverageRating(String groupId) {
        String sql = "SELECT AVG(rating) as avg_rating FROM group_reviews WHERE group_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, groupId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) return rs.getDouble("avg_rating");
        } catch (SQLException e) { e.printStackTrace(); }
        return 0.0;
    }

    // 3. NEW: Check if the group is considered "Age Restricted"
    // Returns true if more than 50% of reviewers say it asks for DOB
    public boolean isLikelyAgeGated(String groupId) {
        String sql = "SELECT " +
                "SUM(CASE WHEN asks_for_dob = 1 THEN 1 ELSE 0 END) as yes_votes, " +
                "COUNT(*) as total_votes " +
                "FROM group_reviews WHERE group_id = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, groupId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                int yesVotes = rs.getInt("yes_votes");
                int totalVotes = rs.getInt("total_votes");
                if (totalVotes == 0) return false;

                // If more than 50% say Yes, we treat it as age-gated
                return ((double) yesVotes / totalVotes) > 0.5;
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    // 4. Get Recent Reviews
    public List<GroupReview> getAllReviews(String groupId) {
        List<GroupReview> reviews = new ArrayList<>();
        String sql = "SELECT * FROM group_reviews WHERE group_id = ? ORDER BY created_at DESC";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, groupId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                reviews.add(new GroupReview(
                        rs.getString("group_id"),
                        rs.getString("discord_user_id"),
                        rs.getInt("rating"),
                        rs.getBoolean("asks_for_dob"), // Retrieve boolean
                        rs.getString("comment"),
                        rs.getTimestamp("created_at")
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return reviews;
    }

    public boolean hasUserReviewed(String groupId, String discordUserId) {
        String sql = "SELECT COUNT(*) as review_count FROM group_reviews WHERE group_id = ? AND discord_user_id = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, groupId);
            stmt.setString(2, discordUserId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return rs.getInt("review_count") > 0;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    // Get a specific user's review for a group
    public GroupReview getUserReview(String groupId, String discordUserId) {
        String sql = "SELECT * FROM group_reviews WHERE group_id = ? AND discord_user_id = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, groupId);
            stmt.setString(2, discordUserId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return new GroupReview(
                        rs.getString("group_id"),
                        rs.getString("discord_user_id"),
                        rs.getInt("rating"),
                        rs.getBoolean("asks_for_dob"),
                        rs.getString("comment"),
                        rs.getTimestamp("created_at")
                );
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    // Update specific fields of a review
    public void updateReview(String groupId, String discordUserId, Integer rating, Boolean asksForDob, String comment) {
        StringBuilder sql = new StringBuilder("UPDATE group_reviews SET ");
        List<Object> params = new ArrayList<>();

        boolean hasUpdates = false;

        if (rating != null) {
            sql.append("rating = ?");
            params.add(rating);
            hasUpdates = true;
        }

        if (asksForDob != null) {
            if (hasUpdates) sql.append(", ");
            sql.append("asks_for_dob = ?");
            params.add(asksForDob);
            hasUpdates = true;
        }

        if (comment != null) {
            if (hasUpdates) sql.append(", ");
            sql.append("comment = ?");
            params.add(comment);
            hasUpdates = true;
        }

        if (!hasUpdates) return; // No fields to update

        sql.append(" WHERE group_id = ? AND discord_user_id = ?");
        params.add(groupId);
        params.add(discordUserId);

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);
                if (param instanceof Integer) {
                    stmt.setInt(i + 1, (Integer) param);
                } else if (param instanceof Boolean) {
                    stmt.setBoolean(i + 1, (Boolean) param);
                } else if (param instanceof String) {
                    stmt.setString(i + 1, (String) param);
                }
            }

            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // Get all reviews by a specific user
    public List<GroupReview> getReviewsByUser(String discordUserId) {
        List<GroupReview> reviews = new ArrayList<>();
        String sql = "SELECT * FROM group_reviews WHERE discord_user_id = ? ORDER BY created_at DESC";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, discordUserId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                reviews.add(new GroupReview(
                        rs.getString("group_id"),
                        rs.getString("discord_user_id"),
                        rs.getInt("rating"),
                        rs.getBoolean("asks_for_dob"),
                        rs.getString("comment"),
                        rs.getTimestamp("created_at")
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return reviews;
    }
}
