/*
 This file is part of Airsonic.

 Airsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Airsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Airsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2023 (C) Y.Tory
 Copyright 2016 (C) Airsonic Authors
 Based upon Subsonic, Copyright 2009 (C) Sindre Mehus
 */
package org.airsonic.player.controller;

import com.auth0.jwt.interfaces.DecodedJWT;
import org.airsonic.player.config.AirsonicHomeConfig;
import org.airsonic.player.domain.MediaFile;
import org.airsonic.player.domain.Player;
import org.airsonic.player.domain.TransferStatus;
import org.airsonic.player.domain.User;
import org.airsonic.player.io.PipeStreams.MonitoredResource;
import org.airsonic.player.security.JWTAuthenticationToken;
import org.airsonic.player.service.JWTSecurityService;
import org.airsonic.player.service.MediaFileService;
import org.airsonic.player.service.PlayerService;
import org.airsonic.player.service.SecurityService;
import org.airsonic.player.service.SettingsService;
import org.airsonic.player.service.StatusService;
import org.airsonic.player.service.TranscodingService;
import org.airsonic.player.service.hls.HlsSession;
import org.airsonic.player.util.FileUtil;
import org.airsonic.player.util.NetworkUtil;
import org.airsonic.player.util.StringUtil;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.acls.model.NotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.awt.*;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Controller which produces the HLS (Http Live Streaming) playlist.
 *
 * @author Sindre Mehus
 */
@Controller("hlsController")
@RequestMapping({ "/hls", "/ext/hls", "/hls.view", "/ext/hls.view"})
public class HLSController {

    private static final Logger LOG = LoggerFactory.getLogger(HLSController.class);

    private static final int SEGMENT_DURATION = 10;
    private static final Pattern BITRATE_PATTERN = Pattern.compile("(\\d+)(@(\\d+)x(\\d+))?");

    private final PlayerService playerService;
    private final MediaFileService mediaFileService;
    private final SecurityService securityService;
    private final JWTSecurityService jwtSecurityService;
    private final StatusService statusService;
    private final SettingsService settingsService;
    private final TranscodingService transcodingService;
    private final AirsonicHomeConfig homeConfig;

    public HLSController(PlayerService playerService, MediaFileService mediaFileService, SecurityService securityService, JWTSecurityService jwtSecurityService, StatusService statusService, SettingsService settingsService, TranscodingService transcodingService, AirsonicHomeConfig homeConfig) {
        this.playerService = playerService;
        this.mediaFileService = mediaFileService;
        this.securityService = securityService;
        this.jwtSecurityService = jwtSecurityService;
        this.statusService = statusService;
        this.settingsService = settingsService;
        this.transcodingService = transcodingService;
        this.homeConfig = homeConfig;
        init();
    }

    private final Map<HlsSession.Key, HlsSession> sessions = new ConcurrentHashMap<>();

    public void init() {
        Path airsonicHome = this.homeConfig.getAirsonicHome();
        if (Files.exists(airsonicHome.resolve("hls"))) {
            FileUtil.delete(airsonicHome.resolve("hls"));
        }
    }

    @GetMapping("/hls.m3u8")
    public void handleHlsRequest(Authentication authentication,
            @RequestParam("id") Integer id,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        MediaFile mediaFile = mediaFileService.getMediaFile(id);
        String username = securityService.getCurrentUsername(request);
        Player player = playerService.getPlayer(request, response, username);

        if (mediaFile == null || mediaFile.isDirectory()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Media file not found or incorrect type: " + id);
            return;
        }

        if (!(authentication instanceof JWTAuthenticationToken)
                && !securityService.isFolderAccessAllowed(mediaFile, username)) {
            throw new AccessDeniedException("Access to file " + mediaFile.getId() + " is forbidden for user " + username);
        }

        Double duration = mediaFile.getDuration();
        if (duration == null || duration < 0.0001) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unknown duration for media file: " + id);
            return;
        }

        response.setContentType("application/x-mpegurl");
        response.setCharacterEncoding(StringUtil.ENCODING_UTF8);
        response.setHeader("Access-Control-Allow-Origin", "*");
        List<Pair<Integer, Dimension>> bitRates = parseBitRates(request).stream()
                .map(b -> b.getRight() != null ? b
                        : Pair.of(b.getLeft(), TranscodingService.getSuitableVideoSize(mediaFile.getWidth(),
                                mediaFile.getHeight(), b.getLeft())))
                .collect(Collectors.toList());
        if (bitRates.isEmpty())
            bitRates = Collections.singletonList(
                    Pair.of(VideoPlayerController.DEFAULT_BIT_RATE, TranscodingService.getSuitableVideoSize(
                            mediaFile.getWidth(), mediaFile.getHeight(), VideoPlayerController.DEFAULT_BIT_RATE)));

