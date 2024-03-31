package org.airsonic.player.ajax;

import org.airsonic.player.domain.MediaFile;
import org.airsonic.player.domain.MusicFolder;
import org.airsonic.player.domain.Player;
import org.airsonic.player.domain.Playlist;
import org.airsonic.player.i18n.LocaleResolver;
import org.airsonic.player.service.MediaFileService;
import org.airsonic.player.service.MediaFolderService;
import org.airsonic.player.service.PlayerService;
import org.airsonic.player.service.PlaylistService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Controller
@MessageMapping("/playlists")
public class PlaylistWSController {
    @Autowired
    private MediaFileService mediaFileService;
    @Autowired
    private PlaylistService playlistService;
    @Autowired
    private MediaFolderService mediaFolderService;
    @Autowired
    private PlayerService playerService;
    @Autowired
    private LocaleResolver localeResolver;

    @SubscribeMapping("/readable")
    public List<Playlist> getReadablePlaylists(Principal p) {
        return playlistService.getReadablePlaylistsForUser(p.getName());
    }

    @MessageMapping("/writable")
    @SendToUser(broadcast = false)
    public List<Playlist> getWritablePlaylists(Principal p) {
        return playlistService.getWritablePlaylistsForUser(p.getName());
    }

    /**
     * Creates a playlist and broadcasts it to all users that have access to it.
     *
     * @param playlist the playlist to create
     * @return the id of the created playlist
     */
    private Playlist createPlaylist(String name, boolean shared, String username) {
        Playlist result = playlistService.createPlaylist(name, shared, username);
        playlistService.broadcast(result);
        return result;
    }

