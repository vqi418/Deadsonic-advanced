package org.airsonic.player.controller;

import org.airsonic.player.domain.Playlist;
import org.airsonic.player.service.PlaylistFileService;
import org.airsonic.player.service.PlaylistService;
import org.airsonic.player.service.SecurityService;
import org.airsonic.player.util.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.ServletRequestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Spring MVC Controller that serves the login page.
 */
@Controller
@RequestMapping({"/exportPlaylist", "/exportPlaylist.view"})
public class ExportPlayListController {

    @Autowired
    private PlaylistService playlistService;
    @Autowired
    private SecurityService securityService;

    @Autowired
    private PlaylistFileService playlistFileService;

    @GetMapping
    public ModelAndView exportPlaylist(HttpServletRequest request, HttpServletResponse response) throws Exception {

        int id = ServletRequestUtils.getRequiredIntParameter(request, "id");
        Playlist playlist = playlistService.getPlaylist(id);
        if (!playlistService.isReadAllowed(playlist, securityService.getCurrentUsername(request))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return null;

        }
        response.setContentType("application/x-download");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + StringUtil.fileSystemSafe(playlist.getName()) + ".m3u8\"");

        playlistFileService.exportPlaylist(id, response.getOutputStream());
        return null;
    }


}
