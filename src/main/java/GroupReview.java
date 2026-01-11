import java.sql.Timestamp;

public class GroupReview {
    private String groupId;
    private String discordUserId;
    private int rating;
    private boolean asksForDob; // New field
    private String comment;
    private Timestamp createdAt;

    public GroupReview(String groupId, String discordUserId, int rating, boolean asksForDob, String comment, Timestamp createdAt) {
        this.groupId = groupId;
        this.discordUserId = discordUserId;
        this.rating = rating;
        this.asksForDob = asksForDob;
        this.comment = comment;
        this.createdAt = createdAt;
    }

    // Getters
    public String getGroupId() { return groupId; }
    public int getRating() { return rating; }
    public boolean isAsksForDob() { return asksForDob; }
    public String getComment() { return comment; }
    public String getDiscordUserId() { return discordUserId; }
    public Timestamp getCreatedAt() { return createdAt; }
}
