/*
 * Copyright 2015 Austin Keener, Michael Ritter, Florian Spieß, and the JDA contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageChannel;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;

import javax.security.auth.login.LoginException;
import java.util.EnumSet;

import static net.dv8tion.jda.api.interactions.commands.OptionType.*;

public class SlashBotExample extends ListenerAdapter
{
    public static void main(String[] args) throws LoginException
    {
        JDA jda = JDABuilder.createLight("BOT_TOKEN_HERE", EnumSet.noneOf(GatewayIntent.class)) // slash commands don't need any intents
                .addEventListeners(new SlashBotExample())
                .build();

        // These commands take up to an hour to be activated after creation/update/delete
        CommandListUpdateAction commands = jda.updateCommands();

        // Moderation commands with required options
        commands.addCommands(
            Commands.slash("ban", "Ban a user from this server. Requires permission to ban users.")
                .addOptions(new OptionData(USER, "user", "The user to ban") // USER type allows to include members of the server or other users by id
                    .setRequired(true)) // This command requires a parameter
                .addOptions(new OptionData(INTEGER, "del_days", "Delete messages from the past days.")) // This is optional
        );

        // Simple reply commands
        commands.addCommands(
            Commands.slash("say", "Makes the bot say what you tell it to")
                .addOption(STRING, "content", "What the bot should say", true) // you can add required options like this too
        );

        // Commands without any inputs
        commands.addCommands(
            Commands.slash("leave", "Make the bot leave the server")
        );

        commands.addCommands(
            Commands.slash("prune", "Prune messages from this channel")
                .addOption(INTEGER, "amount", "How many messages to prune (Default 100)") // simple optional argument
        );

        // Send the new set of commands to discord, this will override any existing global commands with the new set provided here
        commands.queue();
    }


    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event)
    {
        // Only accept commands from guilds
        if (event.getGuild() == null)
            return;
        switch (event.getName())
        {
        case "ban":
            Member member = event.getOption("user").getAsMember(); // the "user" option is required, so it doesn't need a null-check here
            User user = event.getOption("user").getAsUser();
            ban(event, user, member);
            break;
        case "say":
            say(event, event.getOption("content").getAsString()); // content is required so no null-check here
            break;
        case "leave":
            leave(event);
            break;
        case "prune": // 2 stage command with a button prompt
            prune(event);
            break;
        default:
            event.reply("I can't handle that command right now :(").setEphemeral(true).queue();
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event)
    {
        String[] id = event.getComponentId().split(":"); // this is the custom id we specified in our button
        String authorId = id[0];
        String type = id[1];
        // Check that the button is for the user that clicked it, otherwise just ignore the event (let interaction fail)
        if (!authorId.equals(event.getUser().getId()))
            return;
        event.deferEdit().queue(); // acknowledge the button was clicked, otherwise the interaction will fail
 
        MessageChannel channel = event.getChannel();
        switch (type)
        {
            case "prune":
                int amount = Integer.parseInt(id[2]);
                event.getChannel().getIterableHistory()
                    .skipTo(event.getMessageIdLong())
                    .takeAsync(amount)
                    .thenAccept(channel::purgeMessages);
                // fallthrough delete the prompt message with our buttons
            case "delete":
                event.getHook().deleteOriginal().queue();
        }
    }

    public void ban(SlashCommandInteractionEvent event, User user, Member member)
    {
        event.deferReply(true).queue(); // Let the user know we received the command before doing anything else
        InteractionHook hook = event.getHook(); // This is a special webhook that allows you to send messages without having permissions in the channel and also allows ephemeral messages
        hook.setEphemeral(true); // All messages here will now be ephemeral implicitly
        if (!event.getMember().hasPermission(Permission.BAN_MEMBERS))
        {
            hook.sendMessage("You do not have the required permissions to ban users from this server.").queue();
            return;
        }

        Member selfMember = event.getGuild().getSelfMember();
        if (!selfMember.hasPermission(Permission.BAN_MEMBERS))
        {
            hook.sendMessage("I don't have the required permissions to ban users from this server.").queue();
            return;
        }

        if (member != null && !selfMember.canInteract(member))
        {
            hook.sendMessage("This user is too powerful for me to ban.").queue();
            return;
        }

        int delDays = 0;
        OptionMapping option = event.getOption("del_days");
        if (option != null) // null = not provided
            delDays = (int) Math.max(0, Math.min(7, option.getAsLong()));
        // Ban the user and send a success response
        event.getGuild().ban(user, delDays)
            .flatMap(v -> hook.sendMessage("Banned user " + user.getAsTag()))
            .queue();
    }

    public void say(SlashCommandInteractionEvent event, String content)
    {
        event.reply(content).queue(); // This requires no permissions!
    }

    public void leave(SlashCommandInteractionEvent event)
    {
        if (!event.getMember().hasPermission(Permission.KICK_MEMBERS))
            event.reply("You do not have permissions to kick me.").setEphemeral(true).queue();
        else
            event.reply("Leaving the server... :wave:") // Yep we received it
                 .flatMap(v -> event.getGuild().leave()) // Leave server after acknowledging the command
                 .queue();
    }

    public void prune(SlashCommandInteractionEvent event)
    {
        OptionMapping amountOption = event.getOption("amount"); // This is configured to be optional so check for null
        int amount = amountOption == null
                ? 100 // default 100
                : (int) Math.min(200, Math.max(2, amountOption.getAsLong())); // enforcement: must be between 2-200
        String userId = event.getUser().getId();
        event.reply("This will delete " + amount + " messages.\nAre you sure?") // prompt the user with a button menu
            .addActionRow(// this means "<style>(<id>, <label>)", you can encode anything you want in the id (up to 100 characters)
                Button.secondary(userId + ":delete", "Nevermind!"),
                Button.danger(userId + ":prune:" + amount, "Yes!")) // the first parameter is the component id we use in onButtonInteraction above
            .queue();
    }
}
