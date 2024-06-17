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

  Copyright 2024 (C) Y.Tory
  Copyright 2017 (C) Airsonic Authors
  Based upon Subsonic, Copyright 2009 (C) Sindre Mehus
*/
package org.airsonic.player.service.upnp;

public interface UpnpProcessorRouter {

    public UpnpContentProcessor<?, ?> findProcessor(ProcessorType type);

    public AlbumUpnpProcessor getAlbumProcessor();

    public ArtistUpnpProcessor getArtistProcessor();

    public MediaFileUpnpProcessor getMediaFileProcessor();

    public RecentAlbumUpnpProcessor getRecentProcessor();

    public PlaylistUpnpProcessor getPlaylistProcessor();

    public GenreUpnpProcessor getGenreProcessor();

}
