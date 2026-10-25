package com.crazyhacks.music;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.function.Consumer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.render.graphic.TextureGraphic;

/**
 * What is playing on this PC: the media Windows shows in its volume popup, so Spotify, Apple Music, YouTube Music,
 * YouTube, Netflix and podcasts in any browser or app. A small PowerShell script (Windows' own media API) runs in the
 * background while the HUD element is on and reports the title, artist, progress and cover; it also takes
 * play/pause, next and previous.
 */
public final class MediaInfo {
	/** What is playing now; title empty when nothing is. */
	public record Track(String title, String artist, String album, String app, boolean playing,
			double position, double duration, long updatedAt) {
		/** Seconds played right now (the media API only updates the position every few seconds). */
		public double positionNow() {
			if (!playing || updatedAt <= 0) return position;
			double p = position + (System.currentTimeMillis() - updatedAt) / 1000.0;
			return duration > 0 ? Math.min(p, duration) : p;
		}
	}

	private static final Track NONE = new Track("", "", "", "", false, 0, 0, 0);

	private static volatile Track track = NONE;
	private static volatile TextureGraphic cover;
	private static Process process;
	/** Called (on the reader thread) when a new song starts. */
	private static volatile Consumer<Track> onNewSong = t -> {};

	private MediaInfo() {
	}

	public static Track track() {
		return track;
	}

	/** Cover of the current song, null when there is none. */
	public static TextureGraphic cover() {
		return cover;
	}

	public static void onNewSong(Consumer<Track> listener) {
		onNewSong = listener;
	}

	public static boolean supported() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	private static Path dir() {
		return RusherHackAPI.getConfigPath().resolve("crazymusic");
	}

