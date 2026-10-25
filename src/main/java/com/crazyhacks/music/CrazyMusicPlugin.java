package com.crazyhacks.music;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.plugin.Plugin;
import org.rusherhack.core.logging.ILogger;

/**
 * CrazyMusic: the "now playing" box of CrazyHacks as a RusherHack plugin. Made by lurda55.
 */
public class CrazyMusicPlugin extends Plugin {
	static ILogger LOGGER;

	@Override
	public void onLoad() {
		LOGGER = this.getLogger();
		RusherHackAPI.getHudManager().registerFeature(new MusicHudElement());
		Runtime.getRuntime().addShutdownHook(new Thread(MediaInfo::stop));
	}

	@Override
	public void onUnload() {
		MediaInfo.stop();
	}
}