    @MessageMapping("/create/empty")
    @SendToUser(broadcast = false)
    public int createEmptyPlaylist(Principal p) {
        Locale locale = localeResolver.resolveLocale(p.getName());
        DateTimeFormatter dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale);
        Instant now = Instant.now();
        Playlist result = createPlaylist(dateFormat.format(now.atZone(ZoneId.systemDefault())), false, p.getName());
        return result.getId();
    }

    @MessageMapping("/create/starred")
    @SendToUser(broadcast = false)
    public int createPlaylistForStarredSongs(Principal p) {
        Locale locale = localeResolver.resolveLocale(p.getName());
        DateTimeFormatter dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale);

        ResourceBundle bundle = ResourceBundle.getBundle("org.airsonic.player.i18n.ResourceBundle", locale);
        Instant now = Instant.now();
        String name = bundle.getString("top.starred") + " " + dateFormat.format(now.atZone(ZoneId.systemDefault()));
        String username = p.getName();

        Playlist result = createPlaylist(name, false, username);
        List<MusicFolder> musicFolders = mediaFolderService.getMusicFoldersForUser(username);
        List<MediaFile> songs = mediaFileService.getStarredSongs(0, Integer.MAX_VALUE, username, musicFolders);
        Integer playlistId = result.getId();
        playlistService.setFilesInPlaylist(playlistId, songs);
        playlistService.broadcastFileChange(playlistId, false, true);
        return result.getId();
    }

    @MessageMapping("/create/playqueue")
    @SendToUser(broadcast = false)
    public int createPlaylistForPlayQueue(Principal p, Integer playerId) throws Exception {
        Player player = playerService.getPlayerById(playerId);
        Locale locale = localeResolver.resolveLocale(p.getName());
        DateTimeFormatter dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale);

        Instant now = Instant.now();
        Playlist playlist = new Playlist();
        playlist.setUsername(p.getName());
        playlist.setShared(false);
        playlist.setName(dateFormat.format(now.atZone(ZoneId.systemDefault())));

        Playlist result = createPlaylist(dateFormat.format(now.atZone(ZoneId.systemDefault())), false, p.getName());
        Integer playlistId = result.getId();
        playlistService.setFilesInPlaylist(playlistId, player.getPlayQueue().getFiles());
        playlistService.broadcastFileChange(playlistId, false, true);

        return playlistId;
    }

    @MessageMapping("/delete")
    public void deletePlaylist(int id) {
        playlistService.deletePlaylist(id);
        playlistService.broadcastDeleted(id);
    }

    @MessageMapping("/update")
    public void updatePlaylist(PlaylistUpdateRequest req) {
        Playlist playlist = playlistService.getPlaylist(req.getId());
        if (playlist == null) {
            return;
        }
        playlistService.updatePlaylist(req.getId(), req.getName(), req.getComment(), req.getShared());
        playlistService.broadcastFileChange(req.getId(), playlist.getShared(), false);
    }

    @MessageMapping("/files/append")
    @SendToUser(broadcast = false)
    public int appendToPlaylist(PlaylistFilesModificationRequest req) {
        // in this context, modifierIds are mediafile ids
        List<MediaFile> files = Stream
                .concat(playlistService.getFilesInPlaylist(req.getId(), true).stream(),
                        req.getModifierIds().stream().map(mediaFileService::getMediaFile).filter(Objects::nonNull))
                .collect(Collectors.toList());

        playlistService.setFilesInPlaylist(req.getId(), files);
        playlistService.broadcastFileChange(req.getId(), false, true);

        return req.getId();
    }

    @MessageMapping("/files/remove")
    @SendToUser(broadcast = false)
    public int remove(PlaylistFilesModificationRequest req) {
        // in this context, modifierIds are indices
        List<Integer> indices = req.getModifierIds();
        Collections.sort(indices);
        playlistService.removeFilesInPlaylistByIndices(req.getId(), indices);
        playlistService.broadcastFileChange(req.getId(), false, true);
        return req.getId();
    }

    @MessageMapping("/files/moveup")
    @SendToUser(broadcast = false)
    public int up(PlaylistFilesModificationRequest req) {
        // in this context, modifierIds has one element that is the index of the file
        List<MediaFile> files = playlistService.getFilesInPlaylist(req.getId(), true);
        if (req.getModifierIds().size() == 1 && req.getModifierIds().get(0) > 0) {
            Collections.swap(files, req.getModifierIds().get(0), req.getModifierIds().get(0) - 1);
            playlistService.setFilesInPlaylist(req.getId(), files);
            playlistService.broadcastFileChange(req.getId(), false, true);
        }

        return req.getId();
    }

    @MessageMapping("/files/movedown")
    @SendToUser(broadcast = false)
    public int down(PlaylistFilesModificationRequest req) {
        // in this context, modifierIds has one element that is the index of the file
        List<MediaFile> files = playlistService.getFilesInPlaylist(req.getId(), true);
        if (req.getModifierIds().size() == 1 && req.getModifierIds().get(0) < files.size() - 1) {
            Collections.swap(files, req.getModifierIds().get(0), req.getModifierIds().get(0) + 1);
            playlistService.setFilesInPlaylist(req.getId(), files);
            playlistService.broadcastFileChange(req.getId(), false, true);
        }

        return req.getId();
    }

    @MessageMapping("/files/rearrange")
    @SendToUser(broadcast = false)
    public int rearrange(PlaylistFilesModificationRequest req) {
        // in this context, modifierIds are indices
        List<MediaFile> files = playlistService.getFilesInPlaylist(req.getId(), true);
        MediaFile[] newFiles = new MediaFile[files.size()];
        for (int i = 0; i < req.getModifierIds().size(); i++) {
            newFiles[i] = files.get(req.getModifierIds().get(i));
        }
        playlistService.setFilesInPlaylist(req.getId(), Arrays.asList(newFiles));
        playlistService.broadcastFileChange(req.getId(), false, true);

        return req.getId();
    }

    @SubscribeMapping("/{id}")
    public Playlist getPlaylist(Principal p, @DestinationVariable("id") int id) {
        return new PlaylistService.BroadcastedPlaylist(playlistService.getPlaylist(id), true);
    }

    @MessageMapping("/files/{id}")
    @SendToUser(broadcast = false)
    public List<MediaFileEntry> getPlaylistEntries(Principal p, @DestinationVariable("id") int id) {
        return mediaFileService.toMediaFileEntryList(playlistService.getFilesInPlaylist(id, true), p.getName(), true, true, null, null, null);
    }

    public static class PlaylistFilesModificationRequest {
        private int id;
        private List<Integer> modifierIds;

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public List<Integer> getModifierIds() {
            return modifierIds;
        }

        public void setModifierIds(List<Integer> modifierIds) {
            this.modifierIds = modifierIds;
        }
    }

    public static class PlaylistUpdateRequest {
        private int id;
        private String name;
        private String comment;
        private boolean shared;

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getComment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment;
        }

        public boolean getShared() {
            return shared;
        }

        public void setShared(boolean shared) {
            this.shared = shared;
        }
    }
}