	/** Starts the reader if it is not running. */
	public static synchronized void ensureRunning() {
		if (!supported() || (process != null && process.isAlive())) return;
		try {
			Path script = dir().resolve("media.ps1");
			Files.createDirectories(script.getParent());
			Files.writeString(script, SCRIPT, StandardCharsets.UTF_8);
			// the game's process id: the script stops by itself when the game closes
			process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass",
					"-File", script.toString(), Long.toString(ProcessHandle.current().pid()))
					.redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("NUL"))).start();
			Process p = process;
			Thread reader = new Thread(() -> read(p), "CrazyMusic media");
			reader.setDaemon(true);
			reader.start();
		} catch (Exception e) {
			CrazyMusicPlugin.LOGGER.error("Could not start the media reader", e);
		}
	}

	public static synchronized void stop() {
		if (process != null) process.destroy();
		process = null;
		track = NONE;
		cover = null;
	}

	/** Play/pause ("playpause"), "next" or "prev" on the app that is playing. */
	public static void send(String command) {
		if (process == null || !process.isAlive()) return;
		try {
			// the script picks it up within half a second
			Files.writeString(dir().resolve("media-command.txt"), command + "\n", StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception ignored) {
		}
	}

	private static void read(Process p) {
		try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = in.readLine()) != null) {
				line = line.trim();
				if (!line.startsWith("{")) continue;
				try {
					JsonObject o = JsonParser.parseString(line).getAsJsonObject();
					if (o.has("cover")) {
						cover(o.get("cover").getAsString());
					} else if (o.has("none")) {
						track = NONE;
						cover = null;
					} else {
						String artist = str(o, "artist");
						String album = str(o, "album");
						// Apple Music puts "Artist — Album" in the artist
						int dash = artist.indexOf(" — ");
						if (dash > 0) {
							if (album.isEmpty()) album = artist.substring(dash + 3);
							artist = artist.substring(0, dash);
						}
						Track old = track;
						String title = str(o, "title");
						boolean playing = o.has("playing") && o.get("playing").getAsBoolean();
						long nowMs = System.currentTimeMillis();
						// where the app says it is now (its position is often rounded to the second)
						long upd = (long) num(o, "upd");
						double reported = num(o, "pos") + (playing && upd > 0 && upd <= nowMs ? (nowMs - upd) / 1000.0 : 0);
						// keep our own running clock and only follow the app on a new song, play/pause or a real jump,
						// so the time does not step back and forth (0:35, 0:36, 0:35...)
						boolean sameSong = title.equals(old.title()) && artist.equals(old.artist());
						boolean keep = sameSong && playing == old.playing() && Math.abs(reported - old.positionNow()) < 1.5;
						double anchorPos = keep ? old.position() : reported;
						long anchorAt = keep ? old.updatedAt() : nowMs;
						String appName = str(o, "appName");
						track = new Track(title, artist, album, appName.isEmpty() ? app(str(o, "app")) : appName, playing, anchorPos, num(o, "dur"), anchorAt);
						if (!title.isEmpty() && !title.equals(old.title())) onNewSong.accept(track);
					}
				} catch (RuntimeException ignored) {
				}
			}
		} catch (Exception ignored) {
		}
	}

	/** A readable app name from Windows' id ("Spotify.exe", "Chrome", "Microsoft.ZuneMusic_8wekyb..!Microsoft.ZuneMusic"...). */
	private static String app(String id) {
		String s = id.toLowerCase();
		if (s.contains("spotify")) return "Spotify";
		if (s.contains("applemusic") || s.contains("apple")) return "Apple Music";
		if (s.contains("chrome")) return "Chrome";
		if (s.contains("msedge") || s.contains("edge")) return "Edge";
		if (s.contains("firefox")) return "Firefox";
		if (s.contains("opera")) return "Opera";
		if (s.contains("brave")) return "Brave";
		if (s.contains("netflix")) return "Netflix";
		if (s.contains("zunemusic")) return "Media Player";
		int bang = id.lastIndexOf('!');
		String name = bang >= 0 ? id.substring(bang + 1) : id;
		return name.replace(".exe", "").replace(".EXE", "");
	}

	private static void cover(String base64) {
		if (base64.isEmpty()) {
			cover = null;
			return;
		}
		try {
			cover = new TextureGraphic(new ByteArrayInputStream(Base64.getDecoder().decode(base64)), 64, 64);
		} catch (Exception e) {
			cover = null;
		}
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
	}

	private static double num(JsonObject o, String k) {
		try {
			return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : 0;
		} catch (RuntimeException e) {
			return 0;
		}
	}

	/** Windows media reader: one JSON line per update, a "cover" line (64x64 PNG, base64) when the cover changes. */
	private static final String SCRIPT = """
			$ErrorActionPreference = 'SilentlyContinue'
			[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
			Add-Type -AssemblyName System.Runtime.WindowsRuntime
			Add-Type -AssemblyName System.Drawing
			$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
			function Await($op, [Type]$type) { $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op)); [void]$t.Wait(5000); $t.Result }
			[void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
			[void][Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]
			[void][Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType = WindowsRuntime]
			$readerCtor = [Windows.Storage.Streams.DataReader].GetConstructor(@([Windows.Storage.Streams.IInputStream]))
			$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
			# commands (playpause, next, prev) come in a file next to this script: reading the input would block
			$cmdFile = Join-Path $PSScriptRoot 'media-command.txt'
			# readable app names: browsers report an id like 308046B0AF4A39CB (Firefox), the Start menu knows the name
			$appNames = @{}
			try { foreach ($a in Get-StartApps) { if ($a.AppID -and -not $appNames.ContainsKey($a.AppID)) { $appNames[$a.AppID] = $a.Name } } } catch { }
			$parent = Get-Process -Id $args[0]
			$lastKey = $null
			# title/artist are asked to the playing app only every few seconds (asking it too often can make apps
			# like Apple Music hang); position and play/pause are kept by Windows itself and read every half second
			$p = $null
			$lastApp = $null
			$loop = 0
			while ($true) {
			  # the game closed: stop too
			  if ($parent.HasExited) { break }
			  if (Test-Path $cmdFile) {
			    $cmds = Get-Content $cmdFile
			    Remove-Item $cmdFile
			    $s = $mgr.GetCurrentSession()
			    if ($s) {
			      foreach ($cmd in $cmds) {
			        switch ($cmd.Trim()) {
			          'playpause' { [void](Await ($s.TryTogglePlayPauseAsync()) ([bool])) }
			          'next' { [void](Await ($s.TrySkipNextAsync()) ([bool])); $p = $null }
			          'prev' { [void](Await ($s.TrySkipPreviousAsync()) ([bool])); $p = $null }
			        }
			      }
			    }
			  }
			  $s = $mgr.GetCurrentSession()
			  if ($s) {
			    $loop++
			    if ($null -eq $p -or $s.SourceAppUserModelId -ne $lastApp -or $loop % 6 -eq 0) {
			      $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
			      $lastApp = $s.SourceAppUserModelId
			    }
			    if ($null -eq $p) { Start-Sleep -Milliseconds 500; continue }
			    $tl = $s.GetTimelineProperties()
			    $pb = $s.GetPlaybackInfo()
			    $o = [ordered]@{
			      title = [string]$p.Title; artist = [string]$p.Artist; album = [string]$p.AlbumTitle; app = [string]$s.SourceAppUserModelId
			      appName = [string]$appNames[$s.SourceAppUserModelId]
			      playing = ($pb.PlaybackStatus -eq 'Playing'); pos = $tl.Position.TotalSeconds; dur = $tl.EndTime.TotalSeconds
			      upd = $tl.LastUpdatedTime.ToUnixTimeMilliseconds()
			    }
			    [Console]::Out.WriteLine(($o | ConvertTo-Json -Compress))
			    $key = "$($p.Title)|$($p.Artist)|$($s.SourceAppUserModelId)"
			    if ($key -ne $lastKey) {
			      $lastKey = $key
			      $b64 = ''
			      if ($p.Thumbnail) {
			        try {
			          $ras = Await ($p.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
			          # PowerShell 5.1 cannot cast the stream itself; the constructor called by reflection can
			          $reader = $readerCtor.Invoke(@($ras))
			          $n = Await ($reader.LoadAsync(4000000)) ([uint32])
			          $bytes = New-Object byte[] $n
			          $reader.ReadBytes($bytes)
			          $img = [System.Drawing.Image]::FromStream((New-Object System.IO.MemoryStream(,$bytes)))
			          $bmp = New-Object System.Drawing.Bitmap 64, 64
			          $g = [System.Drawing.Graphics]::FromImage($bmp)
			          $g.InterpolationMode = 'HighQualityBicubic'
			          $side = [Math]::Min($img.Width, $img.Height)
			          $g.DrawImage($img, (New-Object System.Drawing.Rectangle 0, 0, 64, 64), [int](($img.Width - $side) / 2), [int](($img.Height - $side) / 2), $side, $side, 'Pixel')
			          $ms = New-Object System.IO.MemoryStream
			          $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
			          $b64 = [Convert]::ToBase64String($ms.ToArray())
			          $g.Dispose(); $bmp.Dispose(); $img.Dispose()
			        } catch { $b64 = '' }
			      }
			      [Console]::Out.WriteLine((@{ cover = $b64 } | ConvertTo-Json -Compress))
			    }
			  } else {
			    $p = $null
			    if ($lastKey -ne '') { $lastKey = ''; [Console]::Out.WriteLine('{"none":true}') }
			  }
			  [Console]::Out.Flush()
			  Start-Sleep -Milliseconds 500
			}
			""";
}
