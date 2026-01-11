import io.github.vrchatapi.ApiException;
import io.github.vrchatapi.api.GroupsApi;
import io.github.vrchatapi.api.UsersApi;
import io.github.vrchatapi.model.LimitedGroup;
import io.github.vrchatapi.model.LimitedUserSearch;
import io.github.vrchatapi.model.User;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class SlashCommandHandler extends ListenerAdapter {

    private final ReviewRepository repo;
    private final UserRepository userRepo;
    private final UsersApi vrcUsersApi;
    private final GroupsApi groupsApi;
    private final VRChatSessionManager vrcSession;

    // Cache für aktive Review-Sessions (messageId -> ReviewSession)
    private final Map<String, ReviewSession> reviewSessions = new ConcurrentHashMap<>();

    // Record zum Speichern einer Review-Session
    private record ReviewSession(String groupId, List<GroupReview> reviews, int currentIndex, double avgRating, int totalReviews, int dobYesCount) {}

    public SlashCommandHandler(ReviewRepository repo, UserRepository userRepo, UsersApi vrcUsersApi, GroupsApi groupsApi, VRChatSessionManager vrcSession) {
        this.repo = repo;
        this.userRepo = userRepo;
        this.vrcUsersApi = vrcUsersApi;
        this.groupsApi = groupsApi;
        this.vrcSession = vrcSession;
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {

        switch (event.getName()) {
            case "rate-group" -> {
                try {
                    handleRateGroup(event);
                } catch (ApiException e) {
                    event.reply("❌ VRChat API Error").setEphemeral(true).queue();
                    vrcSession.reAuthenticate();
                    e.printStackTrace();
                }
            }
            case "link-vrc" -> handleLinkVrc(event);
            case "list-reviews" -> listReviews(event);
            case "edit-review" -> handleEditReview(event);
            case "unlink" -> {
                event.deferReply(true).queue();
                String userId = event.getUser().getId();
                userRepo.unlinkUser(userId);
                event.getHook().sendMessage("✅ Your VRChat account has been unlinked from your Discord account.").queue();
            }
            default -> event.reply("Unknown command").setEphemeral(true).queue();
        }
    }

    @Override
    public void onUserContextInteraction(@NotNull UserContextInteractionEvent event) {
        switch (event.getName()) {
            case "View User Reviews" -> handleViewUserReviews(event);
            case "Send me a DM!" -> {
                event.deferReply(true).queue();
                event.getUser().openPrivateChannel().queue(privateChannel -> {
                    privateChannel.sendMessage("Hello! This is a DM from the bot.").queue();
                    event.getHook().sendMessage("✅ I've sent you a DM! Now you can use the Slash Commands in my DMs!").setEphemeral(true).queue();
                });
            }
            default -> event.reply("Unknown user command").setEphemeral(true).queue();
        }
    }

    private void listReviews(SlashCommandInteractionEvent event) {
        event.deferReply(false).queue();
        String groupId = event.getOption("group_shortcode").getAsString();
        CompletableFuture.runAsync(() -> {
            try {
                List<GroupReview> reviews = repo.getAllReviews(groupId);
                if (reviews.isEmpty()) {
                    event.getHook().sendMessage("No reviews found for group: " + groupId).queue();
                    return;
                }

                // Stats berechnen
                double avgRating = repo.getAverageRating(groupId);
                int totalReviews = reviews.size();
                int dobYesCount = (int) reviews.stream().filter(GroupReview::isAsksForDob).count();

                // Session erstellen
                ReviewSession session = new ReviewSession(groupId, reviews, 0, avgRating, totalReviews, dobYesCount);

                // Container mit UI erstellen
                Container container = buildReviewContainer(session);

                if (container == null) {
                    event.getHook().sendMessage("❌ Could not build review display. Please try again.").queue();
                    return;
                }

                event.getHook().sendMessage("")
                    .setComponents(container)
                    .useComponentsV2()
                    .queue(message -> {
                        // Session mit Message-ID speichern
                        reviewSessions.put(message.getId(), session);
                    });

            } catch (Exception e) {
                vrcSession.reAuthenticate();
                event.getHook().sendMessage("❌ Database Error: " + e.getMessage()).queue();
                e.printStackTrace();
            }
        });
    }

    private Container buildReviewContainer(ReviewSession session) throws ApiException {
        GroupReview review = session.reviews().get(session.currentIndex());

        String stars = "⭐".repeat(review.getRating()) + "☆".repeat(5 - review.getRating());
        double dobPercent = session.totalReviews() > 0 ? (session.dobYesCount() * 100.0 / session.totalReviews()) : 0;

        List<LimitedGroup> groups = Collections.singletonList(vrcSession.executeWithReauth(() -> {
            try {
                return groupsApi.searchGroups(session.groupId(), 0, 60)
                        .stream()
                        .filter(g -> (g.getShortCode() + "." + g.getDiscriminator()).equals(session.groupId()))
                        .findFirst()
                        .orElse(null);
            } catch (ApiException e) {
                vrcSession.reAuthenticate();
                return null;
            }
        }));

        if (groups.get(0) == null) {
            return null;
        }

        String groupName = "Unknown Group";

        if (groups.get(0) != null || groups.get(0).getShortCode().concat(".").concat(groups.get(0).getDiscriminator()).equals(session.groupId)) {
            groupName = groups.get(0).getName();
        }

        TextDisplay header = TextDisplay.of("# 📋 Reviews for: " + groupName);

        String statsText = String.format(
            "### 📊 Statistics\n" +
            "⭐ **Average rating:** %.1f/5\n" +
            "📝 **Review count:** %d\n" +
            "🔞 **Asks for DOB:** %d/%d (%.0f%%)",
            session.avgRating(),
            session.totalReviews(),
            session.dobYesCount(),
            session.totalReviews(),
            dobPercent
        );
        TextDisplay stats = TextDisplay.of(statsText);

        if (userRepo.getVrcUserId(review.getDiscordUserId()).equals(groups.get(0).getOwnerId())) {
            stats = TextDisplay.of(statsText + "\n👑 **Note:** This review is from the group owner.");
        }

        // Aktuelle Review
        String reviewText = String.format(
                """
                        ### Review #%d
                        %s **%d/5**
                        
                        > %s
                        
                        📅 **Date:** %s
                        🔞 **DOB:** %s""",
            session.currentIndex() + 1,
            session.totalReviews(),
            stars,
            review.getRating(),
            review.getComment(),
            review.getCreatedAt() != null ? review.getCreatedAt().toString().substring(0, 10) : "Unknown",
            review.isAsksForDob() ? "✅ Yes" : "❌ No"
        );
        TextDisplay reviewDisplay = TextDisplay.of(reviewText);

        boolean isFirst = session.currentIndex() == 0;
        boolean isLast = session.currentIndex() >= session.reviews().size() - 1;

        ActionRow navigationRow = ActionRow.of(
            Button.secondary("review_prev", "◀ Zurück").withDisabled(isFirst),
            Button.secondary("review_page", String.format("%d / %d", session.currentIndex() + 1, session.totalReviews())).withDisabled(true),
            Button.secondary("review_next", "Weiter ▶").withDisabled(isLast)
        );

        TextDisplay divider = TextDisplay.of("───────────────────────");

        return Container.of(
            header,
            divider,
            stats,
            TextDisplay.of("───────────────────────"),
            reviewDisplay,
            TextDisplay.of("───────────────────────"),
            navigationRow
        );
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String buttonId = event.getComponentId();
        String messageId = event.getMessageId();

        if (!buttonId.startsWith("review_")) return;
        if (buttonId.equals("review_page")) return; // Page-Button ist nur zur Anzeige

        ReviewSession session = reviewSessions.get(messageId);
        if (session == null) {
            event.reply("❌ This session is terminated. Please run /list-reviews again.").setEphemeral(true).queue();
            return;
        }

        int newIndex = session.currentIndex();

        if (buttonId.equals("review_prev")) {
            newIndex = Math.max(0, newIndex - 1);
        } else if (buttonId.equals("review_next")) {
            newIndex = Math.min(session.reviews().size() - 1, newIndex + 1);
        }

        // Neue Session mit aktualisiertem Index
        ReviewSession updatedSession = new ReviewSession(
            session.groupId(),
            session.reviews(),
            newIndex,
            session.avgRating(),
            session.totalReviews(),
            session.dobYesCount()
        );
        reviewSessions.put(messageId, updatedSession);

        Container updatedContainer = null;
        try {
            updatedContainer = buildReviewContainer(updatedSession);
        } catch (ApiException e) {
            event.editMessage("❌ VRChat API Error").queue();
            e.printStackTrace();
            return;
        }

        event.editMessage("")
            .setComponents(updatedContainer)
            .queue();
    }

    // --- Logic for /rate-group ---
    private void handleRateGroup(SlashCommandInteractionEvent event) throws ApiException {
        event.deferReply(true).queue();

        String groupId = event.getOption("group_shortcode").getAsString();
        int rating = event.getOption("rating").getAsInt();
        String comment = event.getOption("comment").getAsString();
        boolean asksDob = event.getOption("asks_for_dob").getAsBoolean();
        String userId = event.getUser().getId();

        if (userRepo.getVrcUserId(userId) == null) {
            event.getHook().sendMessage("❌ You must link your VRChat account first using /link-vrc").queue();
            return;
        }

        if (rating < 1 || rating > 5) {
            event.getHook().sendMessage("❌ Rating must be between 1 and 5").queue();
            return;
        }

        if (repo.hasUserReviewed(groupId, userId)) {
            event.getHook().sendMessage("❌ You have already reviewed this group.").queue();
            return;
        }

        List<LimitedGroup> groups = Collections.singletonList(groupsApi.searchGroups(groupId, 0, 60)
                .stream()
                .filter(g -> (g.getShortCode() + "." + g.getDiscriminator()).equals(groupId))
                .findFirst()
                .orElse(null));

        if (groups.get(0) == null || !groups.get(0).getShortCode().concat(".").concat(groups.get(0).getDiscriminator()).equals(groupId)) {
            event.getHook().sendMessage("❌ Group not found with shortcode: " + groupId).queue();
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                // Save to DB
                repo.upsertReview(groupId, userId, rating, asksDob, comment);

                // Fetch updated stats
                double avg = repo.getAverageRating(groupId);
                boolean isAgeGated = repo.isLikelyAgeGated(groupId);

                // Build Message
                StringBuilder sb = new StringBuilder();
                sb.append("✅ **Review Saved!**\n");
                sb.append("Current Group Rating: ").append(String.format("%.1f", avg)).append(" ⭐\n");

                if (isAgeGated) {
                    sb.append("⚠️ **Warning:** Users report this group requires ID/DOB verification! 🔞");
                }

                event.getHook().sendMessage(sb.toString()).queue();

            } catch (Exception e) {
                event.getHook().sendMessage("❌ Database Error: " + e.getMessage()).queue();
                e.printStackTrace();
            }
        });
    }

    private void handleLinkVrc(SlashCommandInteractionEvent event) {
        String expectedToken = event.getUser().getName();
        String vrcName = event.getOption("username").getAsString();

        event.deferReply(true).queue();

        CompletableFuture.runAsync(() -> {
            try {
                // 1. Search User
                var searchResult = vrcUsersApi.searchUsers(vrcName, null, null, null, null);

                if (searchResult.isEmpty()) {
                    event.getHook().sendMessage("❌ User not found.").queue();
                    return;
                }

                // Simple fuzzy match logic (take first)
                LimitedUserSearch targetUser = searchResult.get(0);

                // 2. Check Bio
                String currentBio = targetUser.getBio();

                if (currentBio != null && currentBio.contains(expectedToken)) {

                    userRepo.linkUser(
                            event.getUser().getId(),
                            targetUser.getId(),
                            targetUser.getDisplayName()
                    );

                    event.getHook().sendMessage("✅ **Success!** Your Discord is now linked to: **" + targetUser.getDisplayName() + "**").queue();
                } else {
                    event.getHook().sendMessage(
                            "⚠️ **Verification Failed!**\nPlease put `" + expectedToken + "` in your VRChat bio and try again."
                    ).queue();
                }

            } catch (Exception e) {
                event.getHook().sendMessage("❌ Error: " + e.getMessage()).queue();
                e.printStackTrace();
            }
        });
    }

    private void handleEditReview(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();

        String groupId = event.getOption("group_shortcode").getAsString();
        String userId = event.getUser().getId();

        Integer newRating = event.getOption("rating") != null ? event.getOption("rating").getAsInt() : null;
        String newComment = event.getOption("comment") != null ? event.getOption("comment").getAsString() : null;
        Boolean newAsksDob = event.getOption("asks_for_dob") != null ? event.getOption("asks_for_dob").getAsBoolean() : null;

        CompletableFuture.runAsync(() -> {
            try {
                GroupReview existingReview = repo.getUserReview(groupId, userId);

                if (existingReview == null) {
                    event.getHook().sendMessage("❌ You did not review this group.").queue();
                    return;
                }

                // Check if at least one field is provided
                if (newRating == null && newComment == null && newAsksDob == null) {
                    // Show current review info
                    String stars = "⭐".repeat(existingReview.getRating()) + "☆".repeat(5 - existingReview.getRating());
                    String currentInfo = String.format(
                            "📝 **Your review %s:**\n\n" +
                            "%s **%d/5**\n" +
                            "> %s\n\n" +
                            "🔞 DOB: %s\n" +
                            "📅 created: %s\n\n" +
                            "💡 Use these optional parameters to edit your review:\n" +
                            "• `rating` - New rating (1-5)\n" +
                            "• `comment` - NEw comment\n" +
                            "• `asks_for_dob` - DOB asked (true/false)",
                            groupId,
                            stars,
                            existingReview.getRating(),
                            existingReview.getComment(),
                            existingReview.isAsksForDob() ? "✅ Yes" : "❌ No",
                            existingReview.getCreatedAt() != null ? existingReview.getCreatedAt().toString().substring(0, 10) : "Unknown"
                    );
                    event.getHook().sendMessage(currentInfo).queue();
                    return;
                }

                // Validate rating if provided
                if (newRating != null && (newRating < 1 || newRating > 5)) {
                    event.getHook().sendMessage("❌ Rating has to be between 1 and 5").queue();
                    return;
                }

                // Update the review
                repo.updateReview(groupId, userId, newRating, newAsksDob, newComment);

                // Build confirmation message
                StringBuilder sb = new StringBuilder();
                sb.append("✅ **Review updated!**\n\n");
                sb.append("**Changed Fields:**\n");

                if (newRating != null) {
                    String stars = "⭐".repeat(newRating) + "☆".repeat(5 - newRating);
                    sb.append("• Rating: ").append(stars).append(" (").append(newRating).append("/5)\n");
                }
                if (newComment != null) {
                    sb.append("• Comment: ").append(newComment).append("\n");
                }
                if (newAsksDob != null) {
                    sb.append("• DOB: ").append(newAsksDob ? "✅ Ja" : "❌ Nein").append("\n");
                }

                // Show updated stats
                double avg = repo.getAverageRating(groupId);
                sb.append("\n📊 **Group-Average:** ").append(String.format("%.1f", avg)).append(" ⭐");

                event.getHook().sendMessage(sb.toString()).queue();

            } catch (Exception e) {
                event.getHook().sendMessage("❌ Error updating: " + e.getMessage()).queue();
                e.printStackTrace();
            }
        });
    }

    // --- Logic for "View User Reviews" User Context Menu ---
    private void handleViewUserReviews(UserContextInteractionEvent event) {
        event.deferReply(false).queue();

        String targetDiscordId = event.getTarget().getId();
        String targetDiscordName = event.getTarget().getName();

        CompletableFuture.runAsync(() -> {
            try {
                // Fetch all reviews by this user
                List<GroupReview> userReviews = repo.getReviewsByUser(targetDiscordId);

                if (userReviews.isEmpty()) {
                    event.getHook().sendMessage("📝 **" + targetDiscordName + "** has not written any reviews yet.").queue();
                    return;
                }

                // Build reviews message
                // Build components using V2
                TextDisplay header = TextDisplay.of("# 📋 Reviews by " + targetDiscordName);
                TextDisplay totalReviews = TextDisplay.of("**Total Reviews:** " + userReviews.size());
                TextDisplay divider = TextDisplay.of("───────────────────────");

                StringBuilder reviewsText = new StringBuilder();
                int count = 0;
                for (GroupReview review : userReviews) {
                    if (count >= 10) {
                        reviewsText.append("\n*... and ").append(userReviews.size() - 10).append(" more reviews.*");
                        break;
                    }

                    String stars = "⭐".repeat(review.getRating()) + "☆".repeat(5 - review.getRating());
                    reviewsText.append("### ").append(review.getGroupId()).append("\n");
                    reviewsText.append(stars).append(" **").append(review.getRating()).append("/5**\n");
                    reviewsText.append("> ").append(review.getComment()).append("\n");
                    reviewsText.append("🔞 DOB: ").append(review.isAsksForDob() ? "✅ Yes" : "❌ No");
                    if (review.getCreatedAt() != null) {
                        reviewsText.append(" | 📅 ").append(review.getCreatedAt().toString().substring(0, 10));
                    }
                    reviewsText.append("\n\n");
                    count++;
                }
                TextDisplay reviewsDisplay = TextDisplay.of(reviewsText.toString());

                // Calculate average rating given by this user
                double avgGiven = userReviews.stream()
                        .mapToInt(GroupReview::getRating)
                        .average()
                        .orElse(0.0);
                TextDisplay avgDisplay = TextDisplay.of("📊 **Average Rating Given:** " + String.format("%.1f", avgGiven) + " ⭐");

                Container container = Container.of(header, divider, totalReviews, divider, reviewsDisplay, divider, avgDisplay);

                event.getHook().sendMessage("").setComponents(container).useComponentsV2().queue();

            } catch (Exception e) {
                event.getHook().sendMessage("❌ Error fetching reviews: " + e.getMessage()).queue();
                e.printStackTrace();
            }
        });
    }
}
