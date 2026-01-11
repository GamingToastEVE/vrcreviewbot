import io.github.vrchatapi.ApiClient;
import io.github.vrchatapi.api.GroupsApi;
import io.github.vrchatapi.api.UsersApi;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import io.github.cdimascio.dotenv.Dotenv;
public class Main {

    public static void main(String[] args) throws InterruptedException {
        // 1. Load configuration (Ensure these are set in your Environment Variables or .env)
        Dotenv dotenv = Dotenv.load();
        String discordToken = dotenv.get("DISCORD_TOKEN");

        if (discordToken == null) {
            System.err.println("Error: DISCORD_TOKEN is missing!");
            return;
        }

        // 2. Initialize Database Connection
        System.out.println("Connecting to Database...");
        DatabaseManager dbManager = new DatabaseManager();
        ReviewRepository reviewRepo = new ReviewRepository(dbManager);
        UserRepository userRepo = new UserRepository(dbManager);

        // 3. Initialize VRChat Session (Auto-Login with TOTP)
        System.out.println("Logging into VRChat...");
        VRChatSessionManager vrcSession = new VRChatSessionManager();
        ApiClient vrcClient = vrcSession.login(); // This handles the TOTP logic we wrote earlier
        UsersApi vrcUsersApi = new UsersApi(vrcClient);
        GroupsApi vrcGroupsApi = new GroupsApi(vrcClient);

        // 4. Initialize Discord Bot (JDA)
        System.out.println("Starting Discord Bot...");
        JDA jda = JDABuilder.createDefault(discordToken)
                .addEventListeners(new SlashCommandHandler(reviewRepo, userRepo, vrcUsersApi, vrcGroupsApi, vrcSession))
                .build();

        // Wait until JDA is ready before registering commands
        jda.awaitReady();

        jda.getPresence().setActivity(Activity.playing("Rating VRChat Groups"));

        // 5. Register Slash Commands
        // Note: Global commands can take up to an hour to update.
        // For testing, use .updateCommands().addCommands(...).queue() on a specific Guild.
        jda.updateCommands().addCommands(

                // Command: /rate-group
                Commands.slash("rate-group", "Rate a VRChat Group based on your experience")
                        .addOption(OptionType.STRING, "group_shortcode", "The VRChat Shortcode of the group", true)
                        .addOption(OptionType.INTEGER, "rating", "Rating from 1 to 5", true)
                        .addOption(OptionType.STRING, "comment", "Your review comment", true)
                        .addOption(OptionType.BOOLEAN, "asks_for_dob", "Did they ask for your Date of Birth / ID despite you being age verified?", true),

                // Command: /link-vrc
                Commands.slash("link-vrc", "Link your Discord account to VRChat via Bio verification")
                        .addOption(OptionType.STRING, "username", "Your VRChat Display Name", true),

                Commands.slash("unlink", "Unlink your VRChat account from your Discord account"),

                Commands.slash("list-reviews", "List reviews for a VRChat Group")
                        .addOption(OptionType.STRING, "group_shortcode", "The VRChat Shortcode of the group", true),

                // Command: /edit-review
                Commands.slash("edit-review", "Edit your existing review for a VRChat Group")
                        .addOption(OptionType.STRING, "group_shortcode", "The VRChat Shortcode of the group", true)
                        .addOption(OptionType.INTEGER, "rating", "New rating from 1 to 5", false)
                        .addOption(OptionType.STRING, "comment", "New review comment", false)
                        .addOption(OptionType.BOOLEAN, "asks_for_dob", "Did they ask for your Date of Birth / ID?", false),

                // User Context Menu Commands
                Commands.user("View User Reviews"),
                Commands.user("Send me a DM!")

        ).queue();

        System.out.println("Bot is running! Invite URL: " + jda.getInviteUrl());
    }
}
