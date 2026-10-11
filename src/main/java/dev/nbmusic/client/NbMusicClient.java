package dev.nbmusic.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.nbmusic.NbMusicMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class NbMusicClient implements ClientModInitializer {
	public static final KeyMapping OPEN = new KeyMapping("key.nbmusic.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R,
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(NbMusicMod.MOD_ID, "main")));

	@Override
	public void onInitializeClient() {
		KeyMappingHelper.registerKeyMapping(OPEN);
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (OPEN.consumeClick()) {
				if (mc.gui.screen() == null) {
					mc.gui.setScreen(new NbMusicScreen(null));
				}
			}
			PreviewPlayer.INSTANCE.tick();
			Hologram.tick(mc);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			PreviewPlayer.INSTANCE.stop();
			ClientSession.INSTANCE.pinned = null;
		});
	}
}
