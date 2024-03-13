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
 */
package org.airsonic.player.service;

import org.airsonic.player.domain.Artist;
import org.airsonic.player.domain.MusicFolder;
import org.airsonic.player.domain.entity.StarredArtist;
import org.airsonic.player.repository.ArtistRepository;
import org.airsonic.player.repository.OffsetBasedPageRequest;
import org.airsonic.player.repository.StarredArtistRepository;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Service
public class ArtistService {

    private final ArtistRepository artistRepository;

    private final StarredArtistRepository starredArtistRepository;

    private static final Logger LOG = LoggerFactory.getLogger(ArtistService.class);

    public ArtistService(ArtistRepository artistRepository, StarredArtistRepository starredArtistRepository) {
        this.artistRepository = artistRepository;
        this.starredArtistRepository = starredArtistRepository;
    }

    public List<Artist> getArtists(List<MusicFolder> musicFolders, int count, int offset) {
        if (CollectionUtils.isEmpty(musicFolders)) {
            LOG.warn("getArtists: musicFolders is null");
            return Collections.emptyList();
        }
        return artistRepository.findByFolderInAndPresentTrue(musicFolders, new OffsetBasedPageRequest(offset, count, Sort.by("id")));
    }

    /**
     * Get artist by id
     *
     * @param id artist id
     * @return artist dto or null
     */
    public Artist getArtist(Integer id) {
        if (id == null) {
            LOG.warn("getArtist: id is null");
            return null;
        }
        return artistRepository.findById(id).orElse(null);
    }

    /**
     * Get artist by name
     *
     * @param artistName artist name. If null or empty, return null
     * @return artist or null
     */
    public Artist getArtist(String artistName) {
        if (!StringUtils.hasLength(artistName)) {
            LOG.warn("getArtist: artistName is null");
            return null;
        }
        return artistRepository.findByName(artistName).orElse(null);
    }

    /**
     * Get artist by name and music folders
     *
     * @param artistName artist name
     * @param musicFolders music folders. If null or empty, return null
     * @return artist dto or null
     */
    public Artist getArtist(String artistName, List<MusicFolder> musicFolders) {
        if (CollectionUtils.isEmpty(musicFolders) || !StringUtils.hasLength(artistName)) {
            LOG.warn("getArtist: musicFolders is null or artistName is null");
            return null;
        }
        return artistRepository.findByNameAndFolderIn(artistName, musicFolders).orElse(null);
    }

    /**
     * Get artists by music folders and pagination
     *
     * @param musicFolders music folders. If null or empty, return empty list
     * @return
     */
    public List<Artist> getAlphabeticalArtists(final List<MusicFolder> musicFolders) {
        if (CollectionUtils.isEmpty(musicFolders)) {
            LOG.warn("getAlphabeticalArtists: musicFolders is null");
            return Collections.emptyList();
        }
        Sort sort = Sort.by(Sort.Direction.ASC, "name");
        return artistRepository.findByFolderInAndPresentTrue(musicFolders, sort);
    }

    /**
     * Get starred artists by username and music folders
     *
     * @param username username to check. If null or empty, return empty list
     * @param musicFolders music folders. If null or empty, return empty list
     * @return list of starred artists or empty list. Sorted by starred date descending.
     */
    @Transactional
    public List<Artist> getStarredArtists(String username, List<MusicFolder> musicFolders) {
        if (CollectionUtils.isEmpty(musicFolders) || !StringUtils.hasLength(username)) {
            LOG.warn("getStarredArtists: musicFolders or username is null");
            return Collections.emptyList();
        }
        return starredArtistRepository.findByUsernameAndArtistFolderInAndArtistPresentTrue(username, musicFolders, Sort.by(Sort.Direction.DESC, "created")).stream().map(StarredArtist::getArtist).toList();
    }

    /**
     * Star or unstar artist
     *
     * @param artistId artist id
     * @param username username to star artist for
     * @param star true to star, false to unstar
     * @return true if success, false otherwise
     */
    @Transactional
    public boolean starOrUnstar(Integer artistId, String username, boolean star) {
        if (artistId == null || !StringUtils.hasLength(username)) {
            LOG.warn("star: artistId or username is null");
            return false;
        }
        return artistRepository.findById(artistId).map(artist -> {
            if (star) {
                starredArtistRepository.save(new StarredArtist(artist, username, Instant.now()));
            } else {
                starredArtistRepository.deleteByArtistIdAndUsername(artist.getId(), username);
            }
            return true;
        }).orElse(false);
    }

    /**
     * Get starred date for artist
     *
     * @param artistId artist id
     * @param username username to check. If null or empty, return null
     * @return starred date or null
     */
    @Transactional
    public Instant getStarredDate(Integer artistId, String username) {
        if (artistId == null || !StringUtils.hasLength(username)) {
            LOG.warn("getStarredDate: artistId or username is null");
            return null;
        }
        return starredArtistRepository.findByArtistIdAndUsername(artistId, username).map(StarredArtist::getCreated).orElse(null);
    }

    /**
     * Expunge artists that are not present
     */
    @Transactional
    public void expunge() {
        artistRepository.deleteAllByPresentFalse();
    }

    /**
     * Mark artists that are not present
     *
     * @param lastScanned last scanned date
     */
    @Transactional
    public void markNonPresent(Instant lastScanned) {
        if (artistRepository.existsByLastScannedBeforeAndPresentTrue(lastScanned)) {
            artistRepository.markNonPresent(lastScanned);
        }
    }

    /**
     * Save artist
     *
     * @param artist artist to save
     * @return saved artist
     */
    @Transactional
    public Artist save(Artist artist) {
        artistRepository.save(artist);
        return artist;
    }

}