        String requestWithoutContextPath = request.getRequestURI().substring(request.getContextPath().length() + 1);
        UriComponentsBuilder prefix = UriComponentsBuilder.fromUriString(StringUtils.removeEndIgnoreCase(requestWithoutContextPath, "/hls.m3u8")); // ext/hls or hls
        String basePath = NetworkUtil.getBaseUrl(request);
        PrintWriter writer = response.getWriter();
        if (bitRates.size() > 1) {
            generateVariantPlaylist(authentication, basePath, prefix, id, player, bitRates, writer);
        } else {
            generateNormalPlaylist(authentication, basePath, prefix, id, player, bitRates.get(0), Math.round(duration), writer);
        }

        return;
    }

    private List<Pair<Integer, Dimension>> parseBitRates(HttpServletRequest request) throws IllegalArgumentException {
        List<Pair<Integer, Dimension>> result = new ArrayList<>();
        String[] bitRates = request.getParameterValues("maxBitRate");
        String globalSize = request.getParameter("size");
        if (bitRates != null) {
            for (String bitRate : bitRates) {
                result.add(parseBitRate(bitRate, globalSize));
            }
        }
        return result;
    }

    /**
     * Parses a string containing the bitrate and an optional width/height, e.g., 1200@640x480
     */
    protected Pair<Integer, Dimension> parseBitRate(String bitRate, String defaultSize) throws IllegalArgumentException {

        Matcher matcher = BITRATE_PATTERN.matcher(bitRate);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid bitrate specification: " + bitRate);
        }
        int kbps = Integer.parseInt(matcher.group(1));
        if (matcher.group(3) == null) {
            if (defaultSize == null) {
                return Pair.of(kbps, null);
            }
            String[] dims = StringUtils.split(defaultSize, "x");
            int width = (Integer.parseInt(dims[0]) / 2) * 2;
            int height = (Integer.parseInt(dims[1]) / 2) * 2;
            return Pair.of(kbps, new Dimension(width, height));
        } else {
            int width = (Integer.parseInt(matcher.group(3)) / 2) * 2;
            int height = (Integer.parseInt(matcher.group(4)) / 2) * 2;
            return Pair.of(kbps, new Dimension(width, height));
        }
    }

    private void generateVariantPlaylist(Authentication authentication, String basePath, UriComponentsBuilder prefix,
            int id, Player player, List<Pair<Integer, Dimension>> bitRates, PrintWriter writer) {
        writer.println("#EXTM3U");
        writer.println("#EXT-X-VERSION:1");
//        writer.println("#EXT-X-TARGETDURATION:" + SEGMENT_DURATION);

        for (Pair<Integer, Dimension> bitRate : bitRates) {
            Integer kbps = bitRate.getLeft();
            int averageVideoBitRate = TranscodingService.getAverageVideoBitRate(kbps);
            writer.println("#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=" + (kbps * 1000L) + ",AVERAGE-BANDWIDTH=" + (averageVideoBitRate * 1000L));
            UriComponentsBuilder url = prefix.cloneBuilder()
                    .pathSegment("hls.m3u8")
                    .queryParam("id", id)
                    .queryParam("player", player.getId())
                    .queryParam("maxBitRate", kbps + Optional.ofNullable(bitRate.getRight()).map(d -> "@" + d.width + "x" + d.height).orElse(""));
            if (authentication instanceof JWTAuthenticationToken) {
                DecodedJWT token = (DecodedJWT) authentication.getDetails();
                Instant expires = Optional.ofNullable(token).map(x -> x.getExpiresAt()).map(x -> x.toInstant()).orElse(null);
                url = jwtSecurityService.addJWTToken(authentication.getName(), url, expires);
            }

            writer.print(basePath + url.toUriString());
            writer.println();
        }
//        writer.println("#EXT-X-ENDLIST");
    }

    private void generateNormalPlaylist(Authentication authentication, String basePath, UriComponentsBuilder prefix,
            int id, Player player, Pair<Integer, Dimension> bitRate, long totalDuration, PrintWriter writer) {
        writer.println("#EXTM3U");
        writer.println("#EXT-X-VERSION:1");
        writer.println("#EXT-X-TARGETDURATION:" + SEGMENT_DURATION);

        for (int i = 0; i < totalDuration / SEGMENT_DURATION; i++) {
            writer.println("#EXTINF:" + SEGMENT_DURATION + ",");
            writer.println(createStreamUrl(authentication, basePath, prefix, player, id, i, SEGMENT_DURATION, bitRate));
        }

        long remainder = totalDuration % SEGMENT_DURATION;
        if (remainder > 0) {
            writer.println("#EXTINF:" + remainder + ",");
            writer.println(
                    createStreamUrl(authentication, basePath, prefix, player, id,
                            (int) (totalDuration / SEGMENT_DURATION),
                            remainder, bitRate));
        }
        writer.println("#EXT-X-ENDLIST");
    }

    private String createStreamUrl(Authentication authentication,
            String basePath, UriComponentsBuilder prefix,
            Player player,
            int id,
            int segmentIndex,
            long duration,
            Pair<Integer, Dimension> bitRate) {
        UriComponentsBuilder builder = prefix.cloneBuilder()
                .pathSegment("segment", "segment.ts")
                .queryParam("id", id)
                .queryParam("hls", "true")
                .queryParam("segmentIndex", segmentIndex)
                .queryParam("player", player.getId())
                .queryParam("duration", duration)
                .queryParam("maxBitRate", bitRate.getLeft())
                .queryParam("size", bitRate.getRight().width + "x" + bitRate.getRight().height);

        if (authentication instanceof JWTAuthenticationToken) {
            DecodedJWT token = (DecodedJWT) authentication.getDetails();
            Instant expires = Optional.ofNullable(token).map(x -> x.getExpiresAt()).map(x -> x.toInstant()).orElse(null);
            builder = jwtSecurityService.addJWTToken(authentication.getName(), builder, expires);
        }
        return basePath + builder.toUriString();
    }

    @GetMapping("/segment/**")
    public ResponseEntity<Resource> handleSegmentRequest(Authentication auth,
            @RequestParam(name = "id") int id,
            @RequestParam(name = "segmentIndex") int segmentIndex,
            @RequestParam(name = "player") String playerId,
            @RequestParam(name = "maxBitRate") int maxBitRate,
            @RequestParam(name = "size") String size,
            @RequestParam(required = false, name = "duration") @DefaultValue("10") Integer duration,
            @RequestParam(required = false, name = "audioTrack") Integer audioTrack,
            ServletWebRequest swr) throws Exception {
        MediaFile mediaFile = this.mediaFileService.getMediaFile(id);
        if (mediaFile == null) {
            throw new NotFoundException("Media file not found: " + id);
        }
        User user = securityService.getUserByName(auth.getName());
        Player player = this.playerService.getPlayer(swr.getRequest(), swr.getResponse(), user.getUsername());
        if (!(auth instanceof JWTAuthenticationToken)
                && !securityService.isFolderAccessAllowed(mediaFile, user.getUsername())) {
            throw new AccessDeniedException("Access to file " + id + " is forbidden for user " + user.getUsername());
        }
        TransferStatus status = this.statusService.createStreamStatus(player);
        status.setMediaFile(mediaFile);
        HlsSession.Key sessionKey = new HlsSession.Key(id, playerId, maxBitRate, size, duration, audioTrack);
        HlsSession session = getOrCreateSession(sessionKey, mediaFile);
        Path segmentFile = session.waitForSegment(segmentIndex, 30000L);
        if (segmentFile == null) {
            throw new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Timed out producing segment " + segmentIndex + " for media file " + id);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setAccessControlAllowOrigin("*");
        headers.setContentType(MediaType.parseMediaType("video/MP2T"));
        headers.setContentDisposition(ContentDisposition.inline().filename("f.ts").build());

        Supplier<TransferStatus> statusSupplier = () -> status;
        Consumer<TransferStatus> statusCloser = s -> {
            securityService.incrementBytesStreamed(user.getUsername(), s.getBytesTransferred());
            statusService.removeStreamStatus(s);
        };
        BiConsumer<InputStream, TransferStatus> inputStreamInit = (i, s) -> {
            LOG.info("{}: {} listening to {}", player.getIpAddress(), player.getUsername(), FileUtil.getShortPath(mediaFile.getRelativePath()));
            if (segmentIndex == 0)
                this.mediaFileService.incrementPlayCount(mediaFile);
        };

        Resource resource = new MonitoredResource(new PathResource(segmentFile),
                settingsService.getDownloadBitrateLimiter(), statusSupplier, statusCloser, inputStreamInit);

        return ResponseEntity.ok().headers(headers).body(resource);
    }

    private HlsSession getOrCreateSession(HlsSession.Key sessionKey, MediaFile mediaFile) {
        return this.sessions.computeIfAbsent(sessionKey, k -> {
            for (HlsSession.Key k1 : sessions.keySet()) {
                if (k1.getMediaFileId() == k.getMediaFileId()
                        && StringUtils.equals(k1.getPlayerId(), k.getPlayerId())) {
                    sessions.remove(k1).destroySession();
                }
            }

            return new HlsSession(k, mediaFile, transcodingService, homeConfig.getAirsonicHome().resolve("hls"));
        });
    }

}
